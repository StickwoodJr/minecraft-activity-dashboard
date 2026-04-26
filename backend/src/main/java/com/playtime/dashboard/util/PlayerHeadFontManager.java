package com.playtime.dashboard.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.playtime.dashboard.FabricDashboardMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PlayerHeadFontManager {
    private static final int BASE_CODEPOINT = 0xE000;
    private static final Map<String, Integer> playerSlots = new ConcurrentHashMap<>();
    private static int nextSlot = 0;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static void loadSlots() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File facesDir = new File(gameDir, "dashboard_faces");
        File slotsFile = new File(facesDir, "slots.json");
        if (slotsFile.exists()) {
            try (FileReader reader = new FileReader(slotsFile)) {
                JsonObject json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                for (String key : json.keySet()) {
                    int slot = json.get(key).getAsInt();
                    playerSlots.put(key, slot);
                    if (slot >= nextSlot) {
                        nextSlot = slot + 1;
                    }
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to load slots.json", e);
            }
        }
    }

    private static void saveSlots() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File facesDir = new File(gameDir, "dashboard_faces");
        facesDir.mkdirs();
        File slotsFile = new File(facesDir, "slots.json");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(slotsFile), java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, Integer> entry : playerSlots.entrySet()) {
                json.addProperty(entry.getKey(), entry.getValue());
            }
            GSON.toJson(json, writer);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to save slots.json", e);
        }
    }

    public static String getHeadGlyph(String playerName) {
        if (playerSlots.isEmpty() && nextSlot == 0) {
            loadSlots();
        }

        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File facesDir = new File(gameDir, "dashboard_faces");
        String sanitized = sanitizeFilename(playerName);
        File faceFile = new File(facesDir, sanitized + ".png");

        if (!faceFile.exists()) {
            FabricDashboardMod.LOGGER.debug("No face PNG found for {} at {}, returning empty glyph.", playerName, faceFile.getAbsolutePath());
            return "";
        }

        boolean[] isNew = {false};
        int slot = playerSlots.computeIfAbsent(playerName, k -> {
            synchronized (PlayerHeadFontManager.class) {
                isNew[0] = true;
                return nextSlot++;
            }
        });
        
        if (isNew[0]) {
            FabricDashboardMod.LOGGER.info("Assigned new font slot {} (charCode={}) to {}", slot, Integer.toHexString(BASE_CODEPOINT + slot), playerName);
            saveSlots();
        }

        return String.valueOf((char) (BASE_CODEPOINT + slot));
    }

    public static void registerKnownPlayers(Iterable<String> onlinePlayers) {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File facesDir = new File(gameDir, "dashboard_faces");
        File metaFile = new File(facesDir, "meta.json");

        if (onlinePlayers != null) {
            for (String name : onlinePlayers) {
                getHeadGlyph(name);
            }
        }

        if (metaFile.exists()) {
            try (FileReader reader = new FileReader(metaFile)) {
                JsonObject meta = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                for (String playerName : meta.keySet()) {
                    getHeadGlyph(playerName);
                }
            } catch (Exception e) {
                FabricDashboardMod.LOGGER.error("Failed to parse meta.json in registerKnownPlayers", e);
            }
        }
    }

    public static void buildRespack() {
        try {
            File gameDir = FabricLoader.getInstance().getGameDir().toFile();
            File respackDir = new File(gameDir, "dashboard_respack");
            
            // Clean existing directory
            if (respackDir.exists()) {
                deleteDirectory(respackDir.toPath());
            }

            File texturesDir = new File(respackDir, "assets/dashboard/textures/font/heads");
            File fontDir = new File(respackDir, "assets/dashboard/font");
            
            texturesDir.mkdirs();
            fontDir.mkdirs();

            File facesDir = new File(gameDir, "dashboard_faces");
            JsonArray providers = new JsonArray();

            for (Map.Entry<String, Integer> entry : playerSlots.entrySet()) {
                String playerName = entry.getKey();
                int slot = entry.getValue();
                String sanitized = sanitizeFilename(playerName);
                String respackAssetName = sanitized.toLowerCase();
                
                File sourceFile = new File(facesDir, sanitized + ".png");
                if (sourceFile.exists()) {
                    File destFile = new File(texturesDir, respackAssetName + ".png");
                    Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    
                    JsonObject provider = new JsonObject();
                    provider.addProperty("type", "bitmap");
                    provider.addProperty("file", "dashboard:font/heads/" + respackAssetName + ".png");
                    provider.addProperty("ascent", 8);
                    provider.addProperty("height", 9);
                    JsonArray chars = new JsonArray();
                    chars.add(String.valueOf((char) (BASE_CODEPOINT + slot)));
                    provider.add("chars", chars);
                    providers.add(provider);
                    FabricDashboardMod.LOGGER.debug("Added font provider for {}: file={}, charCode={}", playerName, respackAssetName + ".png", Integer.toHexString(BASE_CODEPOINT + slot));
                } else {
                    FabricDashboardMod.LOGGER.warn("Face PNG not found for {} at {}", playerName, sourceFile.getAbsolutePath());
                }
            }

            JsonObject fontJson = new JsonObject();
            fontJson.add("providers", providers);

            File headsJsonFile = new File(fontDir, "heads.json");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(headsJsonFile), java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(fontJson, writer);
            }

            File packMcmetaFile = new File(respackDir, "pack.mcmeta");
            JsonObject packMcmeta = new JsonObject();
            JsonObject packInfo = new JsonObject();
            packInfo.addProperty("pack_format", 34);
            packInfo.addProperty("description", "Dashboard Player Heads");
            packMcmeta.add("pack", packInfo);
            
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(packMcmetaFile), java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(packMcmeta, writer);
            }
            
            FabricDashboardMod.LOGGER.info("Successfully built dashboard_respack directory.");
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to build resource pack", e);
        }
    }

    public static void zipRespack() {
        try {
            File gameDir = FabricLoader.getInstance().getGameDir().toFile();
            File respackDir = new File(gameDir, "dashboard_respack");
            File zipFile = new File(gameDir, "dashboard_respack.zip");
            
            if (!respackDir.exists()) {
                return;
            }

            try (FileOutputStream fos = new FileOutputStream(zipFile);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                
                Path sourcePath = respackDir.toPath();
                java.util.List<Path> paths = new java.util.ArrayList<>();
                Files.walkFileTree(sourcePath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        paths.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
                
                // Sort paths to ensure deterministic order
                paths.sort(java.util.Comparator.comparing(Path::toString));
                
                for (Path file : paths) {
                    String zipEntryName = sourcePath.relativize(file).toString().replace('\\', '/');
                    ZipEntry entry = new ZipEntry(zipEntryName);
                    // Force a constant timestamp so the ZIP hash doesn't change arbitrarily
                    entry.setTime(0);
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                }
            }
            
            String sha1 = calculateSHA1(zipFile);
            FabricDashboardMod.LOGGER.info("Successfully created dashboard_respack.zip! SHA-1: " + sha1);
            updateServerPropertiesHash(gameDir, sha1);
        } catch (Exception e) {
            FabricDashboardMod.LOGGER.error("Failed to zip resource pack", e);
        }
    }

    private static void updateServerPropertiesHash(File gameDir, String newHash) {
        File serverProps = new File(gameDir, "server.properties");
        if (!serverProps.exists()) {
            return;
        }

        try {
            java.util.List<String> lines = Files.readAllLines(serverProps.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            boolean foundHash = false;
            boolean foundUrl = false;
            String targetUrl = com.playtime.dashboard.config.DashboardConfig.get().resource_pack_url;

            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("resource-pack-sha1=")) {
                    lines.set(i, "resource-pack-sha1=" + newHash);
                    foundHash = true;
                } else if (targetUrl != null && !targetUrl.trim().isEmpty() && lines.get(i).startsWith("resource-pack=")) {
                    lines.set(i, "resource-pack=" + targetUrl);
                    foundUrl = true;
                }
            }
            if (!foundHash) {
                lines.add("resource-pack-sha1=" + newHash);
            }
            if (!foundUrl && targetUrl != null && !targetUrl.trim().isEmpty()) {
                lines.add("resource-pack=" + targetUrl);
            }
            Files.write(serverProps.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            FabricDashboardMod.LOGGER.info("Automagically updated server.properties with the new resource-pack-sha1: " + newHash);
            if (targetUrl != null && !targetUrl.trim().isEmpty()) {
                FabricDashboardMod.LOGGER.info("Automagically updated server.properties with resource-pack=" + targetUrl);
            }
            FabricDashboardMod.LOGGER.info("Note: You may need to restart the server for Minecraft to load the new hash from server.properties.");
        } catch (IOException e) {
            FabricDashboardMod.LOGGER.error("Failed to update server.properties", e);
        }
    }

    private static String calculateSHA1(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        try (java.io.InputStream is = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void deleteDirectory(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String sanitizeFilename(String playerName) {
        if (playerName == null) return "unknown";
        return playerName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
