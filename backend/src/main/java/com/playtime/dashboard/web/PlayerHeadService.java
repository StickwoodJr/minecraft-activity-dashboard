package com.playtime.dashboard.web;

import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.util.UuidCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Base64;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

public class PlayerHeadService {
    private static final String FACES_DIR_NAME = "dashboard_faces";
    private static final String META_FILE_NAME = "meta.json";
    private static final int FETCH_DELAY_MS = 2000; // Rate limit safety
    private static final long FAILED_FETCH_TTL_MS = 30 * 60_000L; // 30 mins

    private final File facesDir;
    private final File metaFile;
    private final Map<String, PlayerMeta> metaMap = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayerHeadFetcher");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private final byte[] defaultHeadBytes;

    public static class PlayerMeta {
        public String lastSkinUrl;
        public long lastFetchedEpoch;
        public boolean fetchFailed;
    }

    public PlayerHeadService(File gameDir) {
        this.facesDir = new File(gameDir, FACES_DIR_NAME);
        if (!facesDir.exists()) facesDir.mkdirs();
        this.metaFile = new File(facesDir, META_FILE_NAME);

        byte[] defaultBytes = new byte[0];
        try (InputStream is = getClass().getResourceAsStream("/assets/dashboard/default_head.png")) {
            if (is != null) {
                defaultBytes = is.readAllBytes();
            }
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to load default_head.png from resources", e);
        }
        this.defaultHeadBytes = defaultBytes;

        loadMeta();
    }

    // \u2500\u2500 Public API \u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501

    /** Kick off background fetches for a collection of known player names. */
    public void fetchAllKnown(Collection<String> playerNames) {
        if (!DashboardConfig.get().fetch_player_heads) return;
        for (String name : playerNames) {
            fetchIfNeeded(name);
        }
    }

    /** Schedule a single player fetch if stale or missing. Deduplicated via inFlight set. */
    public void fetchIfNeeded(String playerName) {
        if (!DashboardConfig.get().fetch_player_heads) return;
        if (DashboardConfig.get().isPlayerIgnored(playerName)) return;
        PlayerMeta existing = metaMap.get(playerName);
        if (existing != null) {
            // Force re-fetch if full skin file is missing (new feature migration)
            File skinFile = new File(facesDir, sanitizeFilename(playerName) + "_skin.png");
            boolean needsSkinFile = !skinFile.exists();

            if (needsSkinFile) {
                FabricDashboardMod.LOGGER.info("Forcing re-fetch for " + playerName + " because full skin is missing.");
            } else {
                long ttlMs = existing.fetchFailed
                        ? FAILED_FETCH_TTL_MS
                        : DashboardConfig.get().skin_refresh_hours * 3600_000L;
                if ((System.currentTimeMillis() - existing.lastFetchedEpoch) < ttlMs) {
                    return; // still fresh (or still within retry cooldown)
                }
            }
        }

        // Deduplicate: don't queue if already in-flight
        if (!inFlight.add(playerName)) return;

        executor.submit(() -> {
            try {
                // Rate-limit delay between API calls
                Thread.sleep(FETCH_DELAY_MS);
                fetchPlayer(playerName);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Background fetch failed for " + playerName, e);
            } finally {
                inFlight.remove(playerName);
            }
        });
    }

    public byte[] getFaceBytes(String playerName) {
        File file = new File(facesDir, sanitizeFilename(playerName) + ".png");
        if (file.exists()) {
            try {
                return java.nio.file.Files.readAllBytes(file.toPath());
            } catch (IOException ignored) {}
        }
        return defaultHeadBytes;
    }

    public byte[] getSkinBytes(String playerName) {
        File file = new File(facesDir, sanitizeFilename(playerName) + "_skin.png");
        if (file.exists()) {
            try {
                return java.nio.file.Files.readAllBytes(file.toPath());
            } catch (IOException ignored) {}
        }
        // If skin missing, return default head as a fallback
        return defaultHeadBytes;
    }

    public void clearCache() {
        metaMap.clear();
        saveMeta();
        FabricDashboardMod.LOGGER.info("PlayerHeadService cache cleared.");
    }

    // \u2500\u2500 Internal Logic \u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501

