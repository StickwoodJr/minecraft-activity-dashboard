package com.playtime.dashboard.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.playtime.dashboard.FabricDashboardMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
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
    private volatile Map<String, UUID> nameToUuid = new HashMap<>();
    private volatile Map<UUID, String> uuidToName = new HashMap<>();
    private final Map<UUID, String> networkResolved = new ConcurrentHashMap<>();
    private final Set<UUID> networkFailed = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Gson GSON = new Gson();

    public void refresh() {
        Map<String, UUID> newNameToUuid = new HashMap<>();
        Map<UUID, String> newUuidToName = new HashMap<>();
        File cacheFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "usercache.json");

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

        this.nameToUuid = newNameToUuid;
        this.uuidToName = newUuidToName;
    }

    public Optional<UUID> getUuid(String username) {
        UUID uuid = nameToUuid.get(username.toLowerCase());
        if (uuid != null) return Optional.of(uuid);

        // Try Mojang API for Name -> UUID
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
                    // Mojang returns undashed UUIDs
                    if (id.length() == 32) {
                        id = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
                    }
                    UUID resolvedUuid = UUID.fromString(id);
                    FabricDashboardMod.LOGGER.info("Successfully resolved '" + username + "' to " + resolvedUuid + " via Mojang");
                    // Add to our transient cache so we don't spam Mojang
                    uuidToName.put(resolvedUuid, username);
                    nameToUuid.put(username.toLowerCase(), resolvedUuid);
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
        String name = uuidToName.get(uuid);
        if (name != null) return Optional.of(name);

        name = networkResolved.get(uuid);
        if (name != null) return Optional.of(name);

        if (networkFailed.contains(uuid)) return Optional.empty();

        // Fetch from Mojang
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
                // Rate limited, do not cache failure so it retries later
                return Optional.empty();
            }
        } catch (Exception e) {
            // Ignore, likely network issue or rate limit
        }

        networkFailed.add(uuid);
        return Optional.empty();
    }
}
