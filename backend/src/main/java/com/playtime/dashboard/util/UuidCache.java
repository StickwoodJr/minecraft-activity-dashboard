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
        return Optional.ofNullable(nameToUuid.get(username.toLowerCase()));
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
