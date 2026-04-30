package com.playtime.dashboard.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LeaderboardHandler implements HttpHandler {
    private final File leaderboardCacheFile;
    private static final Gson GSON = new Gson();

    public LeaderboardHandler(File leaderboardCacheFile) {
        this.leaderboardCacheFile = leaderboardCacheFile;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");

        if (!leaderboardCacheFile.exists()) {
            String emptyJson = "{}";
            exchange.sendResponseHeaders(200, emptyJson.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(emptyJson.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        try (Reader reader = new FileReader(leaderboardCacheFile)) {
            JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
            DashboardConfig cfg = DashboardConfig.get();

            for (Map.Entry<String, com.google.gson.JsonElement> categoryEntry : data.entrySet()) {
                    JsonObject categoryMap = categoryEntry.getValue().getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> statEntry : categoryMap.entrySet()) {
                        JsonObject statMap = statEntry.getValue().getAsJsonObject();
                        Set<String> toRemove = new HashSet<>();
                        for (String p : statMap.keySet()) {
                            if (cfg.isPlayerIgnored(p)) {
                                toRemove.add(p);
                            }
                        }
                        for (String p : toRemove) {
                            statMap.remove(p);
                        }
                    }
                }

            byte[] json = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to serve leaderboards", e);
            exchange.sendResponseHeaders(500, -1);
        }
    }
}
