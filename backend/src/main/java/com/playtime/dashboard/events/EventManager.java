package com.playtime.dashboard.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.scoreboard.number.*;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class EventManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static EventManager instance;
    private final File eventsFile;
    private MinecraftServer server;
    
    private ServerEvent activeEvent;
    private Map<String, Integer> allTimePoints = new HashMap<>();

    private EventManager() {
        this.eventsFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "dashboard_events.json");
        load();
    }

    public static EventManager getInstance() {
        if (instance == null) {
            instance = new EventManager();
        }
        return instance;
    }

    public void init(MinecraftServer server) {
        this.server = server;
        updateScoreboard();
    }

    public ServerEvent getActiveEvent() {
        return activeEvent;
    }

    public Map<String, Integer> getAllTimePoints() {
        return allTimePoints;
    }

    public Map<String, String> resolveNames(Set<String> uuids) {
        Map<String, String> resolved = new HashMap<>();
        for (String uuidStr : uuids) {
            resolved.put(uuidStr, getPlayerName(uuidStr));
        }
        return resolved;
    }

    public void startEvent(String title, String type, int durationHours) {
        if (activeEvent != null) {
            stopEvent();
        }

        long now = System.currentTimeMillis();
        activeEvent = new ServerEvent(
            UUID.randomUUID().toString(),
            title,
            type,
            now,
            now + (long) durationHours * 60 * 60 * 1000
        );

        // Take snapshot of initial stats for all known players
        takeStatsSnapshot();
        save();
        updateScoreboard();
        
        server.getPlayerManager().broadcast(Text.literal("§6[Event] §aNew event started: §l" + title), false);
        server.getPlayerManager().broadcast(Text.literal("§6[Event] §eGoal: " + type.replace("_", " ") + " for " + durationHours + " hours!"), false);
    }

    public void stopEvent() {
        if (activeEvent == null) return;

        updateScores(); // Final update
        distributePoints();
        
        server.getPlayerManager().broadcast(Text.literal("§6[Event] §cEvent ended: §l" + activeEvent.title), false);
        
        // Remove scoreboard objective
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective("event_lb");
        if (objective != null) {
            scoreboard.removeObjective(objective);
        }

        activeEvent = null;
        save();
    }

    private void takeStatsSnapshot() {
        Path statsDir = getStatsPath();
        
        // 1. Snapshot disk stats
        File[] files = statsDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File statFile : files) {
                String uuidStr = statFile.getName().replace(".json", "");
                int value = getStatValueFromDisk(statFile, activeEvent.type);
                activeEvent.initialStats.put(uuidStr, value);
            }
        }

        // 2. Overwrite with live stats for online players (more accurate)
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String uuidStr = player.getUuid().toString();
            activeEvent.initialStats.put(uuidStr, getStatValueFromPlayer(player, activeEvent.type));
        }
    }

    private Path getStatsPath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve(DashboardConfig.get().stats_world_name)
                .resolve("stats");
    }

    public void tick() {
        if (activeEvent == null) return;

        if (System.currentTimeMillis() > activeEvent.endTime) {
            stopEvent();
            return;
        }

        updateScores();
    }

    private void updateScores() {
        if (activeEvent == null) return;

        // 1. Update from disk (Offline players and baseline)
        Path statsDir = getStatsPath();
        File[] files = statsDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File statFile : files) {
                String uuidStr = statFile.getName().replace(".json", "");
                int currentValue = getStatValueFromDisk(statFile, activeEvent.type);
                updatePlayerScore(uuidStr, currentValue);
            }
        }

        // 2. Update from online players (Override disk with live data)
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String uuidStr = player.getUuid().toString();
            int currentValue = getStatValueFromPlayer(player, activeEvent.type);
            updatePlayerScore(uuidStr, currentValue);
        }
        
        updateScoreboard();
    }

    private void updatePlayerScore(String uuidStr, int currentValue) {
        if (!activeEvent.initialStats.containsKey(uuidStr)) {
            activeEvent.initialStats.put(uuidStr, currentValue);
        }
        
        int initialValue = activeEvent.initialStats.get(uuidStr);
        int score = currentValue - initialValue;
        
        // Convert playtime ticks to seconds for better readability
        if (activeEvent.type.equals("playtime")) {
            score = score / 20;
        }
        
        if (score > 0) {
            activeEvent.currentScores.put(uuidStr, score);
        }
    }

    private int getStatValueFromPlayer(ServerPlayerEntity player, String type) {
        switch (type) {
            case "playtime":
                // Try both play_time and play_one_minute identifiers
                int pt = player.getStatHandler().getStat(Stats.CUSTOM, Registries.CUSTOM_STAT.get(Identifier.of("minecraft", "play_time")));
                if (pt == 0) pt = player.getStatHandler().getStat(Stats.CUSTOM, Registries.CUSTOM_STAT.get(Identifier.of("minecraft", "play_one_minute")));
                return pt;
            case "mob_kills":
                return player.getStatHandler().getStat(Stats.CUSTOM, Registries.CUSTOM_STAT.get(Identifier.of("minecraft", "mob_kills")));
            case "blocks_placed":
                return sumCategoryFromPlayer(player, Stats.USED, Registries.ITEM, true);
            case "blocks_mined":
                return sumCategoryFromPlayer(player, Stats.MINED, Registries.BLOCK, false);
            default:
                return 0;
        }
    }

    private <T> int sumCategoryFromPlayer(ServerPlayerEntity player, StatType<T> category, net.minecraft.registry.Registry<T> registry, boolean checkBlocks) {
        int sum = 0;
        for (T entry : registry) {
            int value = player.getStatHandler().getStat(category, entry);
            if (value > 0) {
                if (checkBlocks) {
                    if (isBlockHeuristic(registry.getId(entry).toString())) {
                        sum += value;
                    }
                } else {
                    sum += value;
                }
            }
        }
        return sum;
    }

    private int getStatValueFromDisk(File statFile, String type) {
        try (FileReader reader = new FileReader(statFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("stats")) return 0;
            JsonObject stats = root.getAsJsonObject("stats");

            switch (type) {
                case "playtime":
                    int pt = getNestedInt(stats, "minecraft:custom", "minecraft:play_time");
                    if (pt == 0) pt = getNestedInt(stats, "minecraft:custom", "minecraft:play_one_minute");
                    return pt;
                case "mob_kills":
                    return getNestedInt(stats, "minecraft:custom", "minecraft:mob_kills");
                case "blocks_placed":
                    return sumCategory(stats, "minecraft:used", true); // heuristic: used blocks
                case "blocks_mined":
                    return sumCategory(stats, "minecraft:mined", false);
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private int getNestedInt(JsonObject stats, String category, String stat) {
        if (stats.has(category)) {
            JsonObject catObj = stats.getAsJsonObject(category);
            if (catObj.has(stat)) {
                return catObj.get(stat).getAsInt();
            }
        }
        return 0;
    }

    private int sumCategory(JsonObject stats, String category, boolean checkBlocks) {
        if (!stats.has(category)) return 0;
        JsonObject catObj = stats.getAsJsonObject(category);
        int sum = 0;
        for (String key : catObj.keySet()) {
            // Basic heuristic for blocks: doesn't end with common item suffixes
            if (checkBlocks) {
                if (isBlockHeuristic(key)) sum += catObj.get(key).getAsInt();
            } else {
                sum += catObj.get(key).getAsInt();
            }
        }
        return sum;
    }

    private boolean isBlockHeuristic(String key) {
        String k = key.replace("minecraft:", "");
        return !k.endsWith("_sword") && !k.endsWith("_pickaxe") && !k.endsWith("_axe") && 
               !k.endsWith("_shovel") && !k.endsWith("_hoe") && !k.endsWith("_helmet") &&
               !k.endsWith("_chestplate") && !k.endsWith("_leggings") && !k.endsWith("_boots") &&
               !k.equals("apple") && !k.equals("bread") && !k.equals("stick");
    }

    private void updateScoreboard() {
        if (server == null || activeEvent == null) return;

        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective("event_lb");
        
        if (objective == null) {
            objective = scoreboard.addObjective("event_lb", ScoreboardCriterion.DUMMY, 
                Text.literal("§6§l" + activeEvent.title), ScoreboardCriterion.RenderType.INTEGER, false, null);
            scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);
        }

        final ScoreboardObjective finalObjective = objective;
        activeEvent.currentScores.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(15)
            .forEach(entry -> {
                String uuidStr = entry.getKey();
                int val = entry.getValue();
                String name = getPlayerName(uuidStr);
                
                ScoreHolder holder = ScoreHolder.fromName(name);
                var score = scoreboard.getOrCreateScore(holder, finalObjective);
                
                if (activeEvent.type.equals("playtime")) {
                    String formattedTime = formatTime(val);
                    score.setDisplayText(Text.literal("§f" + name + " §e" + formattedTime));
                    // Try to hide the score number if the class exists, otherwise it just shows alongside
                    try {
                        score.setNumberFormat(BlankNumberFormat.INSTANCE);
                    } catch (Throwable ignored) {}
                } else {
                    score.setDisplayText(null); // Reset to default
                    score.setNumberFormat(null);
                }
                
                score.setScore(val);
            });
    }

    private String formatTime(int seconds) {
        int d = seconds / 86400;
        int h = (seconds % 86400) / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0 || d > 0) sb.append(h).append("h ");
        if (m > 0 || h > 0 || d > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString();
    }

    public void clearAllPoints(int amount) {
        if (amount <= 0) {
            allTimePoints.clear();
        } else {
            allTimePoints.replaceAll((uuid, points) -> Math.max(0, points - amount));
        }
        save();
    }

    public void clearUserPoints(String playerOrUuid, int amount) {
        String uuidStr = playerOrUuid;
        // Try to find UUID if it's a name
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(playerOrUuid)) {
                uuidStr = player.getUuid().toString();
                break;
            }
        }
        
        if (amount <= 0) {
            allTimePoints.remove(uuidStr);
        } else {
            allTimePoints.computeIfPresent(uuidStr, (k, v) -> Math.max(0, v - amount));
        }
        save();
    }

    private String getPlayerName(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) return player.getGameProfile().getName();
            
            // Fallback to usercache
            return server.getUserCache().getByUuid(uuid).map(p -> p.getName()).orElse(uuidStr);
        } catch (Exception e) {
            return uuidStr;
        }
    }

    private void distributePoints() {
        if (activeEvent == null) return;

        List<Map.Entry<String, Integer>> sorted = activeEvent.currentScores.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            String uuid = sorted.get(i).getKey();
            int points = 10; // Participation
            if (i == 0) points = 100;
            else if (i == 1) points = 50;
            else if (i == 2) points = 25;

            allTimePoints.merge(uuid, points, Integer::sum);
        }
        save();
    }

    private void load() {
        if (!eventsFile.exists()) return;
        try (FileReader reader = new FileReader(eventsFile)) {
            JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
            if (data.has("activeEvent")) {
                activeEvent = GSON.fromJson(data.get("activeEvent"), ServerEvent.class);
            }
            if (data.has("allTimePoints")) {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, Integer>>(){}.getType();
                allTimePoints = GSON.fromJson(data.get("allTimePoints"), type);
            }
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to load events data", e);
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(eventsFile)) {
            JsonObject data = new JsonObject();
            if (activeEvent != null) {
                data.add("activeEvent", GSON.toJsonTree(activeEvent));
            }
            data.add("allTimePoints", GSON.toJsonTree(allTimePoints));
            GSON.toJson(data, writer);
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to save events data", e);
        }
    }
}
