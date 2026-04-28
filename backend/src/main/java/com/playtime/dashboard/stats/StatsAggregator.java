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
        Map<String, Map<String, Map<String, Integer>>> result = new HashMap<>();
        Map<String, String> playerToUuid = new HashMap<>();

        File[] files = statsDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return result;

        for (File statFile : files) {
            String uuidStr = statFile.getName().replace(".json", "");
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue;
            }

            String rawName = uuidCache.getUsername(uuid).orElse(uuidStr);
            String playerName = normalizePlayer(rawName, uuidStr);
            
            if (isUuidString(playerName)) continue;

            playerToUuid.putIfAbsent(playerName, uuidStr);

            try (JsonReader reader = new JsonReader(new FileReader(statFile))) {
                parseStatsIntoMap(reader, playerName, result);
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.warn("Skipping malformed stats file: " + statFile.getName());
            }
        }

        // --- Post-process Playstyle Leaderboards ---
        calculateAndInjectPlaystyleScores(result, playerToUuid, statsDir.getParent());

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
        reader.beginObject();
        long distanceCm = 0;
        int damageTaken = 0;
        int damageDealt = 0;
        int totalMined = 0;
        int totalUsed = 0;
        int redstoneUsed = 0;
        int mobKills = 0;

        Map<String, Map<String, Integer>> filteredCategory = result.computeIfAbsent("general", k -> new HashMap<>());

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
                        } else if ("minecraft:used".equals(category)) {
                            if (isBlockPlacement(stat)) totalUsed += value;
                            if (isRedstone(stat)) redstoneUsed += value;
                        }

                        if (stat.endsWith("_one_cm")) {
                            distanceCm += value;
                        } else if (stat.equals("minecraft:damage_taken")) {
                            damageTaken += value;
                        } else if (stat.equals("minecraft:damage_dealt")) {
                            damageDealt += value;
                        } else if (stat.equals("minecraft:mob_kills")) {
                            mobKills += value;
                            addGeneralStat(filteredCategory, playerName, "mob_kills", value);
                        } else if (stat.equals("minecraft:player_kills") || 
                                   stat.equals("minecraft:deaths") || 
                                   stat.equals("minecraft:sleep_in_bed") || 
                                   stat.equals("minecraft:talked_to_villager") || 
                                   stat.equals("minecraft:totem_of_undying") || 
                                   stat.equals("minecraft:silverfish") || 
                                   stat.equals("minecraft:wither") || 
                                   stat.equals("minecraft:ender_dragon") || 
                                   stat.contains("dragon_fish")) {
                            
                            String key = stat.replace("minecraft:", "").replace("tide:", "");
                            if (key.contains("dragon_fish")) key = "dragon_fish";
                            addGeneralStat(filteredCategory, playerName, key, value);
                        }
                    }
                    reader.endObject();
                }
                reader.endObject();
            } else {
                reader.skipValue();
            }
        }
        if (distanceCm > 0) {
            addGeneralStat(filteredCategory, playerName, "distance_traveled_raw_cm", (int)distanceCm);
            addGeneralStat(filteredCategory, playerName, "distance_traveled_km", (int)(distanceCm / 100000L));
        }
        if (damageDealt > 0) {
            addGeneralStat(filteredCategory, playerName, "damage_dealt_hearts", damageDealt / 10);
        }
        if (totalMined > 0) {
            addGeneralStat(filteredCategory, playerName, "total_blocks_broken", totalMined);
        }
        if (totalUsed > 0) {
            addGeneralStat(filteredCategory, playerName, "total_blocks_placed", totalUsed);
        }
        if (redstoneUsed > 0) {
            addGeneralStat(filteredCategory, playerName, "redstone_used_raw", redstoneUsed);
        }

        reader.endObject();
    }

    private void calculateAndInjectPlaystyleScores(Map<String, Map<String, Map<String, Integer>>> result, Map<String, String> playerToUuid, Path worldDir) {
        Map<String, Map<String, Integer>> general = result.get("general");
        if (general == null) return;

        Map<String, Integer> distRaw = general.get("distance_traveled_raw_cm");
        Map<String, Integer> blocksPlaced = general.get("total_blocks_placed");
        Map<String, Integer> blocksBroken = general.get("total_blocks_broken");
        Map<String, Integer> mobKills = general.get("mob_kills");
        Map<String, Integer> damageHearts = general.get("damage_dealt_hearts");
        Map<String, Integer> redstoneRaw = general.get("redstone_used_raw");

        Set<String> allPlayers = new HashSet<>();
        if (distRaw != null) allPlayers.addAll(distRaw.keySet());
        if (blocksPlaced != null) allPlayers.addAll(blocksPlaced.keySet());
        if (blocksBroken != null) allPlayers.addAll(blocksBroken.keySet());
        if (mobKills != null) allPlayers.addAll(mobKills.keySet());
        if (damageHearts != null) allPlayers.addAll(damageHearts.keySet());
        if (redstoneRaw != null) allPlayers.addAll(redstoneRaw.keySet());

        for (String player : allPlayers) {
            String uuid = playerToUuid.get(player);
            File advFile = worldDir.resolve("advancements").resolve(uuid + ".json").toFile();
            int[] advBonuses = getAdvancementBonus(advFile);

            long distKm = (distRaw != null && distRaw.containsKey(player)) ? (distRaw.get(player) / 100000L) : 0L;
            int placed = (blocksPlaced != null) ? blocksPlaced.getOrDefault(player, 0) : 0;
            int broken = (blocksBroken != null) ? blocksBroken.getOrDefault(player, 0) : 0;
            int kills = (mobKills != null) ? mobKills.getOrDefault(player, 0) : 0;
            int hearts = (damageHearts != null) ? damageHearts.getOrDefault(player, 0) : 0;
            int rsUsed = (redstoneRaw != null) ? redstoneRaw.getOrDefault(player, 0) : 0;

            // Explorer (5000km cap)
            double expBase = Math.min(100.0, (distKm / 5000.0) * 100.0);
            int expFinal = (int) Math.min(100, Math.max(5, expBase + Math.min(30, advBonuses[0])));

            // Builder (500,000 action cap)
            double bldBase = Math.min(100.0, ((placed + broken / 4.0) / 500000.0) * 100.0);
            int bldFinal = (int) Math.min(100, Math.max(5, bldBase + Math.min(30, advBonuses[1])));

            // Fighter (150,000 combat cap)
            double fgtBase = Math.min(100.0, ((kills + hearts / 10.0) / 150000.0) * 100.0);
            int fgtFinal = (int) Math.min(100, Math.max(5, fgtBase + Math.min(30, advBonuses[2])));

            // Redstoner (7,500 interaction cap)
            double redBase = Math.min(100.0, (rsUsed / 7500.0) * 100.0);
            int redFinal = (int) Math.min(100, Math.max(5, redBase + Math.min(30, advBonuses[3])));

            addGeneralStat(general, player, "playstyle_explorer", expFinal);
            addGeneralStat(general, player, "playstyle_builder", bldFinal);
            addGeneralStat(general, player, "playstyle_fighter", fgtFinal);
            addGeneralStat(general, player, "playstyle_redstoner", redFinal);
        }
    }

    private int[] getAdvancementBonus(File advFile) {
        int[] bonuses = new int[4];
        if (advFile == null || !advFile.exists()) return bonuses;

        try (JsonReader reader = new JsonReader(new FileReader(advFile))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String advId = reader.nextName().toLowerCase();
                reader.beginObject();
                boolean done = false;
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if ("done".equals(key)) {
                        done = reader.nextBoolean();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();

                if (done) {
                    if (containsAny(advId, "explore", "discover", "travel", "biome", "adventure")) bonuses[0] += 5;
                    if (containsAny(advId, "build", "construct", "place", "craft")) bonuses[1] += 5;
                    if (containsAny(advId, "kill", "slay", "monster", "combat", "boss")) bonuses[2] += 5;
                    if (containsAny(advId, "redstone", "machine", "circuit", "automation")) bonuses[3] += 5;
                }
            }
            reader.endObject();
        } catch (Exception e) {
            // malformed or empty advancement file
        }
        return bonuses;
    }

    private boolean containsAny(String str, String... keywords) {
        for (String k : keywords) {
            if (str.contains(k)) return true;
        }
        return false;
    }

    private void addGeneralStat(Map<String, Map<String, Integer>> filteredCategory, String playerName, String key, int value) {
        Map<String, Integer> statMap = filteredCategory.computeIfAbsent(key, k -> new HashMap<>());
        statMap.merge(playerName, value, Integer::sum);
    }

    private boolean isRedstone(String stat) {
        String id = stat.replace("minecraft:", "");
        for (String rs : REDSTONE_ITEMS) {
            if (id.equals(rs) || id.contains(rs)) return true;
        }
        return false;
    }

    private static final String[] REDSTONE_ITEMS = {
        "redstone", "repeater", "comparator", "observer", "piston", "sticky_piston", 
        "redstone_torch", "lever", "daylight_detector", "target", "sculk_sensor", 
        "dropper", "dispenser", "hopper", "trapped_chest", "tnt", "tnt_minecart", 
        "redstone_lamp", "redstone_block", "tripwire_hook", "sculk_shrieker", "sculk_catalyst"
    };

    public void streamPlayerStats(String username, UuidCache uuidCache, Path statsDir, OutputStream out) throws IOException {
        streamFile(username, uuidCache, statsDir, out, "stats");
    }

    public void streamPlayerAdvancements(String username, UuidCache uuidCache, Path advancementsDir, OutputStream out) throws IOException {
        streamFile(username, uuidCache, advancementsDir, out, "advancements");
    }

    private void streamFile(String username, UuidCache uuidCache, Path dir, OutputStream out, String type) throws IOException {
        Optional<UUID> uuid = uuidCache.getUuid(username);
        
        if (uuid.isEmpty()) {
            // First check if the username itself is just a raw UUID string
            try {
                uuid = Optional.of(UUID.fromString(username));
                if (!Files.exists(dir.resolve(uuid.get() + ".json"))) {
                    uuid = Optional.empty(); // Not a valid file, fallback to alias search
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
                            if (uuid.isPresent() && Files.exists(dir.resolve(uuid.get() + ".json"))) {
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

        Path file = dir.resolve(uuid.get() + ".json");
        if (!Files.exists(file)) {
            throw new FileNotFoundException("No " + type + " for: " + username);
        }

        // Act as a pure pipe
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
