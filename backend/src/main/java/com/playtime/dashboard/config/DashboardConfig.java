package com.playtime.dashboard.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.playtime.dashboard.FabricDashboardMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DashboardConfig {
    private static final int CURRENT_CONFIG_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "dashboard-config.json");
    private static DashboardConfig instance;

    // --- Config Fields ---
    public int config_version = CURRENT_CONFIG_VERSION;
    public int web_port = 8105;
    public String logs_directory = "";
    public String stats_world_name = "world";
    public String tab_title = "Playtime Dashboard";
    public String dashboard_title = "Activity Dashboard";
    public String custom_logo_path = "";
    public boolean enable_dynmap = true;
    public String dynmap_url = "";
    public List<String> ignored_players = new ArrayList<>();
    public Map<String, String> player_aliases = new HashMap<>();
    public Map<String, String> primary_player_heads = new HashMap<>();
    public int max_concurrent_events = 3;
    public String streak_timezone = "UTC";
    public int streak_minimum_minutes_per_day = 60;
    public int streak_cache_ttl_minutes = -1;
    public int incremental_update_interval_minutes = 5;
    public int leaderboard_update_interval_minutes = 10;
    public boolean fetch_player_heads = true;
    public String resource_pack_url = "";
    public boolean enable_live_tab = true;
    public int live_update_interval_seconds = 3;
    public int world_size_refresh_minutes = 30;
    public int world_size_max_depth = 8;
    public int skin_refresh_hours = 24;
    public int uuid_refresh_cooldown_seconds = 3600;

    // --- Derived State ---
    private transient Set<String> ignoredLowerNames = Collections.emptySet();
    private transient Set<String> ignoredOfflineUuids = Collections.emptySet();
    private transient Map<String, String> aliasesLower = Collections.emptyMap();

    public static DashboardConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static boolean load() {
        if (!CONFIG_FILE.exists()) {
            instance = new DashboardConfig();
            instance.ignored_players.add("ironfarmbot");
            instance.ignored_players.add("mobfarmbot");
            instance.save();
            return true;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            instance = GSON.fromJson(reader, DashboardConfig.class);
            if (instance == null) {
                FabricDashboardMod.LOGGER.error("Failed to parse config (GSON returned null). Falling back to defaults.");
                instance = new DashboardConfig();
                return false;
            }
            instance.recomputeDerived();
            return true;
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to load config: " + e.getMessage());
            if (instance == null) {
                instance = new DashboardConfig();
            }
            return false;
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to save config: " + e.getMessage());
        }
    }

    public boolean isPlayerIgnored(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isEmpty()) return false;
        String lower = nameOrUuid.toLowerCase();
        if (ignoredLowerNames.contains(lower)) return true;
        if (ignoredOfflineUuids.contains(lower)) return true;
        
        // Also check if the normalized display name is ignored
        String normalized = getNormalizedName(nameOrUuid);
        if (normalized != null && !normalized.equalsIgnoreCase(nameOrUuid)) {
            if (ignoredLowerNames.contains(normalized.toLowerCase())) return true;
        }
        
        return false;
    }

    public int getStreakCacheTtlMinutes() {
        if (streak_cache_ttl_minutes < 0) return incremental_update_interval_minutes;
        return streak_cache_ttl_minutes;
    }

    public Map<String, String> getAliasesLower() {
        return aliasesLower;
    }

    /**
     * Resolves a player name (which could be a real username or a synthetic alias)
     * to a primary UUID based on config overrides and alias maps.
     */
    public java.util.Optional<java.util.UUID> resolvePrimaryUuid(String playerName) {
        if (playerName == null || playerName.isEmpty()) return java.util.Optional.empty();
        
        com.playtime.dashboard.util.UuidCache cache = com.playtime.dashboard.util.UuidCache.getInstance();

        // 1. Check explicitly defined primary player heads first
        if (primary_player_heads != null && primary_player_heads.containsKey(playerName)) {
            String primarySource = primary_player_heads.get(playerName);
            try {
                // If the source is a raw UUID, use it directly
                return java.util.Optional.of(java.util.UUID.fromString(primarySource));
            } catch (IllegalArgumentException e) {
                // Otherwise, treat as a username and resolve it
                return cache.getUuid(primarySource);
            }
        }

        // 2. Try to find a source username/UUID that maps to this target alias
        for (Map.Entry<String, String> entry : aliasesLower.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(playerName)) {
                String source = entry.getKey(); // Keys in aliasesLower are lowercased
                try {
                    return java.util.Optional.of(java.util.UUID.fromString(source));
                } catch (IllegalArgumentException e) {
                    // Key is a name, resolve its UUID
                    return cache.getUuid(source);
                }
            }
        }

        // 3. Fallback: Standard direct lookup (only if it looks like a real Minecraft name)
        if (playerName.matches("^[a-zA-Z0-9_]{2,16}$")) {
            return cache.getUuid(playerName);
        }

        return java.util.Optional.empty();
    }

    /**
     * Normalizes a player name to its canonical display name if an alias exists.
     */
    public String getNormalizedName(String playerName) {
        if (playerName == null) return null;
        if (aliasesLower == null || aliasesLower.isEmpty()) return playerName;
        String aliased = aliasesLower.get(playerName.toLowerCase());
        return (aliased != null) ? aliased : playerName;
    }

    private void recomputeDerived() {
        validateFields();

        // Validate timezone
        try {
            java.time.ZoneId.of(streak_timezone);
        } catch (java.time.DateTimeException e) {
            FabricDashboardMod.LOGGER.error("Invalid config key 'streak_timezone': {}. Falling back to UTC.", streak_timezone);
            streak_timezone = "UTC";
        }

        Set<String> names = new HashSet<>();
        Set<String> offlineUuids = new HashSet<>();
        if (ignored_players != null) {
            for (String n : ignored_players) {
                if (n == null || n.isEmpty()) continue;
                names.add(n.toLowerCase());
                String offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + n).getBytes(StandardCharsets.UTF_8)).toString();
                offlineUuids.add(offline);
            }
        }
        ignoredLowerNames = Collections.unmodifiableSet(names);
        ignoredOfflineUuids = Collections.unmodifiableSet(offlineUuids);

        Map<String, String> al = new HashMap<>();
        if (player_aliases != null) {
            for (Map.Entry<String, String> e : player_aliases.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                al.put(e.getKey().toLowerCase(), e.getValue().trim());
            }
        }
        aliasesLower = Collections.unmodifiableMap(al);
    }

    private void validateFields() {
        if (config_version != CURRENT_CONFIG_VERSION) {
            FabricDashboardMod.LOGGER.info("Config version {} found, expected {} \u2014 some defaults may apply.", config_version, CURRENT_CONFIG_VERSION);
            config_version = CURRENT_CONFIG_VERSION;
        }
        if (web_port < 1 || web_port > 65535) web_port = 8105;
        if (streak_minimum_minutes_per_day < 0) streak_minimum_minutes_per_day = 60;
        if (incremental_update_interval_minutes < 1) incremental_update_interval_minutes = 5;
        if (leaderboard_update_interval_minutes < 1) leaderboard_update_interval_minutes = 10;
        if (live_update_interval_seconds < 1) live_update_interval_seconds = 3;
        if (world_size_refresh_minutes < 1) world_size_refresh_minutes = 30;
        if (world_size_max_depth < 1) world_size_max_depth = 8;
        if (skin_refresh_hours < 1) skin_refresh_hours = 24;
        if (uuid_refresh_cooldown_seconds < 0) uuid_refresh_cooldown_seconds = 3600;
    }
}
