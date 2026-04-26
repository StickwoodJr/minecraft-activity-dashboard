package com.playtime.dashboard.web;

import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.stats.StatsAggregator;
import com.playtime.dashboard.util.UuidCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class PlayerAdvancementsHandler implements HttpHandler {
    private final StatsAggregator statsAggregator;
    private final UuidCache uuidCache;

    public PlayerAdvancementsHandler(StatsAggregator statsAggregator, UuidCache uuidCache) {
        this.statsAggregator = statsAggregator;
        this.uuidCache = uuidCache;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/player-advancements/";
        if (!path.startsWith(prefix)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        String username = path.substring(prefix.length());
        
        if (!username.matches("[\\w/\\-]+")) {
            sendError(exchange, 400, "Invalid username format");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");

        Path baseAdvancementsDir = FabricLoader.getInstance().getGameDir()
                .resolve(DashboardConfig.get().stats_world_name)
                .resolve("advancements");

        OutputStream lazyOut = new OutputStream() {
            private OutputStream real = null;
            private void init() throws IOException {
                if (real == null) {
                    exchange.sendResponseHeaders(200, 0); // chunked response
                    real = exchange.getResponseBody();
                }
            }
            @Override public void write(int b) throws IOException { init(); real.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { init(); real.write(b, off, len); }
            @Override public void close() throws IOException {
                if (real == null) { // Empty file, send empty success if it reached here
                    exchange.sendResponseHeaders(200, 0);
                    real = exchange.getResponseBody();
                }
                real.close();
            }
            @Override public void flush() throws IOException { if (real != null) real.flush(); }
        };

        try {
            statsAggregator.streamPlayerAdvancements(username, uuidCache, baseAdvancementsDir, lazyOut);
            lazyOut.close();
        } catch (FileNotFoundException e) {
            sendError(exchange, 404, "Advancements not found");
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("PlayerAdvancementsHandler: Error serving advancements for '" + username + "'", e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String json = "{\"error\":\"" + message + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
