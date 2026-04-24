package com.playtime.dashboard.web;

import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.parser.LogParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Embedded JDK HTTP server that powers the Minecraft Activity Dashboard.
 * Exposes four endpoints: {@code /} for the HTML frontend, {@code /api/activity} for cached
 * JSON session data, {@code /api/player-meta} for skin-derived player colors, and
 * {@code /faces/<player>.png} for cached player head images.
 * A background scheduler handles the initial historical log parse on first run and
 * then re-runs an incremental parse on a configurable interval.
 */
public class DashboardWebServer {
    private final MinecraftServer minecraftServer;
    private HttpServer httpServer;
    private ScheduledExecutorService scheduler;
    private final LogParser parser;
    private final File cacheFile;
    private final PlayerHeadService headService;

    public DashboardWebServer(MinecraftServer server) {
        this.minecraftServer = server;
        this.parser = new LogParser();
        this.cacheFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "dashboard_cache.json");
        this.headService = new PlayerHeadService(FabricLoader.getInstance().getGameDir().toFile());
    }

    public void start() {
        try {
            int port = DashboardConfig.get().web_port;
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            
            httpServer.createContext("/", new HtmlHandler());
            httpServer.createContext("/api/activity", new ApiHandler(cacheFile));
            httpServer.createContext("/api/player-meta", new PlayerMetaHandler(headService));
            httpServer.createContext("/faces/", new FaceHandler(headService));
            
            httpServer.setExecutor(Executors.newFixedThreadPool(2));
            httpServer.start();
            FabricDashboardMod.LOGGER.info("Dashboard Web Server started on port " + port);

            // Setup scheduling
            scheduler = Executors.newScheduledThreadPool(1);
            
            // Run historical parse once if needed, then schedule incremental
            scheduler.execute(() -> {
                File logsDir;
                String customLogsDir = DashboardConfig.get().logs_directory;
                if (customLogsDir != null && !customLogsDir.trim().isEmpty()) {
                    logsDir = new File(customLogsDir);
                } else {
                    logsDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "logs");
                }

                FabricDashboardMod.LOGGER.info("Dashboard background task executing using logs directory: " + logsDir.getAbsolutePath());

                if (!cacheFile.exists()) {
                    FabricDashboardMod.LOGGER.info("Cache file not found at " + cacheFile.getAbsolutePath() + ". Beginning historical parse.");
                    parser.runHistoricalParse(logsDir, cacheFile);
                } else {
                    FabricDashboardMod.LOGGER.info("Found existing cache file at " + cacheFile.getAbsolutePath() + ". Skipping historical parse.");
                }

                // After parse, trigger head fetches for all known players
                triggerHeadFetches();

                long delay = DashboardConfig.get().incremental_update_interval_minutes;
                scheduler.scheduleAtFixedRate(() -> {
                    File incLogsDir;
                    String cLogsDir = DashboardConfig.get().logs_directory;
                    if (cLogsDir != null && !cLogsDir.trim().isEmpty()) {
                        incLogsDir = new File(cLogsDir);
                    } else {
                        incLogsDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "logs");
                    }
                    parser.runIncrementalParse(incLogsDir, cacheFile);
                    // Re-check for new players after incremental parse
                    triggerHeadFetches();
                }, delay, delay, TimeUnit.MINUTES);
            });

        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to start Dashboard Web Server", e);
        }
    }

    /** Scans the cache file for all known player names and kicks off head fetches. */
    private void triggerHeadFetches() {
        if (!cacheFile.exists()) return;
        try (Reader reader = new FileReader(cacheFile)) {
            JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
            Set<String> playerNames = new HashSet<>();

            // Collect player names from playerDailyRaw
            if (data.has("playerDailyRaw")) {
                JsonObject pdr = data.getAsJsonObject("playerDailyRaw");
                for (String dateKey : pdr.keySet()) {
                    JsonObject dayMap = pdr.getAsJsonObject(dateKey);
                    playerNames.addAll(dayMap.keySet());
                }
            }

            // Also from sessData
            if (data.has("sessData")) {
                playerNames.addAll(data.getAsJsonObject("sessData").keySet());
            }

            if (!playerNames.isEmpty()) {
                FabricDashboardMod.LOGGER.info("Triggering head fetches for " + playerNames.size() + " known players");
                headService.fetchAllKnown(playerNames);
            }
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.warn("Could not scan cache for player names: " + e.getMessage());
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (headService != null) {
            headService.shutdown();
        }
    }

    private static class HtmlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            String path = exchange.getRequestURI().getPath();
            
            if (path.equals("/server-logo.jpg")) {
                try (InputStream is = getClass().getResourceAsStream("/web/server-logo.jpg")) {
                    if (is == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                }
                return;
            }
            
            if (!path.equals("/") && !path.equals("/mc-activity-heatmap-v13.html")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            try (InputStream is = getClass().getResourceAsStream("/web/mc-activity-heatmap-v13.html")) {
                if (is == null) {
                    String error = "HTML file not found in mod resources.";
                    exchange.sendResponseHeaders(500, error.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(error.getBytes());
                    }
                    return;
                }
                
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, 0);
                
                try (OutputStream os = exchange.getResponseBody()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            }
        }
    }

    /** Serves cached player face PNGs from disk. */
    private static class FaceHandler implements HttpHandler {
        private final PlayerHeadService headService;

        public FaceHandler(PlayerHeadService headService) {
            this.headService = headService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            // Extract player name from /faces/<playerName>.png
            String filename = path.substring("/faces/".length());
            if (!filename.endsWith(".png")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String playerName = filename.substring(0, filename.length() - 4);

            // Special-case: serve default_head directly from pre-loaded bytes
            // This prevents the fallback-for-the-fallback problem
            if (playerName.equals("default_head")) {
                serveDefaultHead(exchange);
                return;
            }

            // Sanitize player name — only allow word characters, slash, and hyphen
            if (!playerName.matches("[\\w/\\-]+")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            File faceFile = headService.getFaceFile(playerName);

            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");

            if (faceFile != null && faceFile.exists()) {
                exchange.sendResponseHeaders(200, faceFile.length());
                try (InputStream is = new FileInputStream(faceFile);
                     OutputStream os = exchange.getResponseBody()) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                // Serve default head from pre-loaded bytes
                serveDefaultHead(exchange);
                // Trigger a background fetch for this player
                headService.fetchIfNeeded(playerName);
            }
        }

        private void serveDefaultHead(HttpExchange exchange) throws IOException {
            byte[] defaultBytes = headService.getDefaultHeadBytes();
            if (defaultBytes == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, defaultBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(defaultBytes);
            }
        }
    }

    /** Returns JSON map of player names to their skin-derived hex colors. */
    private static class PlayerMetaHandler implements HttpHandler {
        private final PlayerHeadService headService;
        private static final Gson GSON = new Gson();

        public PlayerMetaHandler(PlayerHeadService headService) {
            this.headService = headService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Map<String, String> colorMap = headService.getColorMap();
            byte[] json = GSON.toJson(colorMap).getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=60");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }
        }
    }

    private static class ApiHandler implements HttpHandler {
        private final File cacheFile;

        public ApiHandler(File cacheFile) {
            this.cacheFile = cacheFile;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            
            if (!cacheFile.exists()) {
                String emptyJson = "{\"daily\":{},\"playerDailyRaw\":{},\"sessData\":{},\"hourly\":{}}";
                exchange.sendResponseHeaders(200, emptyJson.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(emptyJson.getBytes());
                }
                return;
            }
            
            exchange.sendResponseHeaders(200, cacheFile.length());
            try (InputStream is = new FileInputStream(cacheFile);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }
    }
}
