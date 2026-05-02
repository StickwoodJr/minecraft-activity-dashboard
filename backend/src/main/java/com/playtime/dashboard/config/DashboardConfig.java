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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import java.util.Map;
import java.util.HashMap;

public class DashboardConfig {
    public static final int CURRENT_CONFIG_VERSION = 1;
    public static final int DEFAULT_WEB_PORT = 8105;
    public static final int DEFAULT_INCREMENTAL_UPDATE_INTERVAL = 5;
    public static final int DEFAULT_SKIN_REFRESH_HOURS = 24;
    public static final int DEFAULT_LEADERBOARD_UPDATE_INTERVAL = 10;
    public static final int DEFAULT_LIVE_UPDATE_INTERVAL = 3;
    public static final int DEFAULT_MAX_CONCURRENT_EVENTS = 3;
    public static final int DEFAULT_STREAK_MINIMUM_MINUTES = 60;
    public static final int DEFAULT_WORLD_SIZE_REFRESH_MINUTES = 30;
    public static final int DEFAULT_WORLD_SIZE_MAX_DEPTH = 8;
    public static final int DEFAULT_UUID_REFRESH_COOLDOWN = 3600;

    public int config_version = CURRENT_CONFIG_VERSION;
    public int web_port = DEFAULT_WEB_PORT;
    public int incremental_update_interval_minutes = DEFAULT_INCREMENTAL_UPDATE_INTERVAL;
    public String logs_directory = ""; // Leave empty to use default (game_dir/logs)
    public String dashboard_title = "Activity Dashboard";
    public String dashboard_description = "Combined playtime from join/leave events";
    public String tab_title = "Playtime Dashboard";
    public String server_name = "MC Server";
    public String custom_logo_path = ""; // Path to a local .jpg or .png file
    public String favicon_path = "";    // Path to a local .ico, .png, or .jpg file
    public List<String> ignored_players = Arrays.asList("ironfarmbot", "mobfarmbot");
    public Map<String, String> player_aliases = new java.util.HashMap<>(); // Map UUIDs or Names to a single display name
    public Map<String, String> primary_player_heads = new java.util.HashMap<>(); // Map a display name to a primary UUID or Name to use for the player head
    public boolean fetch_player_heads = true;      // Fetch Minecraft player heads from Mojang
    public int skin_refresh_hours = DEFAULT_SKIN_REFRESH_HOURS;            // Hours before re-fetching a player's skin
    public String stats_world_name = "world"; // Minecraft world folder name
    public int leaderboard_update_interval_minutes = DEFAULT_LEADERBOARD_UPDATE_INTERVAL; // Can differ from incremental_update_interval_minutes
    public boolean enable_dynmap = true;
    public String dynmap_url = "http://149.56.155.7:8032";
    public boolean enable_live_tab = true;
    public int live_update_interval_seconds = DEFAULT_LIVE_UPDATE_INTERVAL;
    public String resource_pack_url = "http://149.56.155.7:8105/respack.zip"; // Sets resource-pack in server.properties if not empty
    public int max_concurrent_events = DEFAULT_MAX_CONCURRENT_EVENTS;
    public String streak_timezone = "America/Toronto";
    public int streak_minimum_minutes_per_day = DEFAULT_STREAK_MINIMUM_MINUTES;
    public int streak_cache_ttl_minutes = -1; // -1 means inherit incremental_update_interval_minutes
    public boolean allow_pvp_events = true;
    public int world_size_refresh_minutes = DEFAULT_WORLD_SIZE_REFRESH_MINUTES;
    public int world_size_max_depth = DEFAULT_WORLD_SIZE_MAX_DEPTH;
    public int uuid_refresh_cooldown_seconds = DEFAULT_UUID_REFRESH_COOLDOWN;

    private static final transient Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static DashboardConfig instance;

    /** Precomputed lowercase ignored-player names. Recomputed in {@link #load()}. */
    private transient volatile Set<String> ignoredLowerNames = Collections.emptySet();
    /** Precomputed offline UUIDs ({@code OfflinePlayer:<name>}) for ignored players. */
    private transient volatile Set<String> ignoredOfflineUuids = Collections.emptySet();
    /** Precomputed lower-case alias map (key = lowercased original key). */
    private transient volatile Map<String, String> aliasesLower = Collections.emptyMap();

    public static DashboardConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public int getStreakCacheTtlMinutes() {
        return streak_cache_ttl_minutes > 0 ? streak_cache_ttl_minutes : incremental_update_interval_minutes;
    }

    public Set<String> getIgnoredLowerNames() {
        return ignoredLowerNames;
    }

    public Set<String> getIgnoredOfflineUuids() {
        return ignoredOfflineUuids;
    }

    /**
     * @return true if the player name is in the ignored list (case-insensitive).
     */
    public boolean isPlayerIgnored(String name) {
        if (name == null) return false;
        return ignoredLowerNames.contains(name.toLowerCase());
    }

    /**
     * @return true if the UUID is in the ignored list (as an offline UUID).
     */
    public boolean isUuidIgnored(String uuid) {
        if (uuid == null) return false;
        return ignoredOfflineUuids.contains(uuid);
    }

