package com.playtime.dashboard.parser;

import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.events.StreakTracker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {
    private static final Pattern LOG_LINE = Pattern.compile(\"\\\\[(\\\\d{2}:\\\\d{2}:\\\\d{2})\\\\] \\\\[[^/]+/(INFO|WARN|ERROR)\\\\]: (.*)\");
    private static final Pattern JOIN_PATTERN = Pattern.compile(\"(\\\\w+) joined the game\");
    private static final Pattern LEAVE_PATTERN = Pattern.compile(\"(\\\\w+) left the game\");
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat(\"HH:mm:ss\");

    private final Map<String, Long> activeSessions = new HashMap<>();
    private final Map<String, Integer> totalPlaytime = new HashMap<>();
    private final Map<String, Map<String, Integer>> dailyPlaytime = new HashMap<>();
    private final Map<String, Map<String, int[]>> hourlyPlaytime = new HashMap<>(); // Date -> Player -> int[24]
    private final Map<String, SessionStats> playerSessionStats = new HashMap<>();

    private long lastSeenTimestamp = -1;
    private String currentDateStr;

    public void parseLogs(Path logsDir) {
        File folder = logsDir.toFile();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(\".log\") || name.endsWith(\".log.gz\"));
        if (files == null) return;

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            if (file.getName().endsWith(\".gz\")) continue; // Skip compressed for now
            parseFile(file);
        }
        
        closeGhostSessions();
    }

    private void parseFile(File file) {
        currentDateStr = extractDate(file.getName());
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processLine(line);
            }
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error(\"Error reading log file: \" + file.getName(), e);
        }
    }

    private String extractDate(String filename) {
        if (filename.equals(\"latest.log\")) {
            return new SimpleDateFormat(\"yyyy-MM-dd\").format(new Date());
        }
        // Assuming yyyy-MM-dd-N.log
        if (filename.length() >= 10) {
            return filename.substring(0, 10);
        }
        return \"unknown\";
    }

    private void processLine(String line) {
        Matcher m = LOG_LINE.matcher(line);
        if (!m.matches()) return;

        String timeStr = m.group(1);
        String message = m.group(3);

        try {
            long timestamp = TIME_FMT.parse(timeStr).getTime();
            lastSeenTimestamp = timestamp;

            Matcher joinMatch = JOIN_PATTERN.matcher(message);
            if (joinMatch.matches()) {
                handleJoin(joinMatch.group(1), timestamp);
            }

            Matcher leaveMatch = LEAVE_PATTERN.matcher(message);
            if (leaveMatch.matches()) {
                handleLeave(leaveMatch.group(1), timestamp);
            }
        } catch (ParseException e) {
            // Ignore
        }
    }

    private void handleJoin(String player, long timestamp) {
        String normalized = normalizePlayer(player);
        if (DashboardConfig.get().isPlayerIgnored(normalized)) return;
        activeSessions.put(normalized, timestamp);
    }

    private void handleLeave(String player, long timestamp) {
        String normalized = normalizePlayer(player);
        if (DashboardConfig.get().isPlayerIgnored(normalized)) return;
        
        Long joinTime = activeSessions.remove(normalized);
        if (joinTime != null) {
            int duration = (int) ((timestamp - joinTime) / 60000);
            if (duration > 0) {
                addPlaytime(normalized, duration, joinTime);
                updateSessionStats(normalized, duration, currentDateStr);
                
                // Track streaks during log parsing (historic)
                StreakTracker.getInstance().onPlayerActivity(normalized, duration);
            }
        }
    }

    private void closeGhostSessions() {
        if (lastSeenTimestamp == -1) return;
        
        for (Map.Entry<String, Long> entry : activeSessions.entrySet()) {
            String player = entry.getKey();
            long joinTime = entry.getValue();
            
            int duration = (int) ((lastSeenTimestamp - joinTime) / 60000);
            if (duration > 0) {
                addPlaytime(player, duration, joinTime);
                updateSessionStats(player, duration, currentDateStr);
                StreakTracker.getInstance().onPlayerActivity(player, duration);
            }
        }
        activeSessions.clear();
    }

    private void addPlaytime(String player, int minutes, long startTime) {
        totalPlaytime.merge(player, minutes, Integer::sum);
        
        Map<String, Integer> dayMap = dailyPlaytime.computeIfAbsent(currentDateStr, k -> new HashMap<>());
        dayMap.merge(player, minutes, Integer::sum);

        // Hourly tracking
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(startTime);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        
        Map<String, int[]> hourMap = hourlyPlaytime.computeIfAbsent(currentDateStr, k -> new HashMap<>());
        int[] hours = hourMap.computeIfAbsent(player, k -> new int[24]);
        
        // Distribute minutes across hours if session spans multiple
        int remaining = minutes;
        int currentHour = hour;
        while (remaining > 0) {
            int minsInThisHour = Math.min(remaining, 60 - cal.get(Calendar.MINUTE));
            hours[currentHour] = Math.min(60, hours[currentHour] + minsInThisHour);
            remaining -= minsInThisHour;
            currentHour = (currentHour + 1) % 24;
            cal.set(Calendar.MINUTE, 0); // Reset for next hour
        }
    }

    private void updateSessionStats(String player, int duration, String date) {
        SessionStats stats = playerSessionStats.computeIfAbsent(player, k -> new SessionStats());
        stats.sessions++;
        stats.totalMinutes += duration;
        stats.avg = stats.totalMinutes / (double) stats.sessions;
        if (duration > stats.longestSession) {
            stats.longestSession = duration;
            stats.longestSessionDate = date;
        }
    }

    private String normalizePlayer(String player) {
        return DashboardConfig.get().getNormalizedName(player);
    }

    // Getters for aggregator
    public Map<String, Integer> getTotalPlaytime() { return totalPlaytime; }
    public Map<String, Map<String, Integer>> getDailyPlaytime() { return dailyPlaytime; }
    public Map<String, Map<String, int[]>> getHourlyPlaytime() { return hourlyPlaytime; }
    public Map<String, SessionStats> getPlayerSessionStats() { return playerSessionStats; }

    public static class SessionStats {
        public int sessions = 0;
        public int totalMinutes = 0;
        public double avg = 0;
        public int longestSession = 0;
        public String longestSessionDate = \"\";
    }
}
