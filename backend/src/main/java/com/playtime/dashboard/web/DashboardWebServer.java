package com.playtime.dashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.parser.LogParser;
import com.playtime.dashboard.stats.StatsAggregator;
import com.playtime.dashboard.util.UuidCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

public class DashboardWebServer {
    private static DashboardWebServer instance;
    private HttpServer httpServer;
    private final MinecraftServer server;
    private final File cacheFile;
    private final LogParser logParser;
    private final StatsAggregator statsAggregator;
    private final PlayerHeadService headService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Dashboard-Worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean worldSizeWalking = new AtomicBoolean(false);
    private double cachedWorldSizeGb = 0.0;
    private long lastWorldSizeWalkTs = 0L;

    private DashboardWebServer(MinecraftServer server) {
        this.server = server;
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        this.cacheFile = new File(gameDir, "dashboard_cache.json");
        this.logParser = new LogParser(gameDir, cacheFile);
        this.statsAggregator = new StatsAggregator();
        this.headService = new PlayerHeadService(gameDir);
    }

    public static void start(MinecraftServer server) {
        if (instance != null) return;
        instance = new DashboardWebServer(server);
        try {
            instance.init();
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to start Dashboard Web Server: " + e.getMessage());
        }
    }

    public static DashboardWebServer getInstance() {
        return instance;
    }

    private void init() throws IOException {
        DashboardConfig config = DashboardConfig.get();
        httpServer = HttpServer.create(new InetSocketAddress(config.web_port), 0);
        
        httpServer.createContext("/", new StaticHandler());
        httpServer.createContext("/api/activity", new ApiHandler(this));
        httpServer.createContext("/api/stats", new StatsHandler(this));
        httpServer.createContext("/api/player/stats", new PlayerStatsHandler(this));
        httpServer.createContext("/api/player/advancements", new PlayerAdvancementsHandler(this));
        httpServer.createContext("/api/player/head", new PlayerHeadHandler(this));
        httpServer.createContext("/api/player/skin", new PlayerSkinHandler(this));
        httpServer.createContext("/api/performance", new PerformanceHandler(this));
        httpServer.createContext("/api/config", new ConfigHandler());

        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Dashboard-HTTP");
            t.setDaemon(true);
            return t;
        }));
        
        httpServer.start();
        FabricDashboardMod.LOGGER.info("Dashboard Web Server started on port " + config.web_port);

        // Schedule periodic log parsing
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                logParser.parseIncremental();
                triggerHeadFetches();
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Periodic log parse failed: " + e.getMessage());
            }
        }, 1, config.incremental_update_interval_minutes, TimeUnit.MINUTES);

        // Schedule periodic world size calculation
        scheduler.scheduleAtFixedRate(this::refreshWorldSize, 0, config.world_size_refresh_minutes, TimeUnit.MINUTES);
    }

    public void triggerReparse() {
        logParser.parseAll();
        triggerHeadFetches();
    }

    public PlayerHeadService getHeadService() {
        return headService;
    }

    private void refreshWorldSize() {
        if (worldSizeWalking.getAndSet(true)) return;
        try {
            FabricDashboardMod.LOGGER.info("Starting background world size calculation...");
            long start = System.currentTimeMillis();
            Path worldPath = server.getRunDirectory().toPath();
            
            // Container optimization: if we are in /home/container, walk that instead.
            if (worldPath.toAbsolutePath().toString().startsWith("/home/container")) {
                worldPath = Path.of("/home/container");
            }

            cachedWorldSizeGb = calculateFolderSizeGb(worldPath);
            lastWorldSizeWalkTs = System.currentTimeMillis();
            FabricDashboardMod.LOGGER.info("World size calculation complete: " + String.format("%.2f GB", cachedWorldSizeGb) + " (took " + (lastWorldSizeWalkTs - start) + "ms)");
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("World size calculation failed: " + e.getMessage());
        } finally {
            worldSizeWalking.set(false);
        }
    }

    private double calculateFolderSizeGb(Path path) throws IOException {
        long[] total = {0};
        try {
            java.nio.file.Files.walk(path, DashboardConfig.get().world_size_max_depth)
                .filter(p -> java.nio.file.Files.isRegularFile(p))
                .forEach(p -> {
                    try {
                        total[0] += java.nio.file.Files.size(p);
                    } catch (IOException ignored) {}
                });
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.warn("World size walk failed: " + e.getMessage());
        }
        return total[0] / (1024.0 * 1024.0 * 1024.0);
    }

    public double getCachedWorldSize() { return cachedWorldSizeGb; }
    public String getLastWorldSizeWalk() { return lastWorldSizeWalkTs == 0 ? "Never" : new java.util.Date(lastWorldSizeWalkTs).toString(); }
    public String getNextWorldSizeWalk() { 
        if (lastWorldSizeWalkTs == 0) return "Pending";
        return new java.util.Date(lastWorldSizeWalkTs + (DashboardConfig.get().world_size_refresh_minutes * 60000L)).toString(); 
    }
    public boolean isWorldSizeThreadActive() { return worldSizeWalking.get(); }
    public void logWorldSizeThreadId() {
        // This is a debug tool to verify thread isolation.
        FabricDashboardMod.LOGGER.info("[Dashboard-Debug] WorldSize background thread ID: " + Thread.currentThread().getId() + " (" + Thread.currentThread().getName() + ")");
    }

    /** Scans the cache file for all known player names and kicks off head fetches. */
    public void triggerHeadFetches() {
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
    }

    public MinecraftServer getServer() { return server; }
    public File getCacheFile() { return cacheFile; }
}
