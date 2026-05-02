package com.playtime.dashboard.events;

import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StreakTracker {
    private static final StreakTracker INSTANCE = new StreakTracker();
    private final File dataFile;
    private final Map<String, PlayerStreakData> streaks = new ConcurrentHashMap<>();

    private StreakTracker() {
        this.dataFile = new File(FabricLoader.getInstance().getGameDir().toFile(), \"dashboard_streaks.json\");
        load();
    }

    public static StreakTracker getInstance() {
        return INSTANCE;
    }

    public void onPlayerActivity(String playerName, int minutesPlayed) {
        String normalized = DashboardConfig.get().getNormalizedName(playerName);
        PlayerStreakData data = streaks.computeIfAbsent(normalized, k -> new PlayerStreakData());
        
        DashboardConfig config = DashboardConfig.get();
        ZoneId zone = ZoneId.of(config.streak_timezone);
        LocalDate today = LocalDate.now(zone);

        data.dailyMinutes.merge(today.toString(), minutesPlayed, Integer::sum);
        
        if (data.dailyMinutes.get(today.toString()) >= config.streak_minimum_minutes_per_day) {
            updateStreak(normalized, data, today);
        }
        save();
    }

    private void updateStreak(String name, PlayerStreakData data, LocalDate today) {
        if (today.toString().equals(data.lastActiveDate)) return;

        LocalDate yesterday = today.minusDays(1);
        if (yesterday.toString().equals(data.lastActiveDate)) {
            data.streakCount++;
        } else {
            data.streakCount = 1;
        }
        data.lastActiveDate = today.toString();
        FabricDashboardMod.LOGGER.info(\"[Streak] {} is on a {} day streak!\", name, data.streakCount);
    }

    public int getStreak(String playerName) {
        String normalized = DashboardConfig.get().getNormalizedName(playerName);
        PlayerStreakData data = streaks.get(normalized);
        if (data == null) return 0;
        
        DashboardConfig config = DashboardConfig.get();
        ZoneId zone = ZoneId.of(config.streak_timezone);
        LocalDate today = LocalDate.now(zone);
        LocalDate yesterday = today.minusDays(1);

        if (!today.toString().equals(data.lastActiveDate) && !yesterday.toString().equals(data.lastActiveDate)) {
            data.streakCount = 0;
        }
        return data.streakCount;
    }

    public Map<String, PlayerStreakData> getAllStreaks() {
        return Collections.unmodifiableMap(streaks);
    }

    private void load() {
        if (!dataFile.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
            com.google.gson.reflect.TypeToken<Map<String, PlayerStreakData>> typeToken = new com.google.gson.reflect.TypeToken<>() {};
            Map<String, PlayerStreakData> loaded = new com.google.gson.Gson().fromJson(reader, typeToken.getType());
            if (loaded != null) streaks.putAll(loaded);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error(\"Failed to load streak data\", e);
        }
    }

    private void save() {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(dataFile), StandardCharsets.UTF_8)) {
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(streaks, writer);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error(\"Failed to save streak data\", e);
        }
    }

    public static class PlayerStreakData {
        public int streakCount = 0;
        public String lastActiveDate = \"\";
        public Map<String, Integer> dailyMinutes = new HashMap<>();
    }
}
