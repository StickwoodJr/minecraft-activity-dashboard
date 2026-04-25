package com.playtime.dashboard.stats;

import com.google.gson.stream.JsonReader;
import com.playtime.dashboard.FabricDashboardMod;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        "minecraft:cookie", "minecraft:pumpkin_pie", "minecraft:sweet_berries", "minecraft:glow_berries",
        "minecraft:melon_slice", "minecraft:carrot", "minecraft:potato", "minecraft:baked_potato", "minecraft:poisonous_potato",
        "minecraft:dried_kelp", "minecraft:honey_bottle", "minecraft:totem_of_undying", "minecraft:experience_bottle",
        "minecraft:ender_pearl", "minecraft:snowball", "minecraft:egg", "minecraft:firework_rocket", "minecraft:firework_star",
        "minecraft:lead", "minecraft:name_tag", "minecraft:bone_meal", "minecraft:ender_eye", "minecraft:ghast_tear"
    ));

    private boolean isBlockPlacement(String statId) {
        if (ITEM_NAMES.contains(statId)) return false;
        for (String suffix : ITEM_SUFFIXES) {
            if (statId.endsWith(suffix)) return false;
        }
        return true;
    }

    public Map<String, Map<String, Map<String, Integer>>> buildLeaderboards(Path statsDir, UuidCache uuidCache) {
        // Map<Category, Map<Stat, Map<PlayerName, Value>>>
        Map<String, Map<String, Map<String, Integer>>> result = new LinkedHashMap<>();

        File[] files = statsDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return result;

        for (File statFile : files) {
            String uuidStr = statFile.getName().replace(".json", "");
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue; // Not a valid UUID file name
            }

            String rawName = uuidCache.getUsername(uuid).orElse(uuidStr);
            String playerName = normalizePlayer(rawName, uuidStr);

            // Skip players that couldn't be resolved to a name (even after Mojang API check)
            // and are still just raw UUID strings, to prevent UI clutter.
            if (isUuidString(playerName)) {
                continue;
            }

            try (JsonReader reader = new JsonReader(new FileReader(statFile))) {
                parseStatsIntoMap(reader, playerName, result);
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.warn("Skipping malformed stats file: " + statFile.getName());
            }
        }
        return result;
    }

    private boolean isUuidString(String str) {
        if (str == null) return false;
        String s = str.trim();
        // Handle both dashed (36) and undashed (32) UUIDs
        if (s.length() != 36 && s.length() != 32) return false;
        // Match hex chars and optional dashes
        return s.matches("^[0-9a-fA-F-]+$");
    }

    private String normalizePlayer(String name, String uuidStr) {
        if (name == null) name = uuidStr;
        String n = name.trim();
        Map<String, String> aliases = com.playtime.dashboard.config.DashboardConfig.get().player_aliases;
        if (aliases != null) {
            if (aliases.containsKey(uuidStr)) return aliases.get(uuidStr).trim();
            if (aliases.containsKey(n)) return aliases.get(n).trim();
            if (aliases.containsKey(n.toLowerCase())) return aliases.get(n.toLowerCase()).trim();
        }
        if (n.equalsIgnoreCase("hanger") || n.equalsIgnoreCase("advent")) {
            return "Advent/Hanger";
        }
        return n;
    }

    private void parseStatsIntoMap(JsonReader reader, String playerName, Map<String, Map<String, Map<String, Integer>>> result) throws IOException {
        reader.beginObject(); // start main object
        long distanceCm = 0;
        int damageTaken = 0;
        int damageDealt = 0;
        int totalMined = 0;
        int totalUsed = 0;

        Map<String, Map<String, Integer>> filteredCategory = result.computeIfAbsent("general", k -> new LinkedHashMap<>());

        while (reader.hasNext()) {
            String rootKey = reader.nextName();
            if ("stats".equals(rootKey)) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String category = reader.nextName();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String stat = reader.nextName();
                        int value = reader.nextInt();

                        if ("minecraft:mined".equals(category)) {
                            totalMined += value;
                        } else if ("minecraft:used".equals(category) && isBlockPlacement(stat)) {
                            totalUsed += value;
                        }

                        if (stat.endsWith("_one_cm")) {
                            distanceCm += value;
                        } else if (stat.equals("minecraft:damage_taken")) {
                            damageTaken += value;
                        } else if (stat.equals("minecraft:damage_dealt")) {
                            damageDealt += value;
                        } else if (stat.equals("minecraft:player_kills") || 
                                   stat.equals("minecraft:mob_kills") || 
                                   stat.equals("minecraft:deaths") || 
                                   stat.equals("minecraft:sleep_in_bed") || 
                                   stat.equals("minecraft:talked_to_villager") || 
                                   stat.equals("minecraft:totem_of_undying") || 
                                   stat.equals("minecraft:silverfish") || 
                                   stat.equals("minecraft:wither") || 
                                   stat.equals("minecraft:ender_dragon") || 
                                   stat.contains("dragon_fish")) {
                            
                            // normalize stat names a bit to make frontend easier
                            String key = stat.replace("minecraft:", "").replace("tide:", "");
                            if (key.contains("dragon_fish")) {
                                key = "dragon_fish";
                            }
                            Map<String, Integer> statMap = filteredCategory.computeIfAbsent(key, k -> new LinkedHashMap<>());
                            statMap.merge(playerName, value, Integer::sum);
                        }
                    }
                    reader.endObject();
                }
                reader.endObject();
            } else {
                reader.skipValue(); // e.g. DataVersion
            }
        }
        reader.endObject();

        if (distanceCm > 0) {
            filteredCategory.computeIfAbsent("distance_traveled_km", k -> new LinkedHashMap<>())
                    .merge(playerName, (int)(distanceCm / 100000L), Integer::sum);
        }
        if (damageTaken > 0) {
            filteredCategory.computeIfAbsent("damage_taken_hearts", k -> new LinkedHashMap<>())
                    .merge(playerName, damageTaken / 10, Integer::sum);
        }
        if (damageDealt > 0) {
            filteredCategory.computeIfAbsent("damage_dealt_hearts", k -> new LinkedHashMap<>())
                    .merge(playerName, damageDealt / 10, Integer::sum);
        }
        if (totalMined > 0) {
            filteredCategory.computeIfAbsent("total_blocks_broken", k -> new LinkedHashMap<>())
                    .merge(playerName, totalMined, Integer::sum);
        }
        if (totalUsed > 0) {
            filteredCategory.computeIfAbsent("total_blocks_placed", k -> new LinkedHashMap<>())
                    .merge(playerName, totalUsed, Integer::sum);
        }
    }

    public void streamPlayerStats(String username, UuidCache uuidCache, Path statsDir, OutputStream out) throws IOException {
        Optional<UUID> uuid = uuidCache.getUuid(username);
        
        if (uuid.isEmpty()) {
            // First check if the username itself is just a raw UUID string
            try {
                uuid = Optional.of(UUID.fromString(username));
                if (!Files.exists(statsDir.resolve(uuid.get() + ".json"))) {
                    uuid = Optional.empty(); // Not a valid stat file, fallback to alias search
                }
            } catch (IllegalArgumentException e) {
                // Not a UUID string, proceed with alias search
            }
        }
        
        if (uuid.isEmpty()) {
            // Check if username matches an alias reverse lookup or hardcoded Advent/Hanger
            if (username.equalsIgnoreCase("Advent/Hanger")) {
                uuid = uuidCache.getUuid("hanger");
                if (uuid.isEmpty()) uuid = uuidCache.getUuid("advent");
            } else {
                Map<String, String> aliases = com.playtime.dashboard.config.DashboardConfig.get().player_aliases;
                if (aliases != null) {
                    for (Map.Entry<String, String> entry : aliases.entrySet()) {
                        if (entry.getValue().equalsIgnoreCase(username)) {
                            // The key is either a UUID or a name
                            try {
                                uuid = Optional.of(UUID.fromString(entry.getKey()));
                            } catch (IllegalArgumentException e) {
                                uuid = uuidCache.getUuid(entry.getKey());
                            }
                            if (uuid.isPresent() && Files.exists(statsDir.resolve(uuid.get() + ".json"))) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        
        if (uuid.isEmpty()) {
            throw new FileNotFoundException("Unknown player: " + username);
        }

        Path statFile = statsDir.resolve(uuid.get() + ".json");
        if (!Files.exists(statFile)) {
            throw new FileNotFoundException("No stats for: " + username);
        }

        // Act as a pure pipe — never parse the JSON
        try (InputStream in = Files.newInputStream(statFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