    /**
     * @return true if either the name or UUID matches the ignored list.
     */
    public boolean isIgnored(String name, String uuid) {
        return isPlayerIgnored(name) || isUuidIgnored(uuid);
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
        if (playerName.matches(\"^[a-zA-Z0-9_]{2,16}$\")) {
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
            FabricDashboardMod.LOGGER.error(\"Invalid config key 'streak_timezone': {}. Falling back to UTC.\", streak_timezone);
            streak_timezone = \"UTC\";
        }

        Set<String> names = new HashSet<>();
        Set<String> offlineUuids = new HashSet<>();
        if (ignored_players != null) {
            for (String n : ignored_players) {
                if (n == null || n.isEmpty()) continue;
                names.add(n.toLowerCase());
                String offline = UUID.nameUUIDFromBytes((\"OfflinePlayer:\" + n).getBytes(StandardCharsets.UTF_8)).toString();
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
            FabricDashboardMod.LOGGER.info(\"Config version {} found, expected {} — some defaults may apply.\", config_version, CURRENT_CONFIG_VERSION);
            config_version = CURRENT_CONFIG_VERSION;
        }

        web_port = checkRange(\"web_port\", web_port, 1024, 65535, DEFAULT_WEB_PORT);
        incremental_update_interval_minutes = checkRange(\"incremental_update_interval_minutes\", incremental_update_interval_minutes, 1, 1440, DEFAULT_INCREMENTAL_UPDATE_INTERVAL);
        skin_refresh_hours = checkRange(\"skin_refresh_hours\", skin_refresh_hours, 1, 8760, DEFAULT_SKIN_REFRESH_HOURS);
        leaderboard_update_interval_minutes = checkRange(\"leaderboard_update_interval_minutes\", leaderboard_update_interval_minutes, 1, 1440, DEFAULT_LEADERBOARD_UPDATE_INTERVAL);
        live_update_interval_seconds = checkRange(\"live_update_interval_seconds\", live_update_interval_seconds, 1, 300, DEFAULT_LIVE_UPDATE_INTERVAL);
        max_concurrent_events = checkRange(\"max_concurrent_events\", max_concurrent_events, 1, 50, DEFAULT_MAX_CONCURRENT_EVENTS);
        streak_minimum_minutes_per_day = checkRange(\"streak_minimum_minutes_per_day\", streak_minimum_minutes_per_day, 1, 1440, DEFAULT_STREAK_MINIMUM_MINUTES);
        
        // streak_cache_ttl_minutes can be -1 (special value)
        if (streak_cache_ttl_minutes != -1) {
            streak_cache_ttl_minutes = checkRange(\"streak_cache_ttl_minutes\", streak_cache_ttl_minutes, 1, 1440, -1);
        }

        world_size_refresh_minutes = checkRange(\"world_size_refresh_minutes\", world_size_refresh_minutes, 1, 1440, DEFAULT_WORLD_SIZE_REFRESH_MINUTES);
        world_size_max_depth = checkRange(\"world_size_max_depth\", world_size_max_depth, 1, 32, DEFAULT_WORLD_SIZE_MAX_DEPTH);
        uuid_refresh_cooldown_seconds = checkRange(\"uuid_refresh_cooldown_seconds\", uuid_refresh_cooldown_seconds, 0, 86400, DEFAULT_UUID_REFRESH_COOLDOWN);
    }

    private int checkRange(String key, int value, int min, int max, int defaultValue) {
        if (value < min || value > max) {
            FabricDashboardMod.LOGGER.warn(\"Invalid config key '{}': {}. Must be between {} and {}. Using default: {}\", key, value, min, max, defaultValue);
            return defaultValue;
        }
        return value;
    }

    public static boolean load() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), \"dashboard-config.json\");
        DashboardConfig oldConfig = instance;
        DashboardConfig newConfig = null;
        boolean loadedFromDisk = false;
        boolean newlyCreated = false;

        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                newConfig = GSON.fromJson(reader, DashboardConfig.class);
                if (newConfig != null) {
                    loadedFromDisk = true;
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error(\"Failed to load dashboard config. Reverting to defaults.\", e);
            }
        }
        
        if (newConfig == null) {
            newConfig = new DashboardConfig();
            newlyCreated = true;
        }

        instance = newConfig;
        instance.recomputeDerived();

        // Check for restart-required field changes
        if (oldConfig != null) {
            if (oldConfig.web_port != instance.web_port) {
                FabricDashboardMod.LOGGER.warn(\"Config key 'web_port' changed to {}. A restart is required for this change to take effect.\", instance.web_port);
            }
            if (!java.util.Objects.equals(oldConfig.logs_directory, instance.logs_directory)) {
                FabricDashboardMod.LOGGER.warn(\"Config key 'logs_directory' changed. A restart is required for this change to take effect.\");
            }
            if (!java.util.Objects.equals(oldConfig.stats_world_name, instance.stats_world_name)) {
                FabricDashboardMod.LOGGER.warn(\"Config key 'stats_world_name' changed. A restart is required for this change to take effect.\");
            }
            if (!java.util.Objects.equals(oldConfig.resource_pack_url, instance.resource_pack_url)) {
                FabricDashboardMod.LOGGER.warn(\"Config key 'resource_pack_url' changed. A restart is required for this change to take effect.\");
            }
        }

        // Only save if it's a brand new config to avoid overwriting user edits
        if (newlyCreated) {
            save();
        }
        return loadedFromDisk;
    }

    public static void save() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), \"dashboard-config.json\");
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error(\"Failed to save dashboard config\", e);
        }
    }
}
