package com.playtime.dashboard.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class UuidCache {
    private static UuidCache instance;
    private final File facesDir;
    private final File metaFile;
    private final File userCacheFile;
    private final Map<String, UUID> nameToUuid = new ConcurrentHashMap<>();
    private final Map<UUID, String> uuidToName = new ConcurrentHashMap<>();
    private final Map<String, Long> networkFailedNames = new ConcurrentHashMap<>();
    private final Map<String, UUID> runtimeNameToUuid = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    private UuidCache() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        this.facesDir = new File(gameDir, "dashboard_faces");
        this.metaFile = new File(facesDir, "meta.json");
        this.userCacheFile = new File(gameDir, "usercache.json");
        refresh();
    }

    public static UuidCache getInstance() {
        if (instance == null) {
            instance = new UuidCache();
        }
        return instance;
    }

    public void forceRefresh() {
        refresh();
    }

    public void refresh() {
        nameToUuid.clear();
        uuidToName.clear();

        // 1. Load from meta.json (historical data)
        if (metaFile.exists()) {
            try (FileReader reader = new FileReader(metaFile)) {
                JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    String name = entry.getKey();
                    // We only care about name-to-UUID mappings if they exist elsewhere.
                    // This is actually just a source of names, we'll get UUIDs from usercache or Mojang.
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.warn("Failed to read meta.json: " + e.getMessage());
            }
        }

        // 2. Load from usercache.json (standard Minecraft cache)
        if (userCacheFile.exists()) {
            try (FileReader reader = new FileReader(userCacheFile)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray()) {
                        JsonObject obj = el.getAsJsonObject();
                        if (obj.has("name") && obj.has("uuid")) {
                            String name = obj.get("name").getAsString().toLowerCase();
                            UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
                            nameToUuid.put(name, uuid);
                            uuidToName.put(uuid, name);
                        }
                    }
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.warn("Failed to read usercache.json: " + e.getMessage());
            }
        }
        
        // 3. Re-inject runtime discoveries
        nameToUuid.putAll(runtimeNameToUuid);
        for (Map.Entry<String, UUID> e : runtimeNameToUuid.entrySet()) {
            uuidToName.put(e.getValue(), e.getKey());
        }
    }

    public Optional<UUID> getUuid(String username) {
        if (username == null || username.isEmpty()) return Optional.empty();
        String key = username.toLowerCase();
        
        UUID uuid = nameToUuid.get(key);
        if (uuid != null) return Optional.of(uuid);

        FabricDashboardMod.LOGGER.info("UUID for '{}' not found locally, trying Mojang API... (at {})", username, 
                java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                    .filter(st -> !st.getClassName().contains("UuidCache") && !st.getClassName().contains("Thread"))
                    .findFirst()
                    .map(st -> st.getClassName() + ":" + st.getLineNumber())
                    .orElse("unknown"));
        
        long now = System.currentTimeMillis();
        Long lastAttempt = networkFailedNames.get(key);
        int cooldown = DashboardConfig.get().uuid_refresh_cooldown_seconds;
        if (lastAttempt != null && (now - lastAttempt) < (cooldown * 1000L)) {
            FabricDashboardMod.LOGGER.info("Skipping Mojang lookup for '" + username + "' (throttled). Last attempt: " + ((now - lastAttempt)/1000) + "s ago");
            return Optional.empty();
        }

        try {
            String url = "https://api.mojang.com/users/profiles/minecraft/" + username;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String idStr = json.get("id").getAsString();
                // Add dashes
                String uuidWithDashes = idStr.replaceFirst("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]+)", "$1-$2-$3-$4-$5");
                UUID resolvedUuid = UUID.fromString(uuidWithDashes);
                
                nameToUuid.put(key, resolvedUuid);
                uuidToName.put(resolvedUuid, key);
                runtimeNameToUuid.put(key, resolvedUuid);
                return Optional.of(resolvedUuid);
            } else {
                FabricDashboardMod.LOGGER.warn("Mojang API returned status " + response.statusCode() + " for '" + username + "'");
                networkFailedNames.put(key, now);
            }
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Mojang API lookup failed for " + username, e);
            networkFailedNames.put(key, now);
        }

        return Optional.empty();
    }

    public Optional<String> getUsername(UUID uuid) {
        if (uuid == null) return Optional.empty();
        String name = uuidToName.get(uuid);
        return Optional.ofNullable(name);
    }

    public boolean isCachedOnDisk(String uuidStr) {
        // Standard usercache check
        return nameToUuid.values().stream().anyMatch(u -> u.toString().equalsIgnoreCase(uuidStr));
    }

    public boolean isCachedInRuntime(String uuidStr) {
        return runtimeNameToUuid.values().stream().anyMatch(u -> u.toString().equalsIgnoreCase(uuidStr));
    }

    public String getLastAttemptTimestamp(String uuidStr) {
        // Find name for this UUID
        String name = uuidToName.get(UUID.fromString(uuidStr));
        if (name == null) return "Never";
        Long ts = networkFailedNames.get(name.toLowerCase());
        return ts == null ? "Never" : new java.util.Date(ts).toString();
    }

    public boolean isThrottled(String uuidStr) {
        String name = uuidToName.get(UUID.fromString(uuidStr));
        if (name == null) return false;
        Long ts = networkFailedNames.get(name.toLowerCase());
        if (ts == null) return false;
        return (System.currentTimeMillis() - ts) < (DashboardConfig.get().uuid_refresh_cooldown_seconds * 1000L);
    }
}
