package com.playtime.dashboard.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playtime.dashboard.FabricDashboardMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UuidCache {
    private static UuidCache instance;

    public static UuidCache getInstance() {
        if (instance == null) {
            instance = new UuidCache();
        }
        return instance;
    }

    /** Throttle window for {@link #refresh()}. Callers within this window are no-ops. */
    private static final long REFRESH_THROTTLE_NANOS = 30L * 1_000_000_000L;

    /** Bound for the network LRU caches to prevent unbounded growth from web clients. */
    private static final int NETWORK_LRU_CAPACITY = 1024;

    // Bulk-loaded snapshots (replaced atomically on refresh). Treated as immutable after swap.
    private volatile Map<String, UUID> nameToUuid = new HashMap<>();
    private volatile Map<UUID, String> uuidToName = new HashMap<>();

    // Runtime additions (online players, on-demand Mojang resolutions). Survive refresh.
    private final Map<String, UUID> runtimeNameToUuid = new ConcurrentHashMap<>();
    private final Map<UUID, String> runtimeUuidToName = new ConcurrentHashMap<>();

    // Bounded LRU caches for Mojang lookups to avoid unbounded growth from web clients.
    private final Map<UUID, String> networkResolved = Collections.synchronizedMap(
            new LinkedHashMap<UUID, String>(NETWORK_LRU_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
                    return size() > NETWORK_LRU_CAPACITY;
                }
            });
    private final Set<UUID> networkFailed = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>(NETWORK_LRU_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > NETWORK_LRU_CAPACITY;
                }
            }));

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Gson GSON = new Gson();

    private volatile long lastRefreshNanos = 0L;

    /** Throttled disk reload. Skips work if called within {@link #REFRESH_THROTTLE_NANOS}. */
    public void refresh() {
        long now = System.nanoTime();
        if (lastRefreshNanos != 0L && (now - lastRefreshNanos) < REFRESH_THROTTLE_NANOS) {
            return;
        }
        forceRefresh();
    }

    /** Bypasses the throttle. Use for explicit reload paths (server start, event snapshot). */
    public synchronized void forceRefresh() {
        Map<String, UUID> newNameToUuid = new HashMap<>();
        Map<UUID, String> newUuidToName = new HashMap<>();
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File cacheFile = new File(gameDir, "usercache.json");

        if (cacheFile.exists()) {
            try (FileReader reader = new FileReader(cacheFile)) {
                JsonArray array = GSON.fromJson(reader, JsonArray.class);
                if (array != null) {
                    for (JsonElement element : array) {
                        JsonObject obj = element.getAsJsonObject();
                        if (obj.has("name") && obj.has("uuid")) {
                            String name = obj.get("name").getAsString();
                            UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
                            newNameToUuid.put(name.toLowerCase(), uuid);
                            newUuidToName.put(uuid, name);
                        }
                    }
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to read usercache.json", e);
            }
        }

        File metaFile = new File(new File(gameDir, "dashboard_faces"), "meta.json");
        if (metaFile.exists()) {
            try (FileReader reader = new FileReader(metaFile)) {
                JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    String name = entry.getKey();
                    if (entry.getValue().isJsonObject()) {
                        JsonObject meta = entry.getValue().getAsJsonObject();
                        if (meta.has("uuid")) {
                            String uuidStr = meta.get("uuid").getAsString();
                            if (uuidStr != null && !uuidStr.isEmpty()) {
                                try {
                                    String dashed = uuidStr;
                                    if (dashed.length() == 32) {
                                        dashed = dashed.substring(0, 8) + "-" + dashed.substring(8, 12) + "-" + dashed.substring(12, 16) + "-" + dashed.substring(16, 20) + "-" + dashed.substring(20);
                                    }
                                    UUID uuid = UUID.fromString(dashed);
                                    if (!newUuidToName.containsKey(uuid)) {
                                        newUuidToName.put(uuid, name);
                                        newNameToUuid.put(name.toLowerCase(), uuid);
                                    }
                                } catch (Exception e) {
                                    FabricDashboardMod.LOGGER.warn("UuidCache: Failed to parse UUID '{}' for player '{}'", uuidStr, name);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to read dashboard_faces/meta.json in UuidCache", e);
            }
        }

        this.nameToUuid = newNameToUuid;
        this.uuidToName = newUuidToName;
        this.lastRefreshNanos = System.nanoTime();
    }

    /**
     * Direct insertion path for joining/known players, so the JOIN event handler doesn't have to
     * wait for the throttle window to expire before names resolve.
     */
    public void registerPlayer(UUID uuid, String name) {
        if (uuid == null || name == null || name.isEmpty()) return;
        runtimeUuidToName.put(uuid, name);
        runtimeNameToUuid.put(name.toLowerCase(), uuid);
    }

    public Optional<UUID> getUuid(String username) {
        if (username == null || username.isEmpty()) return Optional.empty();
        String key = username.toLowerCase();

        UUID uuid = runtimeNameToUuid.get(key);
        if (uuid != null) return Optional.of(uuid);
        uuid = nameToUuid.get(key);
        if (uuid != null) return Optional.of(uuid);

        FabricDashboardMod.LOGGER.info("UUID for '" + username + "' not found locally, trying Mojang API...");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("id")) {
                    String id = json.get("id").getAsString();
                    if (id.length() == 32) {
                        id = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
                    }
                    UUID resolvedUuid = UUID.fromString(id);
                    FabricDashboardMod.LOGGER.info("Successfully resolved '" + username + "' to " + resolvedUuid + " via Mojang");
                    runtimeUuidToName.put(resolvedUuid, username);
                    runtimeNameToUuid.put(key, resolvedUuid);
                    return Optional.of(resolvedUuid);
                }
            } else {
                FabricDashboardMod.LOGGER.warn("Mojang API returned status " + response.statusCode() + " for '" + username + "'");
            }
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Mojang API lookup failed for '" + username + "': " + e.getMessage());
        }

        return Optional.empty();
    }

    public Optional<String> getUsername(UUID uuid) {
        if (uuid == null) return Optional.empty();

        String name = runtimeUuidToName.get(uuid);
        if (name != null) return Optional.of(name);

        name = uuidToName.get(uuid);
        if (name != null) return Optional.of(name);

        name = networkResolved.get(uuid);
        if (name != null) return Optional.of(name);

        if (networkFailed.contains(uuid)) return Optional.empty();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "")))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("name")) {
                    String resolvedName = json.get("name").getAsString();
                    networkResolved.put(uuid, resolvedName);
                    return Optional.of(resolvedName);
                }
            } else if (response.statusCode() == 429) {
                return Optional.empty();
            }
        } catch (Exception e) {
            // Network failure or rate limit, fall through.
        }

        networkFailed.add(uuid);
        return Optional.empty();
    }

    public java.util.Set<String> getAllKnownUuids() {
        java.util.Set<String> uuids = new java.util.HashSet<>();
        for (UUID u : uuidToName.keySet()) uuids.add(u.toString());
        for (UUID u : runtimeUuidToName.keySet()) uuids.add(u.toString());
        for (UUID u : networkResolved.keySet()) uuids.add(u.toString());
        for (UUID u : nameToUuid.values()) uuids.add(u.toString());
        for (UUID u : runtimeNameToUuid.values()) uuids.add(u.toString());
        return uuids;
    }
}
