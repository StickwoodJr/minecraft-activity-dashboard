package com.playtime.dashboard.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class LogParser {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LOG_PATTERN = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})\\]");
    private static final Pattern JOIN_PATTERN = Pattern.compile("] (\\w+) joined the game$");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("] (\\w+) left the game$");
    private static final Pattern BOOT_PATTERN = Pattern.compile("Loading Minecraft");
    private static final Pattern STOP_PATTERN = Pattern.compile("Stopping server");

    private static final long INCREMENTAL_MAX_READ_BYTES = 10 * 1024 * 1024; // 10MB chunking

    private final File logsDir;
    private final File cacheFile;
    private final Map<String, LocalDateTime> activeSessions = new HashMap<>();
    private long lastByteOffset = 0;
    private LocalDateTime incrementalLastTs = null;

    public LogParser(File gameDir, File cacheFile) {
        String customDir = DashboardConfig.get().logs_directory;
        if (customDir != null && !customDir.isEmpty()) {
            this.logsDir = new File(customDir);
        } else {
            this.logsDir = new File(gameDir, "logs");
        }
        this.cacheFile = cacheFile;
    }

    public void parseAll() {
        FabricDashboardMod.LOGGER.info("Starting full log re-parse...");
        DashboardData data = new DashboardData();
        Map<String, LocalDateTime> sessions = new HashMap<>();

        File[] files = logsDir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".log.gz"));
        if (files == null) return;

        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        for (File f : files) {
            String dateStr = extractDate(f.getName());
            if (dateStr == null) continue;

            LocalDateTime fileLastTs = null;
            if (f.getName().endsWith(".gz")) {
                try (GZIPInputStream gzis = new GZIPInputStream(new java.io.FileInputStream(f));
                     Scanner scanner = new Scanner(gzis, StandardCharsets.UTF_8)) {
                    while (scanner.hasNextLine()) {
                        fileLastTs = processLine(scanner.nextLine(), dateStr, sessions, data, fileLastTs);
                    }
                } catch (IOException e) {
                    FabricDashboardMod.LOGGER.error("Failed to parse " + f.getName(), e);
                }
            } else {
                try (Scanner scanner = new Scanner(f, StandardCharsets.UTF_8)) {
                    while (scanner.hasNextLine()) {
                        fileLastTs = processLine(scanner.nextLine(), dateStr, sessions, data, fileLastTs);
                    }
                } catch (IOException e) {
                    FabricDashboardMod.LOGGER.error("Failed to parse " + f.getName(), e);
                }
            }
        }

        // Close any remaining active sessions at the last known timestamp
        LocalDateTime finalTs = LocalDateTime.now();
        for (Map.Entry<String, LocalDateTime> entry : sessions.entrySet()) {
            closeSession(entry.getKey(), entry.getValue(), finalTs, data);
        }

        saveCache(cacheFile, data);
        lastByteOffset = new File(logsDir, "latest.log").length();
        FabricDashboardMod.LOGGER.info("Full re-parse complete.");
    }

    public void parseIncremental() {
        File latestLog = new File(logsDir, "latest.log");
        if (!latestLog.exists()) return;

        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        long fileLen = latestLog.length();
        if (fileLen < lastByteOffset) {
            lastByteOffset = 0;
            // activeSessions and incrementalLastTs are intentionally preserved across log rotations
        }
        if (fileLen <= lastByteOffset) return;

        long readLen = Math.min(fileLen - lastByteOffset, INCREMENTAL_MAX_READ_BYTES);
        byte[] buffer = new byte[(int) readLen];
        try (RandomAccessFile raf = new RandomAccessFile(latestLog, "r")) {
            raf.seek(lastByteOffset);
            raf.readFully(buffer);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed incremental parse (read)", e);
            return;
        }

        // Process up to the last complete line so we never decode mid-codepoint
        // and so partial trailing lines get re-read on the next pass.
        int lastNewline = -1;
        for (int i = buffer.length - 1; i >= 0; i--) {
            if (buffer[i] == '\n') { lastNewline = i; break; }
        }
        if (lastNewline < 0) return;

        int processedLen = lastNewline + 1;
        String chunk = new String(buffer, 0, processedLen, StandardCharsets.UTF_8);

        DashboardData[] dataRef = new DashboardData[1];
        int from = 0;
        int len = chunk.length();
        for (int i = 0; i < len; i++) {
            if (chunk.charAt(i) == '\n') {
                int end = i;
                if (end > from && chunk.charAt(end - 1) == '\r') end--;
                if (end > from) {
                    String line = chunk.substring(from, end);
                    if (dataRef[0] == null
                            && (line.indexOf("joined the game") >= 0
                                || line.indexOf("left the game") >= 0
                                || BOOT_PATTERN.matcher(line).find()
                                || STOP_PATTERN.matcher(line).find())) {
                        dataRef[0] = loadCache(cacheFile);
                    }
                    incrementalLastTs = processLine(line, dateStr, activeSessions, dataRef[0], incrementalLastTs);
                }
                from = i + 1;
            }
        }

        lastByteOffset += processedLen;

        if (dataRef[0] != null) {
            saveCache(cacheFile, dataRef[0]);
        }
    }

    private String extractDate(String filename) {
        Matcher m = DATE_PATTERN.matcher(filename);
        if (m.find()) return m.group(1);
        return null;
    }

    private static boolean isInterestingLine(String line) {
        return line.indexOf("joined the game") >= 0
            || line.indexOf("left the game") >= 0
            || line.indexOf("Stopping server") >= 0
            || line.indexOf("Loading Minecraft") >= 0
            || line.indexOf("Environment:") >= 0
            || line.indexOf("Starting minecraft server version") >= 0;
    }

    private LocalDateTime processLine(String line, String dateStr, Map<String, LocalDateTime> sessions, DashboardData data, LocalDateTime lastTimestamp) {
        Matcher timeMatch = LOG_PATTERN.matcher(line);
        LocalDateTime currentTs = lastTimestamp;
        if (timeMatch.find()) {
            String timeStr = timeMatch.group(1);
            try {
                currentTs = LocalDateTime.parse(dateStr + " " + timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (DateTimeParseException e) {
                // Keep currentTs as lastTimestamp if parsing fails
            }
        }

        if (!isInterestingLine(line)) return currentTs;

        // Reset sessions on server lifecycle events
        boolean isBoot = BOOT_PATTERN.matcher(line).find();
        boolean isStop = STOP_PATTERN.matcher(line).find();

        if (isBoot || isStop) {
            if (data != null && !sessions.isEmpty()) {
                LocalDateTime endTs;
                String logReason;
                
                if (isStop) {
                    endTs = currentTs;
                    logReason = "CLEAN STOP";
                } else {
                    // Unexpected boot / crash
                    if (lastTimestamp != null) {
                        endTs = lastTimestamp;
                        logReason = "CRASH (closing at last known timestamp: " + lastTimestamp + ")";
                    } else {
                        endTs = currentTs;
                        logReason = "CRASH (no previous timestamp, closing at boot: " + currentTs + ")";
                    }
                }

                for (Map.Entry<String, LocalDateTime> entry : sessions.entrySet()) {
                    FabricDashboardMod.LOGGER.info("FORCE CLOSING ghost session for " + entry.getKey() + " [" + logReason + "]. Start: " + entry.getValue() + ", End: " + endTs);
                    closeSession(entry.getKey(), entry.getValue(), endTs, data);
                }
            }
            sessions.clear();
            return currentTs;
        }
        
        Matcher joinMatch = JOIN_PATTERN.matcher(line);
        if (joinMatch.find()) {
            String player = normalizePlayer(joinMatch.group(1));
            if (DashboardConfig.get().isPlayerIgnored(player)) return currentTs;
            
            if (sessions.containsKey(player) && data != null) {
                closeSession(player, sessions.get(player), currentTs, data);
            }
            sessions.put(player, currentTs);
            return currentTs;
        }
        
        Matcher leaveMatch = LEAVE_PATTERN.matcher(line);
        if (leaveMatch.find()) {
            String player = normalizePlayer(leaveMatch.group(1));
            if (DashboardConfig.get().isPlayerIgnored(player)) return currentTs;
            
            if (sessions.containsKey(player)) {
                if (data != null) {
                    closeSession(player, sessions.get(player), currentTs, data);
                }
                sessions.remove(player);
            }
        }
        return currentTs;
    }

    private String normalizePlayer(String p) {
        return DashboardConfig.get().getNormalizedName(p);
    }

    private void closeSession(String player, LocalDateTime startTs, LocalDateTime endTs, DashboardData data) {
        if (endTs.isBefore(startTs) || data == null) return;
        
        double sessionTotalMinutes = ChronoUnit.SECONDS.between(startTs, endTs) / 60.0;
        if (sessionTotalMinutes <= 0) return;

        // Update SessionData (average etc)
        DashboardData.SessionData sData = data.sessData.computeIfAbsent(player, k -> new DashboardData.SessionData());
        double totalMinutesCombined = sData.sessions * sData.avg;
        sData.sessions++;
        sData.avg = (totalMinutesCombined + sessionTotalMinutes) / sData.sessions;
        if (sessionTotalMinutes > sData.longestSession) {
            sData.longestSession = sessionTotalMinutes;
            sData.longestSessionDate = startTs.toLocalDate().toString();
        }

        // Distribute minutes to days/hours accurately. Hoist map lookups to a single computeIfAbsent per segment.
        LocalDateTime current = startTs;
        while (current.isBefore(endTs)) {
            LocalDateTime nextHour = current.plusHours(1).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime segmentEnd = endTs.isBefore(nextHour) ? endTs : nextHour;
            double segmentMinutes = ChronoUnit.SECONDS.between(current, segmentEnd) / 60.0;

            String cDateStr = current.toLocalDate().toString();
            int hourIndex = current.getHour();

            data.daily.merge(cDateStr, segmentMinutes / 60.0, Double::sum);

            Map<String, Double> dailyForDate = data.playerDailyRaw.computeIfAbsent(cDateStr, k -> new HashMap<>());
            dailyForDate.merge(player, segmentMinutes, Double::sum);

            Map<String, double[]> hourlyForDate = data.hourly.computeIfAbsent(cDateStr, k -> new HashMap<>());
            double[] hourArr = hourlyForDate.computeIfAbsent(player, k -> new double[24]);
            hourArr[hourIndex] += segmentMinutes;

            current = segmentEnd;
        }
    }

    private DashboardData loadCache(File cacheFile) {
        if (!cacheFile.exists()) return new DashboardData();
        try (Reader reader = new FileReader(cacheFile)) {
            DashboardData data = GSON.fromJson(reader, DashboardData.class);
            return data != null ? data : new DashboardData();
        } catch (Exception e) {
            return new DashboardData();
        }
    }

    private void saveCache(File cacheFile, DashboardData data) {
        try (FileWriter writer = new FileWriter(cacheFile)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to save dashboard cache: " + e.getMessage());
        }
    }
}