    private void fetchPlayer(String playerName) {
        try {
            FabricDashboardMod.LOGGER.info("Fetching player head for: " + playerName);
            String uuid = resolveUUID(playerName);
            if (uuid == null) {
                FabricDashboardMod.LOGGER.warn("Could not resolve UUID for " + playerName + ", using default head.");
                writeDefaultHead(playerName);
                return;
            }

            String skinUrl = fetchSkinUrl(uuid);
            if (skinUrl == null) {
                FabricDashboardMod.LOGGER.warn("Could not find skin URL for " + playerName + " (" + uuid + ")");
                writeDefaultHead(playerName);
                return;
            }

            // Download and process
            byte[] skinData = downloadBytes(skinUrl);
            fetchAndCropFace(playerName, skinData);

            // Success
            PlayerMeta meta = new PlayerMeta();
            meta.lastSkinUrl = skinUrl;
            meta.lastFetchedEpoch = System.currentTimeMillis();
            meta.fetchFailed = false;
            metaMap.put(playerName, meta);
            saveMeta();
            FabricDashboardMod.LOGGER.info("Successfully fetched and cached head for: " + playerName);

        } catch (Exception e) {
            FabricDashboardMod.LOGGER.warn("Failed to fetch player head for " + playerName + ": " + e.getMessage());
            PlayerMeta meta = new PlayerMeta();
            meta.lastFetchedEpoch = System.currentTimeMillis();
            meta.fetchFailed = true;
            metaMap.put(playerName, meta);
            saveMeta();
        }
    }

    private String resolveUUID(String playerName) throws Exception {
        return DashboardConfig.get().resolvePrimaryUuid(playerName)
                .map(java.util.UUID::toString)
                .map(s -> s.replace("-", ""))
                .orElse(null);
    }

    private String fetchSkinUrl(String uuid) throws Exception {
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("properties")) return null;

        for (JsonElement prop : json.getAsJsonArray("properties")) {
            JsonObject obj = prop.getAsJsonObject();
            if ("textures".equals(obj.get("name").getAsString())) {
                String base64 = obj.get("value").getAsString();
                String decoded = new String(Base64.getDecoder().decode(base64));
                JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject();
                return textures.getAsJsonObject("textures")
                        .getAsJsonObject("SKIN")
                        .get("url").getAsString();
            }
        }
        return null;
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private void fetchAndCropFace(String playerName, byte[] skinData) throws IOException {
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(skinData);
        BufferedImage skin = ImageIO.read(bais);
        if (skin == null) throw new IOException("Invalid skin image data");

        // Save full skin
        File skinFile = new File(facesDir, sanitizeFilename(playerName) + "_skin.png");
        ImageIO.write(skin, "png", skinFile);

        // Crop face (8,8, 8x8)
        BufferedImage face = skin.getSubimage(8, 8, 8, 8);
        
        // Try to overlay outer layer (40,8, 8x8) if it exists
        // Classic skins are 64x32, modern are 64x64. Both have the hat layer at 40,8.
        try {
            BufferedImage hat = skin.getSubimage(40, 8, 8, 8);
            // Check if hat layer is not just transparent
            boolean hasHat = false;
            outer: for (int x=0; x<8; x++) {
                for (int y=0; y<8; y++) {
                    if ((hat.getRGB(x, y) >> 24) != 0x00) {
                        hasHat = true;
                        break outer;
                    }
                }
            }
            if (hasHat) {
                BufferedImage combined = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = combined.createGraphics();
                g.drawImage(face, 0, 0, null);
                g.drawImage(hat, 0, 0, null);
                g.dispose();
                face = combined;
            }
        } catch (Exception ignored) {}

        File faceFile = new File(facesDir, sanitizeFilename(playerName) + ".png");
        ImageIO.write(face, "png", faceFile);
    }

    private void writeDefaultHead(String playerName) throws IOException {
        File faceFile = new File(facesDir, sanitizeFilename(playerName) + ".png");
        try (FileOutputStream fos = new FileOutputStream(faceFile)) {
            fos.write(defaultHeadBytes);
        }
        // Also write default for skin (optional, but prevents repeat 404 logs)
        File skinFile = new File(facesDir, sanitizeFilename(playerName) + "_skin.png");
        try (FileOutputStream fos = new FileOutputStream(skinFile)) {
            fos.write(defaultHeadBytes);
        }
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-/]", "_");
    }

    private void loadMeta() {
        if (!metaFile.exists()) return;
        try (java.io.FileReader reader = new java.io.FileReader(metaFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonObject obj = entry.getValue().getAsJsonObject();
                PlayerMeta meta = new PlayerMeta();
                meta.lastSkinUrl = obj.has("lastSkinUrl") ? obj.get("lastSkinUrl").getAsString() : null;
                meta.lastFetchedEpoch = obj.has("lastFetchedEpoch") ? obj.get("lastFetchedEpoch").getAsLong() : 0L;
                meta.fetchFailed = obj.has("fetchFailed") && obj.get("fetchFailed").getAsBoolean();
                metaMap.put(entry.getKey(), meta);
            }
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to load head meta: " + e.getMessage());
        }
    }

    private void saveMeta() {
        try (java.io.FileWriter writer = new java.io.FileWriter(metaFile)) {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, PlayerMeta> entry : metaMap.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("lastSkinUrl", entry.getValue().lastSkinUrl);
                obj.addProperty("lastFetchedEpoch", entry.getValue().lastFetchedEpoch);
                obj.addProperty("fetchFailed", entry.getValue().fetchFailed);
                json.add(entry.getKey(), obj);
            }
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to save head meta: " + e.getMessage());
        }
    }
}
