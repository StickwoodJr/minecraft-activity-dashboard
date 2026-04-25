package com.playtime.dashboard.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.playtime.dashboard.FabricDashboardMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import java.util.Map;
import java.util.HashMap;

public class DashboardConfig {
    public int web_port = 8105;
    public int incremental_update_interval_minutes = 5;
    public String logs_directory = ""; // Leave empty to use default (game_dir/logs)
    public String dashboard_title = "Player Session Activity";
    public String dashboard_description = "Combined playtime from join/leave events";
    public String tab_title = "Playtime Dashboard";
    public String server_name = "MC Server";
    public String custom_logo_path = ""; // Path to a local .jpg or .png file
    public String favicon_path = "";    // Path to a local .ico, .png, or .jpg file
    public List<String> ignored_players = Arrays.asList("ironfarmbot", "mobfarmbot", "EinenSoenenAbend");
    public Map<String, String> player_aliases = new java.util.HashMap<>(); // Map UUIDs or Names to a single display name
    public boolean fetch_player_heads = true;      // Fetch Minecraft player heads from Mojang
    public int skin_refresh_hours = 24;            // Hours before re-fetching a player's skin
    public String stats_world_name = "world"; // Minecraft world folder name
    public int leaderboard_update_interval_minutes = 10; // Can differ from incremental_update_interval_minutes

    private static final transient Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static DashboardConfig instance;

    public static DashboardConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "dashboard-config.json");
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                instance = GSON.fromJson(reader, DashboardConfig.class);
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to load dashboard config. Reverting to defaults.", e);
            }
        }
        
        if (instance == null) {
            instance = new DashboardConfig();
        }
        // Always save to ensure new default fields are written back to the file
        save();
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
