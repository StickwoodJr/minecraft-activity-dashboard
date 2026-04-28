package com.playtime.dashboard.parser;

import com.google.gson.Gson;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharsetDecoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class LogParser {
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LOG_PATTERN = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})\\]");
    private static final Pattern JOIN_PATTERN = Pattern.compile(": ([\\w]+) joined the game");
    private static final Pattern LEAVE_PATTERN = Pattern.compile(": ([\\w]+) left the game");
    private static final Pattern START_PATTERN = Pattern.compile("Starting minecraft server version");
    private static final Pattern STOP_PATTERN = Pattern.compile("Stopping server");
    private static final Pattern BOOT_PATTERN = Pattern.compile("Loading Minecraft|Environment:|Starting minecraft server version");

    private static final Gson GSON = new Gson();

    private long lastByteOffset = 0;
    private final Map<String, LocalDateTime> activeSessions = new HashMap<>();
    private LocalDateTime incrementalLastTs = null;

    public void runHistoricalParse(File logsDir, File cacheFile) {
        FabricDashboardMod.LOGGER.info("Starting historical log parse from directory: " + logsDir.getAbsolutePath());
        DashboardData data = new DashboardData();
        
        if (!logsDir.exists() || !logsDir.isDirectory()) {
            FabricDashboardMod.LOGGER.error("Logs directory does not exist or is not a directory: " + logsDir.getAbsolutePath());
            return;
        }

        File[] files = logsDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz"));
        if (files == null) {
            FabricDashboardMod.LOGGER.error("Failed to list files in logs directory.");
            return;
        }
        
        List<File> fileList = new ArrayList<>(Arrays.asList(files));
        fileList.sort((f1, f2) -> {
            String n1 = f1.getName();
            String n2 = f2.getName();
            if (n1.equals("latest.log")) return 1;
            if (n2.equals("latest.log")) return -1;
            
            String[] p1 = n1.replace(".log.gz", "").replace(".log", "").split("-");
            String[] p2 = n2.replace(".log.gz", "").replace(".log", "").split("-");
            
            for (int i = 0; i < Math.min(p1.length, p2.length); i++) {
                try {
                    int i1 = Integer.parseInt(p1[i]);
                    int i2 = Integer.parseInt(p2[i]);
                    if (i1 != i2) return Integer.compare(i1, i2);
                } catch (NumberFormatException e) {
                    int cmp = p1[i].compareTo(p2[i]);
                    if (cmp != 0) return cmp;
                }
            }
            return Integer.compare(p1.length, p2.length);
        });
        
        FabricDashboardMod.LOGGER.info("Found " + fileList.size() + " log files to parse.");
        int parsedCount = 0;
        int skippedCount = 0;
        
        Map<String, LocalDateTime> globalSessions = new HashMap<>();
        LocalDateTime globalLastTimestamp = null;
        
        for (File file : fileList) {
            String dateStr = extractDate(file.getName());
            if (dateStr == null) {
                if (file.getName().equals("latest.log")) {
                    dateStr = java.time.Instant.ofEpochMilli(file.lastModified())
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .toString();
                } else {
                    skippedCount++;
                    continue;
                }
            }
            
            

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
                    
            try (InputStream is = file.getName().endsWith(".gz") 
                    ? new GZIPInputStream(new FileInputStream(file)) 
                    : new FileInputStream(file);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, decoder))) {
                 
                String line;
                int joins = 0;
                int leaves = 0;
                try {
                    while ((line = reader.readLine()) != null) {
                        LocalDateTime newTs = processLine(line, dateStr, globalSessions, data, globalLastTimestamp);
                        if (newTs != globalLastTimestamp) {
                            if (line.contains("joined the game")) joins++;
                            if (line.contains("left the game")) leaves++;
                            globalLastTimestamp = newTs;
                        }
                    }
                } catch (IOException eof) {
                    // Graceful exit for corrupted files
                }
                
                parsedCount++;
                FabricDashboardMod.LOGGER.info("Parsed " + file.getName() + " [Joins: " + joins + ", Leaves: " + leaves + "]");
                
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to parse file: " + file.getName(), e);
            }
        }
        
        if (globalLastTimestamp != null) {
            for (Map.Entry<String, LocalDateTime> entry : globalSessions.entrySet()) {
                closeSession(entry.getKey(), entry.getValue(), globalLastTimestamp, data);
            }
        }
        
        saveCache(cacheFile, data);
        FabricDashboardMod.LOGGER.info("Historical parse complete. Parsed: " + parsedCount + ", Skipped: " + skippedCount + ". Total active days found: " + data.daily.size());
    }

    /** Cap chunk read per incremental pass to avoid spike heap on a multi-megabyte tail. */
    private static final long INCREMENTAL_MAX_READ_BYTES = 16L * 1024 * 1024;

    public void runIncrementalParse(File logsDir, File cacheFile) {
        File latestLog = new File(logsDir, "latest.log");
        if (!latestLog.exists()) return;

        String dateStr = java.time.Instant.ofEpochMilli(latestLog.lastModified())
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString();

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
        if (!isInterestingLine(line)) return lastTimestamp;
        Matcher timeMatch = LOG_PATTERN.matcher(line);
        if (!timeMatch.find()) return lastTimestamp;
        
        String timeStr = timeMatch.group(1);
        LocalDateTime currentTs;
        try {
            currentTs = LocalDateTime.parse(dateStr + " " + timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException e) {
            return lastTimestamp;
        }

        // Reset sessions on server lifecycle events
        if (BOOT_PATTERN.matcher(line).find() || STOP_PATTERN.matcher(line).find()) {
            if (data != null) {
                boolean isCrash = BOOT_PATTERN.matcher(line).find();
                // For clean stops: credit time up to currentTs (the stop event).
                // For crashes: cap ghost sessions at MAX_GHOST_SESSION_HOURS from join
                // to avoid inflating stats when players were idle/offline during a crash.
                final long MAX_GHOST_MINUTES = 4 * 60; // 4 hours
                for (Map.Entry<String, LocalDateTime> entry : sessions.entrySet()) {
                    LocalDateTime sessionStart = entry.getValue();
                    LocalDateTime endTs;
                    if (isCrash && sessionStart != null) {
                        LocalDateTime cappedEnd = sessionStart.plusMinutes(MAX_GHOST_MINUTES);
                        endTs = (currentTs != null && currentTs.isBefore(cappedEnd)) ? currentTs : cappedEnd;
                    } else {
                        endTs = currentTs != null ? currentTs : sessionStart;
                    }
                    FabricDashboardMod.LOGGER.info("FORCE CLOSING ghost session for " + entry.getKey() + ". Start: " + sessionStart + ", End: " + endTs + (isCrash ? " [crash-capped]" : ""));
                    closeSession(entry.getKey(), sessionStart, endTs, data);
                }
            }
            sessions.clear();
            return currentTs;
        }
        
        Matcher joinMatch = JOIN_PATTERN.matcher(line);
        if (joinMatch.find()) {
            String player = normalizePlayer(joinMatch.group(1));
            if (DashboardConfig.get().ignored_players.contains(player)) return currentTs;
            
            if (sessions.containsKey(player) && data != null) {
                closeSession(player, sessions.get(player), currentTs, data);
            }
            sessions.put(player, currentTs);
            return currentTs;
        }
        
        Matcher leaveMatch = LEAVE_PATTERN.matcher(line);
        if (leaveMatch.find()) {
            String player = normalizePlayer(leaveMatch.group(1));
            if (DashboardConfig.get().ignored_players.contains(player)) return currentTs;
            
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
        if (p.equalsIgnoreCase("hanger") || p.equalsIgnoreCase("advent")) {
            return "Advent/Hanger";
        }
        return p;
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
            FabricDashboardMod.LOGGER.error("Failed to load cache", e);
            return new DashboardData();
        }
    }

    private void saveCache(File cacheFile, DashboardData data) {
        for (Map.Entry<String, Double> e : data.daily.entrySet()) e.setValue(Math.round(e.getValue() * 100.0) / 100.0);
        for (Map<String, Double> map : data.playerDailyRaw.values()) {
            for (Map.Entry<String, Double> e : map.entrySet()) e.setValue(Math.round(e.getValue() * 10.0) / 10.0);
        }
        for (DashboardData.SessionData sd : data.sessData.values()) {
            sd.avg = Math.round(sd.avg * 10.0) / 10.0;
            sd.longestSession = Math.round(sd.longestSession * 10.0) / 10.0;
        }
        for (Map<String, double[]> map : data.hourly.values()) {
            for (double[] arr : map.values()) {
                for (int i=0; i<arr.length; i++) arr[i] = Math.round(arr[i] * 10.0) / 10.0;
            }
        }
        
        try (Writer writer = new FileWriter(cacheFile)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to save cache", e);
        }
    }
}
