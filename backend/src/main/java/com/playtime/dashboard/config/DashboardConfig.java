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
    public int web_port = 8105;
    public int incremental_update_interval_minutes = 5;
    public String logs_directory = ""; // Leave empty to use default (game_dir/logs)
    public String dashboard_title = "Activity Dashboard";
    public String dashboard_description = "Combined playtime from join/leave events";
    public String tab_title = "Playtime Dashboard";
    public String server_name = "MC Server";
    public String custom_logo_path = ""; // Path to a local .jpg or .png file
    public String favicon_path = "";    // Path to a local .ico, .png, or .jpg file
    public List<String> ignored_players = Arrays.asList("ironfarmbot", "mobfarmbot", "EinenSoenenAbend", "Hanger");
    public Map<String, String> player_aliases = new java.util.HashMap<>(); // Map UUIDs or Names to a single display name
    public boolean fetch_player_heads = true;      // Fetch Minecraft player heads from Mojang
    public int skin_refresh_hours = 24;            // Hours before re-fetching a player's skin
    public String stats_world_name = "world"; // Minecraft world folder name
    public int leaderboard_update_interval_minutes = 10; // Can differ from incremental_update_interval_minutes
    public boolean enable_dynmap = true;
    public String dynmap_url = "http://149.56.155.7:8032";
    public boolean enable_live_tab = true;
    public int live_update_interval_seconds = 3;
    public String resource_pack_url = "http://149.56.155.7:8105/respack.zip"; // Sets resource-pack in server.properties if not empty
    public int max_concurrent_events = 3;
    public String streak_timezone = "America/Toronto";
    public boolean allow_pvp_events = true;

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

    public Set<String> getIgnoredLowerNames() {
        return ignoredLowerNames;
    }

    public Set<String> getIgnoredOfflineUuids() {
        return ignoredOfflineUuids;
    }

    public Map<String, String> getAliasesLower() {
        return aliasesLower;
    }

    private void recomputeDerived() {
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

    public static void load() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "dashboard-config.json");
        boolean newlyCreated = false;
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                instance = GSON.fromJson(reader, DashboardConfig.class);
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to load dashboard config. Reverting to defaults.", e);
            }
        }
        
        if (instance == null) {
            instance = new DashboardConfig();
            newlyCreated = true;
        }

        instance.recomputeDerived();

        // Only save if it's a brand new config to avoid overwriting user edits
        if (newlyCreated) {
            save();
        }
    }

    public static void save() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "dashboard-config.json");
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to save dashboard config", e);
        }
    }
}
