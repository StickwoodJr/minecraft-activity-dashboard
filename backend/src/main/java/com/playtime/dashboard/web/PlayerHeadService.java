package com.playtime.dashboard.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Fetches Minecraft player skins from Mojang, crops the face region,
 * extracts a dominant color, and caches everything to disk.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Minimal memory — faces are written to disk immediately, never held in memory</li>
 *   <li>All network/IO on a single background thread — never blocks the server tick</li>
 *   <li>Persistent meta.json survives restarts</li>
 *   <li>24-hour TTL before re-fetching (configurable)</li>
 *   <li>Rate-limit safe: 500ms delay between Mojang API calls</li>
 * </ul>
 */
public class PlayerHeadService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type META_MAP_TYPE = new TypeToken<ConcurrentHashMap<String, PlayerMeta>>() {}.getType();

    /** Delay between consecutive Mojang API requests to stay under rate limits (~600 req/10 min). */
    private static final long FETCH_DELAY_MS = 500;

    /** Short TTL for failed fetches so they retry sooner (10 minutes). */
    private static final long FAILED_FETCH_TTL_MS = 10 * 60 * 1000L;

    private final File facesDir;
    private final File metaFile;
    private final ConcurrentHashMap<String, PlayerMeta> metaMap = new ConcurrentHashMap<>();
    /** Tracks player names currently queued or in-flight to prevent duplicate fetches. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayerHeadService");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Default Steve head bytes — loaded once from classpath at init, never re-read. */
    private final byte[] defaultHeadBytes;

    /** Lightweight metadata per player — the only thing kept in memory. */
    public static class PlayerMeta {
        public String uuid;
        public String colorHex = "#888888";
        public long lastFetchedEpoch;
        /** Set to true when the fetch failed (rate limit, network error). Uses shorter TTL for retry. */
        public transient boolean fetchFailed = false;
    }

    public PlayerHeadService(File gameDir) {
        this.facesDir = new File(gameDir, "dashboard_faces");
        this.metaFile = new File(facesDir, "meta.json");
        if (!facesDir.exists()) {
            facesDir.mkdirs();
        }

        // Load default head from classpath once at init
        byte[] defaultBytes = null;
        try (InputStream is = getClass().getResourceAsStream("/web/default_head.png")) {
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                defaultBytes = bos.toByteArray();
            } else {
                FabricDashboardMod.LOGGER.warn("default_head.png not found in resources at init");
            }
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to load default_head.png from resources", e);
        }
        this.defaultHeadBytes = defaultBytes;

        loadMeta();
    }

    // ── Public API ──────────────────────────────────────────────

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
        PlayerMeta existing = metaMap.get(playerName);
        if (existing != null) {
            long ttlMs = existing.fetchFailed
                    ? FAILED_FETCH_TTL_MS
                    : DashboardConfig.get().skin_refresh_hours * 3600_000L;
            if ((System.currentTimeMillis() - existing.lastFetchedEpoch) < ttlMs) {
                return; // still fresh (or still within retry cooldown)
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
                FabricDashboardMod.LOGGER.warn("Failed to fetch head for " + playerName + ": " + e.getMessage());
                // Mark as failed with short TTL so it retries in 10 minutes, not 24 hours
                markFetchFailed(playerName);
            } finally {
                inFlight.remove(playerName);
            }
        });
    }

    /** Returns the cached face PNG file for a player, or null if not yet fetched. */
    public File getFaceFile(String playerName) {
        File f = new File(facesDir, sanitizeFilename(playerName) + ".png");
        return f.exists() ? f : null;
    }

    /** Returns the default head bytes (loaded once from classpath). */
    public byte[] getDefaultHeadBytes() {
        return defaultHeadBytes;
    }

    /** Returns the full color map (playerName → hex color string). */
    public Map<String, String> getColorMap() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, PlayerMeta> entry : metaMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().colorHex);
        }
        return result;
    }

    /** Shutdown the background executor cleanly. */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ── Internal pipeline ───────────────────────────────────────

    private void fetchPlayer(String playerName) throws Exception {
        FabricDashboardMod.LOGGER.info("Fetching player head for: " + playerName);

        // Step 1: Username → UUID
        String uuid = resolveUUID(playerName);
        if (uuid == null) {
            FabricDashboardMod.LOGGER.warn("Could not resolve UUID for " + playerName + ", using default head.");
            writeDefaultHead(playerName);
            return;
        }

        // Step 2: UUID → skin URL (add delay between API calls)
        Thread.sleep(FETCH_DELAY_MS);
        String skinUrl = fetchSkinUrl(uuid);
        if (skinUrl == null) {
            FabricDashboardMod.LOGGER.warn("Could not resolve skin URL for " + playerName + " (UUID: " + uuid + "), using default head.");
            writeDefaultHead(playerName);
            return;
        }

        // Step 3: Download skin, crop face, extract color, save to disk
        fetchAndCropFace(skinUrl, playerName, uuid);

        FabricDashboardMod.LOGGER.info("Successfully fetched and cached head for: " + playerName);
    }

    private String resolveUUID(String playerName) throws Exception {
        String lookupName = playerName;
        if ("Advent/Hanger".equals(playerName)) {
            lookupName = "Advent";
        }
        String url = "https://api.mojang.com/users/profiles/minecraft/" + lookupName;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 429 = rate limited — throw so the caller marks it as failed (short TTL retry)
        if (response.statusCode() == 429) {
            throw new IOException("Mojang rate limit hit for " + playerName);
        }
        if (response.statusCode() != 200) return null;

        // Minimal JSON parsing with Gson
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("id")) {
            return json.get("id").getAsString();
        }
        return null;
    }

    private String fetchSkinUrl(String uuid) throws Exception {
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // 429 = rate limited
        if (response.statusCode() == 429) {
            throw new IOException("Mojang session API rate limit hit for UUID " + uuid);
        }
        if (response.statusCode() != 200) return null;

        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
        com.google.gson.JsonArray properties = root.getAsJsonArray("properties");
        if (properties == null) return null;

        for (com.google.gson.JsonElement prop : properties) {
            com.google.gson.JsonObject p = prop.getAsJsonObject();
            if ("textures".equals(p.get("name").getAsString())) {
                String decoded = new String(Base64.getDecoder().decode(p.get("value").getAsString()));
                com.google.gson.JsonObject texturesRoot = com.google.gson.JsonParser.parseString(decoded)
                        .getAsJsonObject();
                com.google.gson.JsonObject textures = texturesRoot.getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) {
                    return textures.getAsJsonObject("SKIN").get("url").getAsString();
                }
            }
        }
        return null;
    }

    private void fetchAndCropFace(String skinUrl, String playerName, String uuid) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(skinUrl))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            writeDefaultHead(playerName);
            return;
        }

        BufferedImage skin;
        try (InputStream is = response.body()) {
            skin = ImageIO.read(is);
        }
        if (skin == null) {
            writeDefaultHead(playerName);
            return;
        }

        // Base face layer: (8,8) 8×8
        BufferedImage face = skin.getSubimage(8, 8, 8, 8);

        // Hat/overlay layer: (40,8) 8×8 — composite on top
        BufferedImage hat = skin.getSubimage(40, 8, 8, 8);

        // Scale to 32×32 and composite
        BufferedImage result = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(face, 0, 0, 32, 32, null);
        g.drawImage(hat, 0, 0, 32, 32, null);
        g.dispose();

        // Extract dominant color from face pixels using quantized mode approach
        String colorHex = extractDominantColor(face);

        // Write face PNG to disk
        File outFile = new File(facesDir, sanitizeFilename(playerName) + ".png");
        ImageIO.write(result, "PNG", outFile);

        // Update meta
        PlayerMeta meta = new PlayerMeta();
        meta.uuid = uuid;
        meta.colorHex = colorHex;
        meta.lastFetchedEpoch = System.currentTimeMillis();
        meta.fetchFailed = false;
        metaMap.put(playerName, meta);
        saveMeta();

        // Let the images be GC'd immediately
        skin.flush();
        face.flush();
        hat.flush();
        result.flush();
    }

    /**
     * Extracts the most visually distinctive color from an 8×8 face image
     * using quantized mode-counting. This surfaces characteristic colors
     * (hair, clothing, accessories) rather than averaging to beige.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Quantize each pixel to 4-bit per channel (16 levels per channel)</li>
     *   <li>Skip transparent, near-black, near-white, and skin-tone pixels</li>
     *   <li>Count occurrences of each quantized color</li>
     *   <li>Return the most frequent color (the mode)</li>
     *   <li>Boost saturation for vibrancy</li>
     * </ol>
     */
    private String extractDominantColor(BufferedImage face) {
        // Map of quantized color key → {count, rSum, gSum, bSum}
        Map<Integer, int[]> buckets = new HashMap<>();

        for (int y = 0; y < face.getHeight(); y++) {
            for (int x = 0; x < face.getWidth(); x++) {
                int argb = face.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                // Skip transparent pixels
                if (a < 128) continue;
                // Skip near-black (shadow)
                if (r < 30 && g < 30 && b < 30) continue;
                // Skip near-white (highlight)
                if (r > 225 && g > 225 && b > 225) continue;

                // Skip skin tones: detect by checking if the pixel is in the
                // typical skin hue range (warm beige/tan) with low saturation.
                // This ensures we pick clothing/hair/accessory colors instead.
                float[] hsb = Color.RGBtoHSB(r, g, b, null);
                float hue = hsb[0] * 360f;
                boolean isSkinTone = (hue >= 15 && hue <= 45) && hsb[1] < 0.55f && hsb[2] > 0.4f;
                if (isSkinTone) continue;

                // Quantize to 4-bit per channel (16 levels)
                int rq = (r >> 4) & 0xF;
                int gq = (g >> 4) & 0xF;
                int bq = (b >> 4) & 0xF;
                int key = (rq << 8) | (gq << 4) | bq;

                int[] bucket = buckets.computeIfAbsent(key, k -> new int[4]);
                bucket[0]++; // count
                bucket[1] += r; // rSum
                bucket[2] += g; // gSum
                bucket[3] += b; // bSum
            }
        }

        if (buckets.isEmpty()) {
            // All pixels were skin-tone or filtered — fall back to averaging all valid pixels
            return extractFallbackAverage(face);
        }

        // Find the best bucket based on a combination of frequency (count), brightness, and saturation
        int[] bestBucket = null;
        float bestScore = -1f;
        
        for (int[] bucket : buckets.values()) {
            int rAvg = bucket[1] / bucket[0];
            int gAvg = bucket[2] / bucket[0];
            int bAvg = bucket[3] / bucket[0];
            
            float[] hsb = Color.RGBtoHSB(rAvg, gAvg, bAvg, null);
            // Score formula: count * (brightness bonus) * (saturation bonus)
            // Bright, saturated colors get up to a 2.25x multiplier over dark/dull colors
            float score = bucket[0] * (0.5f + hsb[2]) * (0.5f + hsb[1]);
            
            if (score > bestScore) {
                bestScore = score;
                bestBucket = bucket;
            }
        }

        // Average the actual RGB values in the winning bucket for accuracy
        int rAvg = bestBucket[1] / bestBucket[0];
        int gAvg = bestBucket[2] / bestBucket[0];
        int bAvg = bestBucket[3] / bestBucket[0];

        // Boost saturation for vibrant accent colors
        float[] hsb = Color.RGBtoHSB(rAvg, gAvg, bAvg, null);
        hsb[1] = Math.min(1.0f, hsb[1] * 1.4f); // 40% saturation boost
        hsb[2] = Math.min(1.0f, Math.max(0.35f, hsb[2])); // clamp brightness
        int boosted = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);

        return String.format("#%06x", boosted & 0xFFFFFF);
    }

    /**
     * Fallback when all non-skin pixels are filtered: average all visible pixels.
     * This handles skins that are entirely skin-colored (e.g. naked Steve).
     */
    private String extractFallbackAverage(BufferedImage face) {
        long rTotal = 0, gTotal = 0, bTotal = 0;
        int count = 0;

        for (int y = 0; y < face.getHeight(); y++) {
            for (int x = 0; x < face.getWidth(); x++) {
                int argb = face.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a < 128) continue;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (r < 30 && g < 30 && b < 30) continue;
                if (r > 225 && g > 225 && b > 225) continue;
                rTotal += r;
                gTotal += g;
                bTotal += b;
                count++;
            }
        }

        if (count == 0) return "#888888";

        int rAvg = (int) (rTotal / count);
        int gAvg = (int) (gTotal / count);
        int bAvg = (int) (bTotal / count);

        float[] hsb = Color.RGBtoHSB(rAvg, gAvg, bAvg, null);
        hsb[1] = Math.min(1.0f, hsb[1] * 1.3f);
        hsb[2] = Math.min(1.0f, Math.max(0.35f, hsb[2]));
        int boosted = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
        return String.format("#%06x", boosted & 0xFFFFFF);
    }

    /** Marks a player fetch as failed with a short TTL (10 min) so it retries sooner. */
    private void markFetchFailed(String playerName) {
        PlayerMeta meta = metaMap.computeIfAbsent(playerName, k -> new PlayerMeta());
        meta.fetchFailed = true;
        meta.lastFetchedEpoch = System.currentTimeMillis();
        // Don't write a default head to disk for rate-limit failures —
        // the FaceHandler will serve the in-memory default bytes directly.
    }

    private void writeDefaultHead(String playerName) {
        try {
            if (defaultHeadBytes == null) {
                FabricDashboardMod.LOGGER.warn("No default head bytes available for " + playerName);
                return;
            }
            File outFile = new File(facesDir, sanitizeFilename(playerName) + ".png");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(defaultHeadBytes);
            }

            // Set a default meta entry — successful (not a rate limit), full TTL
            PlayerMeta meta = metaMap.computeIfAbsent(playerName, k -> new PlayerMeta());
            meta.colorHex = "#888888";
            meta.lastFetchedEpoch = System.currentTimeMillis();
            meta.fetchFailed = false;
            saveMeta();
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to write default head for " + playerName, e);
        }
    }

    // ── Meta persistence ────────────────────────────────────────

    private void loadMeta() {
        if (!metaFile.exists()) return;
        try (Reader reader = new FileReader(metaFile)) {
            ConcurrentHashMap<String, PlayerMeta> loaded = GSON.fromJson(reader, META_MAP_TYPE);
            if (loaded != null) {
                metaMap.putAll(loaded);
            }
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to load player head meta", e);
        }
    }

    private synchronized void saveMeta() {
        try (Writer writer = new FileWriter(metaFile)) {
            GSON.toJson(metaMap, writer);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to save player head meta", e);
        }
    }

    private String sanitizeFilename(String playerName) {
        if (playerName == null) return "unknown";
        return playerName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
