package com.playtime.dashboard.stats;

import com.google.gson.stream.JsonReader;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.util.UuidCache;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class StatsAggregator {
    private static final Set<String> ITEM_SUFFIXES = new HashSet<>(Arrays.asList(
        "_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots",
        "_bucket", "_potion", "_stew", "_soup", "_bottle", "_pearl", "_egg", "_rod", "_shears", "_bow",
        "_crossbow", "_trident", "_shield", "_rod", "_flint_and_steel", "_spyglass", "_compass", "_clock"
    ));
    
    private static final Set<String> ITEM_NAMES = new HashSet<>(Arrays.asList(
        "minecraft:apple", "minecraft:bread", "minecraft:steak", "minecraft:cooked_porkchop", "minecraft:cooked_mutton",
        "minecraft:cooked_chicken", "minecraft:cooked_rabbit", "minecraft:cooked_cod", "minecraft:cooked_salmon",
        "minecraft:carrot", "minecraft:potato", "minecraft:baked_potato", "minecraft:poisonous_potato", "minecraft:golden_apple",
        "minecraft:enchanted_golden_apple", "minecraft:mushroom_stew", "minecraft:suspicious_stew", "minecraft:cookie",
        "minecraft:pumpkin_pie", "minecraft:sugar", "minecraft:cake", "minecraft:milk_bucket", "minecraft:honey_bottle",
        "minecraft:egg", "minecraft:wheat", "minecraft:pumpkin", "minecraft:melon_slice", "minecraft:sweet_berries",
        "minecraft:glow_berries", "minecraft:chorus_fruit", "minecraft:popped_chorus_fruit", "minecraft:rotten_flesh",
        "minecraft:spider_eye", "minecraft:fermented_spider_eye", "minecraft:poisonous_potato", "minecraft:pufferfish"
    ));

    public Map<String, Double> getGlobalStats(Path statsDir, String statType, UuidCache uuidCache) {
        Map<String, Double> results = new HashMap<>();
        File[] files = statsDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return results;

        for (File f : files) {
            String uuid = f.getName().replace(".json", "");
            if (DashboardConfig.get().isPlayerIgnored(uuid)) continue;

            Optional<String> name = uuidCache.getUsername(UUID.fromString(uuid));
            if (name.isEmpty()) continue;
            
            String displayName = DashboardConfig.get().getNormalizedName(name.get());
            if (DashboardConfig.get().isPlayerIgnored(displayName)) continue;

            try (JsonReader reader = new JsonReader(new FileReader(f))) {
                double val = parseStatValue(reader, statType);
                if (val > 0) {
                    results.merge(displayName, val, Double::sum);
                }
            } catch (IOException e) {
                FabricDashboardMod.LOGGER.error("Failed to parse " + f.getName(), e);
            }
        }
        return results;
    }

    public void streamPlayerStats(String username, UuidCache uuidCache, Path statsDir, OutputStream out) throws IOException {
        streamFile(username, uuidCache, statsDir, out, "stats");
    }

    public void streamPlayerAdvancements(String username, UuidCache uuidCache, Path advancementsDir, OutputStream out) throws IOException {
        streamFile(username, uuidCache, advancementsDir, out, "advancements");
    }

    private void streamFile(String username, UuidCache uuidCache, Path dir, OutputStream out, String type) throws IOException {
        Optional<UUID> uuid = DashboardConfig.get().resolvePrimaryUuid(username);

        if (uuid.isEmpty()) {
            throw new FileNotFoundException("Unknown player: " + username);
        }

        Path file = dir.resolve(uuid.get() + ".json");
        if (!Files.exists(file)) {
            throw new FileNotFoundException(type + " file not found for player: " + username);
        }
        Files.copy(file, out);
    }

    private double parseStatValue(JsonReader reader, String target) throws IOException {
        String[] parts = target.split(":");
        if (parts.length < 2) return 0;
        
        String category = "minecraft:" + parts[0];
        String stat = "minecraft:" + parts[1];

        reader.beginObject();
        while (reader.hasNext()) {
            if (reader.nextName().equals("stats")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if (reader.nextName().equals(category)) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            if (reader.nextName().equals(stat)) {
                                double val = reader.nextDouble();
                                reader.endObject();
                                reader.endObject();
                                reader.endObject();
                                return val;
                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endObject();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return 0;
    }
}
