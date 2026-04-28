package com.playtime.dashboard.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.parser.DashboardData;
import com.playtime.dashboard.util.UuidCache;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.File;
import java.io.FileReader;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class StreakTracker {
    private static final Gson GSON = new GsonBuilder().create();
    private static StreakTracker instance;
    private final File cacheFile;

    private static final class Snapshot {
        final Map<String, PlayerStreakData> streaks;
        final long fileMtime;
        final LocalDate today;
        final long computedNanos;

        Snapshot(Map<String, PlayerStreakData> streaks, long fileMtime, LocalDate today, long computedNanos) {
            this.streaks = streaks;
            this.fileMtime = fileMtime;
            this.today = today;
            this.computedNanos = computedNanos;
        }
    }

    private volatile Snapshot cachedSnapshot;

    private StreakTracker() {
        this.cacheFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "dashboard_cache.json");
    }

    public static StreakTracker getInstance() {
        if (instance == null) {
            instance = new StreakTracker();
        }
        return instance;
    }

    public Map<String, PlayerStreakData> getStreaks() {
        ZoneId zone = ZoneId.of(DashboardConfig.get().streak_timezone);
        LocalDate today = LocalDate.now(zone);
        long fileMtime = cacheFile.exists() ? cacheFile.lastModified() : 0L;
        long ttlNanos = Math.max(1, DashboardConfig.get().incremental_update_interval_minutes) * 60L * 1_000_000_000L;
        long now = System.nanoTime();

        Snapshot snap = cachedSnapshot;
        if (snap != null
                && snap.fileMtime == fileMtime
                && snap.today.equals(today)
                && (now - snap.computedNanos) < ttlNanos) {
            return snap.streaks;
        }

        Map<String, PlayerStreakData> built = computeStreaks(today);
        cachedSnapshot = new Snapshot(built, fileMtime, today, System.nanoTime());
        return built;
    }

    private Map<String, PlayerStreakData> computeStreaks(LocalDate today) {
        DashboardData data = loadCache();
        LocalDate yesterday = today.minusDays(1);

        Map<String, Map<LocalDate, Double>> playerDailyMinutes = new HashMap<>();
        if (data.playerDailyRaw != null) {
            for (Map.Entry<String, Map<String, Double>> dayEntry : data.playerDailyRaw.entrySet()) {
                LocalDate day;
                try {
                    day = LocalDate.parse(dayEntry.getKey());
                } catch (Exception ignored) {
                    continue;
                }
                if (dayEntry.getValue() == null) continue;
                for (Map.Entry<String, Double> playerEntry : dayEntry.getValue().entrySet()) {
                    String player = playerEntry.getKey();
                    double mins = playerEntry.getValue() == null ? 0.0 : playerEntry.getValue();
                    playerDailyMinutes.computeIfAbsent(player, k -> new HashMap<>()).merge(day, mins, Double::sum);
                }
            }
        }

        Map<String, PlayerStreakData> streaks = new HashMap<>();
        UuidCache.getInstance().refresh();
        for (Map.Entry<String, Map<LocalDate, Double>> playerEntry : playerDailyMinutes.entrySet()) {
            String player = playerEntry.getKey();
            Map<LocalDate, Double> perDay = playerEntry.getValue();

            Set<LocalDate> qualifyingDays = new HashSet<>();
            for (Map.Entry<LocalDate, Double> dayEntry : perDay.entrySet()) {
                if (dayEntry.getValue() != null && dayEntry.getValue() >= 60.0) {
                    qualifyingDays.add(dayEntry.getKey());
                }
            }

            int streakCount = computeCurrentStreak(qualifyingDays, today, yesterday);
            int minutesToday = (int) Math.floor(perDay.getOrDefault(today, 0.0));
            String lastUpdateDay = perDay.isEmpty()
                ? today.toString()
                : perDay.keySet().stream().max(LocalDate::compareTo).map(LocalDate::toString).orElse(today.toString());

            PlayerStreakData streakData = new PlayerStreakData();
            streakData.streakCount = streakCount;
            streakData.minutesToday = minutesToday;
            streakData.lastUpdateDay = lastUpdateDay;

            streaks.put(player, streakData);
            Optional<java.util.UUID> maybeUuid = UuidCache.getInstance().getUuid(player);
            maybeUuid.ifPresent(uuid -> streaks.put(uuid.toString(), streakData));
        }
        return streaks;
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        // Streaks are now log-derived from dashboard_cache.json.
    }

    public void onPlayerLeave(ServerPlayerEntity player) {
        // Streaks are now log-derived from dashboard_cache.json.
    }

    public void tick(MinecraftServer server) {
        // Streaks are now log-derived from dashboard_cache.json.
    }

    public int getStreak(String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank()) return 0;
        Map<String, PlayerStreakData> streaks = getStreaks();

        PlayerStreakData direct = streaks.get(uuidStr);
        if (direct != null) return direct.streakCount;

        try {
            java.util.UUID uuid = java.util.UUID.fromString(uuidStr);
            Optional<String> maybeName = UuidCache.getInstance().getUsername(uuid);
            if (maybeName.isPresent()) {
                PlayerStreakData byName = streaks.get(maybeName.get());
                if (byName != null) return byName.streakCount;
            }
        } catch (Exception ignored) {
            // Input wasn't a UUID, fall through to case-insensitive name matching.
        }

        for (Map.Entry<String, PlayerStreakData> entry : streaks.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(uuidStr)) {
                return entry.getValue().streakCount;
            }
        }
        return 0;
    }

    private int computeCurrentStreak(Set<LocalDate> qualifyingDays, LocalDate today, LocalDate yesterday) {
        LocalDate anchor = qualifyingDays.contains(today) ? today : (qualifyingDays.contains(yesterday) ? yesterday : null);
        if (anchor == null) return 0;

        int streak = 0;
        LocalDate cursor = anchor;
        while (qualifyingDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private DashboardData loadCache() {
        if (!cacheFile.exists()) return new DashboardData();
        try (FileReader reader = new FileReader(cacheFile)) {
            DashboardData data = GSON.fromJson(reader, DashboardData.class);
            return data != null ? data : new DashboardData();
        } catch (Exception ignored) {
            return new DashboardData();
        }
    }

    public static class PlayerStreakData {
        public int streakCount = 0;
        public int minutesToday = 0;
        public String lastUpdateDay; // YYYY-MM-DD
    }
}
