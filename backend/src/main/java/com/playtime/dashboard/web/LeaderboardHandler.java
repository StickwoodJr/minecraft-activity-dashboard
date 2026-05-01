package com.playtime.dashboard.web;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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

        // 1. ETag Support
        long lastModified = leaderboardCacheFile.exists() ? leaderboardCacheFile.lastModified() : 0;
        String etag = "\"" + lastModified + "\"";
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");

        if (lastModified > 0 && etag.equals(ifNoneMatch)) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("ETag", etag);

        if (!leaderboardCacheFile.exists()) {
            String emptyJson = "{}";
            exchange.sendResponseHeaders(200, emptyJson.length());
            try (var os = exchange.getResponseBody()) {
                os.write(emptyJson.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        // 2. Pre-fetch ignored players set (pre-computed in DashboardConfig)
        Set<String> ignoredPlayers = DashboardConfig.get().getIgnoredLowerNames();

        // 3. Streaming JsonReader -> JsonWriter
        exchange.sendResponseHeaders(200, 0); // Chunked encoding
        try (JsonReader reader = new JsonReader(new FileReader(leaderboardCacheFile));
             Writer osw = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(osw)) {
            
            streamLeaderboards(reader, writer, ignoredPlayers);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to stream leaderboards", e);
            // We can't send 500 here if we already sent 200/chunked headers
        }
    }

    private void streamLeaderboards(JsonReader reader, JsonWriter writer, Set<String> ignoredPlayers) throws IOException {
        reader.beginObject();
        writer.beginObject();

        while (reader.hasNext()) {
            String category = reader.nextName();
            writer.name(category);

            reader.beginObject();
            writer.beginObject();

            while (reader.hasNext()) {
                String stat = reader.nextName();
                writer.name(stat);

                reader.beginObject();
                writer.beginObject();

                while (reader.hasNext()) {
                    String player = reader.nextName();
                    // Check ignored set directly (case-insensitive via lowercase keys)
                    if (ignoredPlayers.contains(player.toLowerCase())) {
                        reader.skipValue();
                    } else {
                        writer.name(player);
                        // Stream the value (usually a number)
                        JsonElement value = GSON.fromJson(reader, JsonElement.class);
                        GSON.toJson(value, writer);
                    }
                }

                reader.endObject();
                writer.endObject();
            }

            reader.endObject();
            writer.endObject();
        }

        reader.endObject();
        writer.endObject();
    }
}

