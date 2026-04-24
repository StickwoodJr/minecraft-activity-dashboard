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

public class DashboardConfig {
    public int web_port = 5000;
    public int incremental_update_interval_minutes = 5;
    public String logs_directory = ""; // Leave empty to use default (game_dir/logs)
    public List<String> ignored_players = Arrays.asList("ironfarmbot", "mobfarmbot", "EinenSoenenAbend");
    public boolean fetch_player_heads = true;      // Fetch Minecraft player heads from Mojang
    public int skin_refresh_hours = 24;            // Hours before re-fetching a player's skin

    private static final transient Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static DashboardConfig instance;

    public static DashboardConfig get() {
        load();
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
