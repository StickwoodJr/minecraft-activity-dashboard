package com.playtime.dashboard.web;

import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.parser.LogParser;
import com.playtime.dashboard.stats.StatsAggregator;
import com.playtime.dashboard.util.UuidCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

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
    private final LogParser parser;
    private final File cacheFile;
    private final PlayerHeadService headService;
    private final File leaderboardCacheFile;
    private final StatsAggregator statsAggregator;
    private final UuidCache uuidCache;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile LiveMetricsSnapshot liveSnapshot;
    private long cachedWorldSizeMb = 0;
    private long lastWorldSizeCheck = 0;

    public static class LiveMetricsSnapshot {
        public final int playersOnline;
        public final int maxPlayers;
        public final List<String> playerNames;
        public final double tps;
        public final double mspt;
        public final double cpuProcess;
        public final long jvmUsedMb;
        public final long jvmMaxMb;
        public final double diskUsedGb;
        public final double diskTotalGb;
        public final double diskFreeGb;
        public final long worldSizeMb;
        public final String status;
        public final long timestamp;

        public LiveMetricsSnapshot(int playersOnline, int maxPlayers, List<String> playerNames, 
                                   double tps, double mspt, double cpuProcess, 
                                   long jvmUsedMb, long jvmMaxMb, double diskUsedGb, 
                                   double diskTotalGb, double diskFreeGb, long worldSizeMb, String status) {
            this.playersOnline = playersOnline;
            this.maxPlayers = maxPlayers;
            this.playerNames = Collections.unmodifiableList(playerNames);
            this.tps = tps;
            this.mspt = mspt;
            this.cpuProcess = cpuProcess;
            this.jvmUsedMb = jvmUsedMb;
            this.jvmMaxMb = jvmMaxMb;
            this.diskUsedGb = diskUsedGb;
            this.diskTotalGb = diskTotalGb;
            this.diskFreeGb = diskFreeGb;
            this.worldSizeMb = worldSizeMb;
            this.status = status;
            this.timestamp = System.currentTimeMillis();
        }

        // Warming up constructor
        public LiveMetricsSnapshot() {
            this(0, 0, new ArrayList<>(), 0, 0, 0, 0, 0, 0, 0, 0, 0, "warming_up");
        }
    }

    public DashboardWebServer(MinecraftServer server) {
        this.minecraftServer = server;
        this.parser = new LogParser();
        this.cacheFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "dashboard_cache.json");
        this.headService = new PlayerHeadService(FabricLoader.getInstance().getGameDir().toFile());
        this.leaderboardCacheFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "dashboard_leaderboards.json");
        this.statsAggregator = new StatsAggregator();
        this.uuidCache = new UuidCache();
    }

    public void start() {
        try {
            int port = DashboardConfig.get().web_port;
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            
            httpServer.createContext("/", new HtmlHandler());
            httpServer.createContext("/api/activity", new ApiHandler(cacheFile));
            httpServer.createContext("/api/player-meta", new PlayerMetaHandler(headService));
            httpServer.createContext("/faces/", new FaceHandler(headService));
            httpServer.createContext("/skins/", new SkinHandler(headService));
            httpServer.createContext("/api/leaderboards", new LeaderboardHandler(leaderboardCacheFile));
            httpServer.createContext("/api/player-stats/", new PlayerStatsHandler(statsAggregator, uuidCache));
            httpServer.createContext("/api/player-advancements/", new PlayerAdvancementsHandler(statsAggregator, uuidCache));
            httpServer.createContext("/api/live", new LiveMetricsHandler(this));
            
            httpServer.setExecutor(Executors.newFixedThreadPool(2));
            httpServer.start();
            FabricDashboardMod.LOGGER.info("Dashboard Web Server started on port " + port);

            // Setup scheduling
            
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

                // Trigger head fetches once after the startup parse — not on every incremental update.
                // New players discovered later will have their heads fetched on-demand by the FaceHandler.
                triggerHeadFetches();

                long lbDelay = DashboardConfig.get().leaderboard_update_interval_minutes;
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        java.nio.file.Path statsDir = FabricLoader.getInstance().getGameDir()
                            .resolve(DashboardConfig.get().stats_world_name)
                            .resolve("stats");
                        uuidCache.refresh(); // Re-read usercache.json
                        Map<String, Map<String, Map<String, Integer>>> leaderboards = statsAggregator.buildLeaderboards(statsDir, uuidCache);
                        
                        File tempFile = new File(leaderboardCacheFile.getParentFile(), leaderboardCacheFile.getName() + ".tmp");
                        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
                            new com.google.gson.Gson().toJson(leaderboards, writer);
                        }
                        java.nio.file.Files.move(tempFile.toPath(), leaderboardCacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception e) {
                        FabricDashboardMod.LOGGER.error("Leaderboard aggregation failed", e);
                    }
                }, 0, lbDelay, TimeUnit.MINUTES);

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
                }, delay, delay, TimeUnit.MINUTES);

                // Live metrics sampling
                if (DashboardConfig.get().enable_live_tab) {
                    liveSnapshot = new LiveMetricsSnapshot();
                    long liveInterval = DashboardConfig.get().live_update_interval_seconds;
                    scheduler.scheduleAtFixedRate(this::updateLiveMetrics, 0, liveInterval, TimeUnit.SECONDS);
                }
            });

        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to start Dashboard Web Server", e);
        }
    }

    private void updateLiveMetrics() {
        try {
            // Player data
            int playersOnline = minecraftServer.getPlayerManager().getCurrentPlayerCount();
            int maxPlayers = minecraftServer.getPlayerManager().getMaxPlayerCount();
            List<String> playerNames = new ArrayList<>();
            minecraftServer.getPlayerManager().getPlayerList().forEach(player -> {
                if (playerNames.size() < 100) {
                    playerNames.add(player.getGameProfile().getName());
                }
            });

            // TPS / MSPT
            double tps = 0;
            double mspt = 0;
            long[] tickTimes = minecraftServer.getTickTimes(); // Internal reference, used transiently
            if (tickTimes != null && tickTimes.length > 0) {
                long sum = 0;
                for (long time : tickTimes) sum += time;
                mspt = (sum / (double) tickTimes.length) * 1.0E-6D;
                tps = Math.min(20.0D, 1000.0D / mspt);
            }

            // CPU / Memory
            double cpuProcess = 0;
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunBean = (com.sun.management.OperatingSystemMXBean) osBean;
                cpuProcess = sunBean.getProcessCpuLoad() * 100.0;
            }
            
            Runtime runtime = Runtime.getRuntime();
            long jvmMaxMb = runtime.maxMemory() / (1024 * 1024);
            long jvmUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

            // Disk Usage
            File targetDir = new File("/home/container");
            if (!targetDir.exists()) {
                targetDir = FabricLoader.getInstance().getGameDir().toFile();
            }
            
            double diskTotalGb = targetDir.getTotalSpace() / (1024.0 * 1024.0 * 1024.0);
            double diskFreeGb = targetDir.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
            double diskUsedGb = diskTotalGb - diskFreeGb;

            // World Size (Expensive, update every 10 minutes)
            if (System.currentTimeMillis() - lastWorldSizeCheck > 600000) {
                cachedWorldSizeMb = calculateDirectorySize(targetDir) / (1024 * 1024);
                lastWorldSizeCheck = System.currentTimeMillis();
            }

            this.liveSnapshot = new LiveMetricsSnapshot(playersOnline, maxPlayers, playerNames, 
                                                        tps, mspt, cpuProcess, 
                                                        jvmUsedMb, jvmMaxMb, diskUsedGb, 
                                                        diskTotalGb, diskFreeGb, cachedWorldSizeMb, "ok");
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to update live metrics", e);
        }
    }

    private long calculateDirectorySize(File directory) {
        long length = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) length += file.length();
                else length += calculateDirectorySize(file);
            }
        }
        return length;
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
                String customPath = DashboardConfig.get().custom_logo_path;
                InputStream is = null;
                File customFile = null;

                if (customPath != null && !customPath.trim().isEmpty()) {
                    customFile = new File(customPath);
                    if (customFile.exists() && customFile.isFile()) {
                        try {
                            is = new FileInputStream(customFile);
                        } catch (FileNotFoundException ignored) {}
                    }
                }

                if (is == null) {
                    is = getClass().getResourceAsStream("/web/server-logo.jpg");
                }

                if (is == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }

                try {
                    String contentType = "image/jpeg";
                    if (customFile != null && customFile.getName().toLowerCase().endsWith(".png")) {
                        contentType = "image/png";
                    }
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                } finally {
                    is.close();
                }
                return;
            }
            
            if (path.equals("/favicon.png") || path.equals("/favicon.ico")) {
                DashboardConfig config = DashboardConfig.get();
                String favPath = config.favicon_path;
                String logoPath = config.custom_logo_path;
                InputStream is = null;
                File targetFile = null;

                // 1. Try favicon_path
                if (favPath != null && !favPath.trim().isEmpty()) {
                    targetFile = new File(favPath);
                    if (targetFile.exists() && targetFile.isFile()) {
                        try { is = new FileInputStream(targetFile); } catch (FileNotFoundException ignored) {}
                    }
                }
                // 2. Fallback to custom_logo_path
                if (is == null && logoPath != null && !logoPath.trim().isEmpty()) {
                    targetFile = new File(logoPath);
                    if (targetFile.exists() && targetFile.isFile()) {
                        try { is = new FileInputStream(targetFile); } catch (FileNotFoundException ignored) {}
                    }
                }
                // 3. Fallback to default logo in JAR
                if (is == null) {
                    is = getClass().getResourceAsStream("/web/server-logo.jpg");
                }

                if (is == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }

                try {
                    String contentType = "image/png"; // Default
                    String name = (targetFile != null) ? targetFile.getName().toLowerCase() : "server-logo.jpg";
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) contentType = "image/jpeg";
                    else if (name.endsWith(".ico")) contentType = "image/x-icon";
                    
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                } finally {
                    is.close();
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
                
                // Read HTML and replace placeholders
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }
                
                String html = sb.toString();
                DashboardConfig config = DashboardConfig.get();
                html = html.replace("{{TAB_TITLE}}", config.tab_title != null ? config.tab_title : "Playtime Dashboard");
                html = html.replace("{{SERVER_NAME}}", config.server_name != null ? config.server_name : "MC Server");
                html = html.replace("{{DASHBOARD_TITLE}}", config.dashboard_title != null ? config.dashboard_title : "Player Activity");
                html = html.replace("{{DASHBOARD_DESCRIPTION}}", config.dashboard_description != null ? config.dashboard_description : "Session data");
                html = html.replace("{{ENABLE_DYNMAP}}", String.valueOf(config.enable_dynmap));
                html = html.replace("{{DYNMAP_URL}}", config.dynmap_url != null ? config.dynmap_url : "");
                html = html.replace("{{ENABLE_LIVE_TAB}}", String.valueOf(config.enable_live_tab));
                html = html.replace("{{LIVE_UPDATE_INTERVAL_MS}}", String.valueOf(config.live_update_interval_seconds * 1000));
                html = html.replace("{{LIVE_TAB_DISPLAY}}", config.enable_live_tab ? "block" : "none");
                
                FabricDashboardMod.LOGGER.info("Serving dashboard. Dynmap enabled: " + config.enable_dynmap + ", URL: " + config.dynmap_url);
                
                byte[] response = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
                exchange.getResponseHeaders().set("Pragma", "no-cache");
                exchange.getResponseHeaders().set("Expires", "0");
                exchange.sendResponseHeaders(200, response.length);
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
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

            // Always trigger a background fetch check (it will return quickly if fresh)
            headService.fetchIfNeeded(playerName);

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

    /** Serves cached full skin PNGs for the 3D viewer. Same-origin avoids CORS. */
    private static class SkinHandler implements HttpHandler {
        private final PlayerHeadService headService;

        public SkinHandler(PlayerHeadService headService) {
            this.headService = headService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String filename = path.substring("/skins/".length());
            if (!filename.endsWith(".png")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String playerName = filename.substring(0, filename.length() - 4);

            if (!playerName.matches("[\\w/\\-]+")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            File skinFile = headService.getFullSkinFile(playerName);

            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");

            // Always trigger a background fetch check
            headService.fetchIfNeeded(playerName);

            if (skinFile != null && skinFile.exists()) {
                exchange.sendResponseHeaders(200, skinFile.length());
                try (InputStream is = new FileInputStream(skinFile);
                     OutputStream os = exchange.getResponseBody()) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                // No cached full skin — return 404; frontend will show default model
                exchange.sendResponseHeaders(404, -1);
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

            Map<String, PlayerHeadService.PlayerMeta> metaMap = headService.getColorMap();
            byte[] json = GSON.toJson(metaMap).getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }
        }
    }

    private static class ApiHandler implements HttpHandler {
        private final File cacheFile;
        private static final Gson GSON = new Gson();

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
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
            
            if (!cacheFile.exists()) {
                String emptyJson = "{\"daily\":{},\"playerDailyRaw\":{},\"sessData\":{},\"hourly\":{}}";
                exchange.sendResponseHeaders(200, emptyJson.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(emptyJson.getBytes());
                }
                return;
            }

            try (Reader reader = new FileReader(cacheFile)) {
                JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
                List<String> ignored = DashboardConfig.get().ignored_players;

                if (ignored != null && !ignored.isEmpty()) {
                    Set<String> ignoreSet = new HashSet<>();
                    for (String s : ignored) ignoreSet.add(s.toLowerCase());

                    // Filter sessData
                    if (data.has("sessData")) {
                        JsonObject sess = data.getAsJsonObject("sessData");
                        List<String> toRemove = new ArrayList<>();
                        for (String p : sess.keySet()) {
                            if (ignoreSet.contains(p.toLowerCase())) toRemove.add(p);
                        }
                        for (String p : toRemove) sess.remove(p);
                    }

                    // Filter playerDailyRaw and collect for daily recalculation
                    Map<String, Double> newDaily = new HashMap<>();
                    if (data.has("playerDailyRaw")) {
                        JsonObject pdr = data.getAsJsonObject("playerDailyRaw");
                        for (String date : pdr.keySet()) {
                            JsonObject dayMap = pdr.getAsJsonObject(date);
                            List<String> toRemove = new ArrayList<>();
                            for (String p : dayMap.keySet()) {
                                if (ignoreSet.contains(p.toLowerCase())) toRemove.add(p);
                            }
                            for (String p : toRemove) dayMap.remove(p);
                            
                            double sumMinutes = 0;
                            for (String p : dayMap.keySet()) {
                                sumMinutes += dayMap.get(p).getAsDouble();
                            }
                            newDaily.put(date, Math.round((sumMinutes / 60.0) * 100.0) / 100.0);
                        }
                    }

                    // Filter hourly
                    if (data.has("hourly")) {
                        JsonObject hly = data.getAsJsonObject("hourly");
                        for (String date : hly.keySet()) {
                            JsonObject dayMap = hly.getAsJsonObject(date);
                            List<String> toRemove = new ArrayList<>();
                            for (String p : dayMap.keySet()) {
                                if (ignoreSet.contains(p.toLowerCase())) toRemove.add(p);
                            }
                            for (String p : toRemove) dayMap.remove(p);
                        }
                    }

                    // Overwrite daily totals with filtered ones
                    data.add("daily", GSON.toJsonTree(newDaily));
                    // Include the ignored list in the response for the frontend to be extra sure
                    data.add("ignored_players", GSON.toJsonTree(ignored));
                }

                byte[] json = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, json.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(json);
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to serve filtered API activity", e);
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    private static class LiveMetricsHandler implements HttpHandler {
        private final DashboardWebServer server;
        private static final Gson GSON = new Gson();

        public LiveMetricsHandler(DashboardWebServer server) {
            this.server = server;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            LiveMetricsSnapshot snapshot = server.liveSnapshot;
            if (snapshot == null) {
                snapshot = new LiveMetricsSnapshot(); // warming_up state
            }

            byte[] response = GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
            exchange.sendResponseHeaders(200, response.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
}
