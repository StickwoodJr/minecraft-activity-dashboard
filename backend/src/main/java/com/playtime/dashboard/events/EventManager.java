package com.playtime.dashboard.events;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.playtime.dashboard.FabricDashboardMod;
import com.playtime.dashboard.config.DashboardConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.*;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.scoreboard.number.FixedNumberFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class EventManager {
    private static final Gson GSON = new Gson();
    private static EventManager instance;

    private static final Set<String> OBSIDIAN_IDS = Set.of(
        "minecraft:obsidian",
        "minecraft:crying_obsidian",
        "betternether:blue_weeping_obsidian",
        "betternether:weeping_obsidian",
        "betternether:blue_crying_obsidian",
        "betternether:obsidian_bricks",
        "betternether:obsidian_bricks_stairs",
        "betternether:obsidian_bricks_slab",
        "betternether:obsidian_tile",
        "betternether:obsidian_tile_small",
        "betternether:obsidian_tile_stairs",
        "betternether:obsidian_tile_slab",
        "betternether:obsidian_rod_tiles",
        "betternether:obsidian_glass",
        "betternether:obsidian_glass_pane",
        "betternether:blue_obsidian",
        "betternether:blue_obsidian_bricks",
        "betternether:blue_obsidian_bricks_stairs",
        "betternether:blue_obsidian_bricks_slab",
        "betternether:blue_obsidian_tile",
        "betternether:blue_obsidian_tile_small",
        "betternether:blue_obsidian_tile_stairs",
        "betternether:blue_obsidian_tile_slab",
        "betternether:blue_obsidian_rod_tiles",
        "betternether:blue_obsidian_glass",
        "betternether:blue_obsidian_glass_pane"
    );
    private final File eventsFile;
    private MinecraftServer server;
    
    private List<ServerEvent> activeEvents = new ArrayList<>();
    private Map<String, String> playerPreferences = new HashMap<>(); // UUID -> eventId
    private Map<String, Integer> allTimePoints = new HashMap<>();
    private Map<String, Set<UUID>> syncedObjectives = new HashMap<>(); // objName -> set of player UUIDs
    private Set<String> hiddenScoreboards = new HashSet<>(); // UUIDs of players who hid the sidebar

    private static final Identifier HEADS_FONT_ID = Identifier.of("dashboard", "heads");
    private static final net.minecraft.text.Style HEADS_STYLE = net.minecraft.text.Style.EMPTY.withFont(HEADS_FONT_ID);

    /** Obsidian items/blocks resolved once after server start to avoid per-tick registry scans. */
    private final Set<Item> obsidianItems = new HashSet<>();
    private final Set<Block> obsidianBlocks = new HashSet<>();
    private volatile boolean obsidianResolved = false;

    /** Per-player cache of the last sent (objective, holder -> score) so we can skip unchanged updates. */
    private final Map<UUID, ScoreboardSendState> lastSent = new HashMap<>();

    private static final class ScoreboardSendState {
        String objectiveName;
        final Map<String, Integer> scores = new HashMap<>();
        final Map<String, String> formats = new HashMap<>();
    }

    private EventManager() {
        this.eventsFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "dashboard_events.json");
        load();
    }

    public static EventManager getInstance() {
        if (instance == null) {
            instance = new EventManager();
        }
        return instance;
    }

    public void init(MinecraftServer server) {
        this.server = server;
        com.playtime.dashboard.util.UuidCache.getInstance().forceRefresh();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            com.playtime.dashboard.util.UuidCache.getInstance()
                .registerPlayer(player.getUuid(), player.getGameProfile().getName());
        }
        resolveObsidianRegistry();
        updateScoreboard();
    }

    private void resolveObsidianRegistry() {
        if (obsidianResolved) return;
        obsidianItems.clear();
        obsidianBlocks.clear();
        for (Item item : Registries.ITEM) {
            if (OBSIDIAN_IDS.contains(Registries.ITEM.getId(item).toString())) obsidianItems.add(item);
        }
        for (Block block : Registries.BLOCK) {
            if (OBSIDIAN_IDS.contains(Registries.BLOCK.getId(block).toString())) obsidianBlocks.add(block);
        }
        obsidianResolved = true;
    }

    public List<ServerEvent> getActiveEvents() {
        if (activeEvents == null) activeEvents = new ArrayList<>();
        return activeEvents;
    }

    public ServerEvent getActiveEvent() {
        return activeEvents.isEmpty() ? null : activeEvents.get(0);
    }

    public Map<String, Integer> getAllTimePoints() {
        return allTimePoints;
    }

    public Map<String, String> resolveNames(Set<String> uuids) {
        Map<String, String> resolved = new HashMap<>();
        for (String uuidStr : uuids) {
            resolved.put(uuidStr, getPlayerName(uuidStr));
        }
        return resolved;
    }

    public void startEvent(String title, String type, int durationHours) {
        if (activeEvents.size() >= DashboardConfig.get().max_concurrent_events) {
            server.getPlayerManager().broadcast(Text.literal("§c[Event] Cannot start event: Max concurrent events reached (" + DashboardConfig.get().max_concurrent_events + ")"), false);
            return;
        }
        if (isActiveTitleInUse(title)) {
            server.getPlayerManager().broadcast(Text.literal("§c[Event] Cannot start event: An active event already uses that title."), false);
            return;
        }

        long now = System.currentTimeMillis();
        ServerEvent event = new ServerEvent(
            UUID.randomUUID().toString(),
            title,
            type,
            now,
            now + (long) durationHours * 60 * 60 * 1000
        );

        if (type.equals("fewest_deaths")) {
            event.lowerIsBetter = true;
        }

        activeEvents.add(event);
        takeStatsSnapshot(event);
        save();
        
        List<String> onlineNames = server.getPlayerManager().getPlayerList().stream()
            .map(p -> p.getGameProfile().getName())
            .collect(Collectors.toList());
        com.playtime.dashboard.util.PlayerHeadFontManager.registerKnownPlayers(onlineNames);
        
        new Thread(() -> {
            com.playtime.dashboard.util.PlayerHeadFontManager.buildRespack();
            com.playtime.dashboard.util.PlayerHeadFontManager.zipRespack();
        }, "Dashboard-Respack-Builder").start();

        updateScoreboard();
        
        server.getPlayerManager().broadcast(Text.literal("§6[Event] §aNew event started: §l" + title), false);
        server.getPlayerManager().broadcast(Text.literal("§6[Event] §eGoal: " + type.replace("_", " ") + " for " + durationHours + " hours!"), false);
    }

    public boolean isActiveTitleInUse(String title) {
        if (title == null) return false;
        String normalized = title.trim();
        return activeEvents.stream().anyMatch(e -> e.title != null && e.title.trim().equalsIgnoreCase(normalized));
    }

    public ServerEvent findActiveEventByInput(String input) {
        if (input == null) return null;
        String normalized = input.trim();
        if (normalized.isEmpty()) return null;

        ServerEvent byTitle = activeEvents.stream()
            .filter(e -> e.title != null && e.title.trim().equalsIgnoreCase(normalized))
            .findFirst()
            .orElse(null);
        if (byTitle != null) return byTitle;

        ServerEvent byExactId = activeEvents.stream()
            .filter(e -> e.id.equals(normalized))
            .findFirst()
            .orElse(null);
        if (byExactId != null) return byExactId;

        return activeEvents.stream()
            .filter(e -> e.id.startsWith(normalized))
            .findFirst()
            .orElse(null);
    }

    public void stopEvent() {
        if (!activeEvents.isEmpty()) {
            stopEvent(activeEvents.get(0).id);
        }
    }

    public void stopEvent(String eventId) {
        ServerEvent eventToStop = activeEvents.stream().filter(e -> e.id.equals(eventId)).findFirst().orElse(null);
        if (eventToStop == null) return;

        updateScores(eventToStop);
        distributePoints(eventToStop);
        broadcastEventResults(eventToStop);

        activeEvents.remove(eventToStop);

        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(getObjectiveName(eventToStop.id));
        if (objective != null) {
            scoreboard.removeObjective(objective);
        }

        lastSent.clear();
        save();
        updateScoreboard();
    }

    private void takeStatsSnapshot(ServerEvent event) {
        com.playtime.dashboard.util.UuidCache.getInstance().forceRefresh();
        Path statsDir = getStatsPath();
        File[] files = statsDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        Set<String> snapshotTaken = new HashSet<>();
        if (files != null) {
            for (File statFile : files) {
                String uuidStr = statFile.getName().replace(".json", "");
                event.initialStats.put(uuidStr, getStatValueFromDisk(statFile, event.type));
                snapshotTaken.add(uuidStr);
            }
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String uuidStr = player.getUuid().toString();
            event.initialStats.put(uuidStr, getStatValueFromPlayer(player, event.type));
            snapshotTaken.add(uuidStr);
        }
        
        Set<String> allKnown = com.playtime.dashboard.util.UuidCache.getInstance().getAllKnownUuids();
        for (String u : allKnown) {
            if (!snapshotTaken.contains(u)) {
                event.initialStats.put(u, 0);
            }
        }
    }

    private Path getStatsPath() {
        return server.getRunDirectory().resolve("world/stats");
    }

    public void tick() {
        if (activeEvents.isEmpty()) return;
        com.playtime.dashboard.util.UuidCache.getInstance().refresh();
        List<String> toStop = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ServerEvent event : activeEvents) {
            if (now > event.endTime) toStop.add(event.id);
            else updateScores(event);
        }
        for (String id : toStop) stopEvent(id);
        updateScoreboard();
    }

    private void updateScores(ServerEvent event) {
        com.playtime.dashboard.util.UuidCache.getInstance().refresh();
        Path statsDir = getStatsPath();
        File[] files = statsDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        Set<String> updated = new HashSet<>();
        if (files != null) {
            for (File statFile : files) {
                String uuidStr = statFile.getName().replace(".json", "");
                updatePlayerScore(event, uuidStr, getStatValueFromDisk(statFile, event.type));
                updated.add(uuidStr);
            }
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String uuidStr = player.getUuid().toString();
            updatePlayerScore(event, uuidStr, getStatValueFromPlayer(player, event.type));
            updated.add(uuidStr);
        }
        
        Set<String> allKnown = com.playtime.dashboard.util.UuidCache.getInstance().getAllKnownUuids();
        for (String u : allKnown) {
            if (!updated.contains(u)) {
                updatePlayerScore(event, u, 0);
            }
        }
    }

    private void updatePlayerScore(ServerEvent event, String uuidStr, int currentValue) {
        String name = getPlayerName(uuidStr);
        DashboardConfig cfg = DashboardConfig.get();
        if (cfg.getIgnoredLowerNames().contains(name.toLowerCase())) {
            event.currentScores.remove(uuidStr);
            return;
        }
        if (cfg.getIgnoredOfflineUuids().contains(uuidStr)) {
            event.currentScores.remove(uuidStr);
            return;
        }

        if (!event.initialStats.containsKey(uuidStr)) event.initialStats.put(uuidStr, currentValue);
        int score = currentValue - event.initialStats.get(uuidStr);
        
        if (event.type.equals("playtime")) score /= 20;
        else if (event.type.equals("damage_dealt")) score /= 10;
        else if (event.type.equals("daily_streak")) score = currentValue;
        
        if (score >= 0 || event.lowerIsBetter) event.currentScores.put(uuidStr, score);
    }

    private int getStatValueFromPlayer(ServerPlayerEntity player, String type) {
        switch (type) {
            case "playtime": return player.getStatHandler().getStat(Stats.CUSTOM, Stats.PLAY_TIME);
            case "mob_kills": return player.getStatHandler().getStat(Stats.CUSTOM, Stats.MOB_KILLS);
            case "fewest_deaths": return player.getStatHandler().getStat(Stats.CUSTOM, Stats.DEATHS);
            case "damage_dealt": return player.getStatHandler().getStat(Stats.CUSTOM, Stats.DAMAGE_DEALT);
            case "player_kills": return player.getStatHandler().getStat(Stats.CUSTOM, Stats.PLAYER_KILLS);
            case "fish_caught":
                int vFish = player.getStatHandler().getStat(Stats.CUSTOM, Stats.FISH_CAUGHT);
                return vFish + (FabricLoader.getInstance().isModLoaded("tide") ? sumTideFromPlayer(player) : 0);
            case "daily_streak": return StreakTracker.getInstance().getStreak(player.getUuid().toString());
            case "blocks_placed": return sumCategoryPlayer(player, Stats.USED, true);
            case "blocks_mined": return sumCategoryPlayer(player, Stats.MINED, false);
            case "obsidian_placed": return sumIdSetFromPlayer(player, true);
            case "obsidian_mined": return sumIdSetFromPlayer(player, false);
            default: return 0;
        }
    }

    private int sumIdSetFromPlayer(ServerPlayerEntity player, boolean placed) {
        if (!obsidianResolved) resolveObsidianRegistry();
        int sum = 0;
        if (placed) {
            for (Item item : obsidianItems) {
                sum += player.getStatHandler().getStat(Stats.USED, item);
            }
        } else {
            for (Block block : obsidianBlocks) {
                sum += player.getStatHandler().getStat(Stats.MINED, block);
            }
        }
        return sum;
    }

    private int sumCategoryPlayer(ServerPlayerEntity player, StatType<?> type, boolean blocksOnly) {
        int sum = 0;
        if (type == Stats.USED) {
            StatType<Item> itemType = (StatType<Item>) type;
            for (Item item : itemType.getRegistry()) {
                if (blocksOnly && !Registries.ITEM.getId(item).toString().contains(":")) continue; // basic filter
                sum += player.getStatHandler().getStat(itemType, item);
            }
        } else if (type == Stats.MINED) {
            StatType<Block> blockType = (StatType<Block>) type;
            for (Block block : blockType.getRegistry()) {
                sum += player.getStatHandler().getStat(blockType, block);
            }
        }
        return sum;
    }

    private static final java.util.function.Predicate<String> NAMESPACED_ID_FILTER = id -> id != null && id.contains(":");

    private int getStatValueFromDisk(File statFile, String type) {
        switch (type) {
            case "playtime": return readSingleStat(statFile, "minecraft:custom", "minecraft:play_time");
            case "mob_kills": return readSingleStat(statFile, "minecraft:custom", "minecraft:mob_kills");
            case "fewest_deaths": return readSingleStat(statFile, "minecraft:custom", "minecraft:deaths");
            case "damage_dealt": return readSingleStat(statFile, "minecraft:custom", "minecraft:damage_dealt");
            case "player_kills": return readSingleStat(statFile, "minecraft:custom", "minecraft:player_kills");
            case "fish_caught": return readFishCaughtFromDisk(statFile, FabricLoader.getInstance().isModLoaded("tide"));
            case "daily_streak": return StreakTracker.getInstance().getStreak(statFile.getName().replace(".json", ""));
            case "blocks_placed": return sumCategoryFiltered(statFile, "minecraft:used", NAMESPACED_ID_FILTER);
            case "blocks_mined": return sumCategoryFiltered(statFile, "minecraft:mined", null);
            case "obsidian_placed": return sumCategoryFiltered(statFile, "minecraft:used", OBSIDIAN_IDS::contains);
            case "obsidian_mined": return sumCategoryFiltered(statFile, "minecraft:mined", OBSIDIAN_IDS::contains);
            default: return 0;
        }
    }

    private int readSingleStat(File statFile, String category, String stat) {
        try (JsonReader r = new JsonReader(new FileReader(statFile))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!"stats".equals(r.nextName())) { r.skipValue(); continue; }
                r.beginObject();
                while (r.hasNext()) {
                    if (!category.equals(r.nextName())) { r.skipValue(); continue; }
                    r.beginObject();
                    while (r.hasNext()) {
                        if (stat.equals(r.nextName())) return (int) r.nextLong();
                        r.skipValue();
                    }
                    return 0;
                }
                return 0;
            }
            return 0;
        } catch (Exception e) { return 0; }
    }

    private int sumCategoryFiltered(File statFile, String category, java.util.function.Predicate<String> filter) {
        int sum = 0;
        try (JsonReader r = new JsonReader(new FileReader(statFile))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!"stats".equals(r.nextName())) { r.skipValue(); continue; }
                r.beginObject();
                while (r.hasNext()) {
                    if (!category.equals(r.nextName())) { r.skipValue(); continue; }
                    r.beginObject();
                    while (r.hasNext()) {
                        String id = r.nextName();
                        if (filter == null || filter.test(id)) sum += (int) r.nextLong();
                        else r.skipValue();
                    }
                    return sum;
                }
                return sum;
            }
            return sum;
        } catch (Exception e) { return 0; }
    }

    private int readFishCaughtFromDisk(File statFile, boolean includeTide) {
        int total = 0;
        try (JsonReader r = new JsonReader(new FileReader(statFile))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!"stats".equals(r.nextName())) { r.skipValue(); continue; }
                r.beginObject();
                while (r.hasNext()) {
                    String cat = r.nextName();
                    if ("minecraft:custom".equals(cat)) {
                        r.beginObject();
                        while (r.hasNext()) {
                            if ("minecraft:fish_caught".equals(r.nextName())) total += (int) r.nextLong();
                            else r.skipValue();
                        }
                        r.endObject();
                    } else if (includeTide && "minecraft:used".equals(cat)) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String id = r.nextName();
                            if (id.startsWith("tide:")) total += (int) r.nextLong();
                            else r.skipValue();
                        }
                        r.endObject();
                    } else {
                        r.skipValue();
                    }
                }
                return total;
            }
            return total;
        } catch (Exception e) { return 0; }
    }

    private int sumTideFromPlayer(ServerPlayerEntity player) {
        int sum = 0;
        for (Item item : Registries.ITEM) {
            if (Registries.ITEM.getId(item).getNamespace().equals("tide")) {
                sum += player.getStatHandler().getStat(Stats.USED, item);
            }
        }
        return sum;
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        syncedObjectives.values().forEach(set -> set.remove(uuid));
        lastSent.remove(uuid);
    }

    public void onPlayerLeave(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        syncedObjectives.values().forEach(set -> set.remove(uuid));
        lastSent.remove(uuid);
    }

    /** Drops the per-player diff cache so the next tick resends everything. */
    public void invalidateScoreboardCache() {
        lastSent.clear();
    }

    public void updateScoreboard() {
        if (server == null) return;
        Scoreboard scoreboard = server.getScoreboard();

        if (activeEvents.isEmpty()) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
            }
            return;
        }

        for (ServerEvent event : activeEvents) {
            String objName = getObjectiveName(event.id);
            if (scoreboard.getNullableObjective(objName) == null) {
                scoreboard.addObjective(objName, ScoreboardCriterion.DUMMY, Text.literal("§6§l" + event.title), ScoreboardCriterion.RenderType.INTEGER, false, null);
            }
            updateObjectiveContent(event, scoreboard.getNullableObjective(objName));
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID playerUuid = player.getUuid();
            String uuidStr = playerUuid.toString();
            if (hiddenScoreboards != null && hiddenScoreboards.contains(uuidStr)) {
                player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, null));
                lastSent.remove(playerUuid);
                continue;
            }
            String prefId = playerPreferences.get(uuidStr);
            ServerEvent displayEvent = activeEvents.stream().filter(e -> e.id.equals(prefId)).findFirst()
                    .orElse(activeEvents.get(0));

            ScoreboardObjective obj = scoreboard.getNullableObjective(getObjectiveName(displayEvent.id));
            if (obj == null) continue;

            ScoreboardSendState sendState = lastSent.computeIfAbsent(playerUuid, k -> new ScoreboardSendState());
            boolean objectiveChanged = !obj.getName().equals(sendState.objectiveName);
            if (objectiveChanged) {
                sendState.objectiveName = obj.getName();
                sendState.scores.clear();
                sendState.formats.clear();
            }

            Set<UUID> synced = syncedObjectives.computeIfAbsent(obj.getName(), k -> new HashSet<>());
            if (!synced.contains(playerUuid)) {
                player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket(obj, 0));
                synced.add(playerUuid);
            }

            // Sidebar display packet is cheap and ensures the player's view is correct on (re)join.
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, obj));

            long remainingMs = displayEvent.endTime - System.currentTimeMillis();
            int remainingSeconds = (int) Math.max(0, remainingMs / 1000);
            net.minecraft.text.MutableText timerText = Text.literal("§b§lTime Left: §r§f" + formatTime(remainingSeconds));
            player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                "_time_remaining_",
                obj.getName(),
                Integer.MAX_VALUE,
                Optional.of(timerText),
                Optional.of(BlankNumberFormat.INSTANCE)
            ));

            Map<String, Integer> scores = displayEvent.currentScores;

            for (Map.Entry<String, Integer> entry : scores.entrySet()) {
                String name = getPlayerName(entry.getKey());
                int val = entry.getValue();
                int internalScore = displayEvent.lowerIsBetter ? Integer.MAX_VALUE - val : val;
                String formatStr = formatScoreValue(displayEvent, val);

                Integer prevScore = sendState.scores.get(name);
                String prevFormat = sendState.formats.get(name);
                if (prevScore != null && prevScore == internalScore && formatStr.equals(prevFormat)) {
                    continue;
                }

                net.minecraft.text.MutableText text = Text.empty();
                String glyph = com.playtime.dashboard.util.PlayerHeadFontManager.getHeadGlyph(name);
                if (!glyph.isEmpty()) {
                    text.append(Text.literal(glyph).setStyle(HEADS_STYLE));
                }
                text.append(Text.literal(" §f" + name));

                player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                    name,
                    obj.getName(),
                    internalScore,
                    Optional.of(text),
                    Optional.of(new FixedNumberFormat(Text.literal("§e" + formatStr)))
                ));
                sendState.scores.put(name, internalScore);
                sendState.formats.put(name, formatStr);
            }
        }
    }

    private void updateObjectiveContent(ServerEvent event, ScoreboardObjective objective) {
        Scoreboard scoreboard = server.getScoreboard();
        event.currentScores.forEach((uuid, val) -> {
            String name = getPlayerName(uuid);
            ScoreHolder holder = ScoreHolder.fromName(name);
            var score = scoreboard.getOrCreateScore(holder, objective);

            net.minecraft.text.MutableText text = Text.empty();
            String glyph = com.playtime.dashboard.util.PlayerHeadFontManager.getHeadGlyph(name);
            if (!glyph.isEmpty()) {
                text.append(Text.literal(glyph).setStyle(HEADS_STYLE));
            }
            text.append(Text.literal(" §f" + name));

            score.setDisplayText(text);
            score.setNumberFormat(new FixedNumberFormat(Text.literal("§e" + formatScoreValue(event, val))));
            score.setScore(event.lowerIsBetter ? Integer.MAX_VALUE - val : val);
        });
    }

    private String getObjectiveName(String eventId) { return "ev_" + eventId.substring(0, Math.min(8, eventId.length())); }

    private String formatTime(int seconds) {
        int d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return (d > 0 ? d + "d " : "") + (h > 0 || d > 0 ? h + "h " : "") + (m > 0 || h > 0 || d > 0 ? m + "m " : "") + s + "s";
    }

    private String formatScoreValue(ServerEvent event, int val) {
        if (event.type.equals("playtime")) return formatTime(val);
        if (event.type.equals("damage_dealt")) return val + " ❤";
        return String.valueOf(val);
    }

    public void setPlayerScoreboardPreference(UUID uuid, String eventId) {
        playerPreferences.put(uuid.toString(), eventId);
        lastSent.remove(uuid);
        save();
        updateScoreboard();
    }

    public void setScoreboardHidden(UUID uuid, boolean hidden) {
        String uuidStr = uuid.toString();
        if (hidden) hiddenScoreboards.add(uuidStr);
        else hiddenScoreboards.remove(uuidStr);
        lastSent.remove(uuid);
        save();
        updateScoreboard();
    }

    public boolean isScoreboardHidden(UUID uuid) {
        return hiddenScoreboards != null && hiddenScoreboards.contains(uuid.toString());
    }

    public void clearAllPoints(int amount) {
        if (amount <= 0) allTimePoints.clear();
        else allTimePoints.replaceAll((u, p) -> Math.max(0, p - amount));
        save();
    }

    public void clearUserPoints(String playerOrUuid, int amount) {
        String uuidStr = playerOrUuid;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(playerOrUuid)) {
                uuidStr = player.getUuid().toString();
                break;
            }
        }
        if (amount <= 0) allTimePoints.remove(uuidStr);
        else allTimePoints.computeIfPresent(uuidStr, (k, v) -> Math.max(0, v - amount));
        save();
    }

    public String resolvePlayerName(String uuidStr) {
        return getPlayerName(uuidStr);
    }

    private String getPlayerName(String uuidStr) {
        String name = uuidStr;
        if (isUUID(uuidStr)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    name = player.getGameProfile().getName();
                } else {
                    name = server.getUserCache().getByUuid(uuid).map(p -> p.getName()).orElseGet(() ->
                        com.playtime.dashboard.util.UuidCache.getInstance().getUsername(uuid).orElse(uuidStr)
                    );
                }
            } catch (Exception e) { /* ignored */ }
        }

        Map<String, String> aliases = DashboardConfig.get().getAliasesLower();
        if (!aliases.isEmpty()) {
            String byUuid = aliases.get(uuidStr.toLowerCase());
            if (byUuid != null) return byUuid;
            String byName = aliases.get(name.toLowerCase());
            if (byName != null) return byName;
        }

        if (name.equalsIgnoreCase("hanger") || name.equalsIgnoreCase("advent")) {
            return "Advent/Hanger";
        }

        return name;
    }

    private void load() {
        if (!eventsFile.exists()) return;
        try (FileReader reader = new FileReader(eventsFile)) {
            JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
            if (data.has("activeEvents")) activeEvents = GSON.fromJson(data.get("activeEvents"), new TypeToken<List<ServerEvent>>(){}.getType());
            if (data.has("playerPreferences")) playerPreferences = GSON.fromJson(data.get("playerPreferences"), new TypeToken<Map<String, String>>(){}.getType());
            if (data.has("allTimePoints")) allTimePoints = GSON.fromJson(data.get("allTimePoints"), new TypeToken<Map<String, Integer>>(){}.getType());
            if (data.has("hiddenScoreboards")) hiddenScoreboards = GSON.fromJson(data.get("hiddenScoreboards"), new TypeToken<Set<String>>(){}.getType());
        } catch (Exception e) { FabricDashboardMod.LOGGER.error("Failed to load events data", e); }
        if (activeEvents == null) activeEvents = new ArrayList<>();
        if (playerPreferences == null) playerPreferences = new HashMap<>();
        if (allTimePoints == null) allTimePoints = new HashMap<>();
        if (hiddenScoreboards == null) hiddenScoreboards = new HashSet<>();
    }

    public void save() {
        try (FileWriter writer = new FileWriter(eventsFile)) {
            JsonObject data = new JsonObject();
            data.add("activeEvents", GSON.toJsonTree(activeEvents));
            data.add("playerPreferences", GSON.toJsonTree(playerPreferences));
            data.add("allTimePoints", GSON.toJsonTree(allTimePoints));
            data.add("hiddenScoreboards", GSON.toJsonTree(hiddenScoreboards));
            GSON.toJson(data, writer);
        } catch (IOException e) { FabricDashboardMod.LOGGER.error("Failed to save events data", e); }
    }

    private boolean isUUID(String s) { return s != null && s.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"); }

    private void distributePoints(ServerEvent event) {
        List<Map.Entry<String, Integer>> sorted = event.currentScores.entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
            .sorted(event.lowerIsBetter ? Map.Entry.comparingByValue() : Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());
        for (int i = 0; i < Math.min(sorted.size(), 3); i++) {
            allTimePoints.merge(sorted.get(i).getKey(), 3 - i, Integer::sum);
        }
        save();
    }

    private void broadcastEventResults(ServerEvent event) {
        server.getPlayerManager().broadcast(Text.literal("§6[Event] §cEvent ended: §l" + event.title), false);

        List<Map.Entry<String, Integer>> ranked = event.currentScores.entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
            .sorted(event.lowerIsBetter ? Map.Entry.comparingByValue() : Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());

        if (ranked.isEmpty()) {
            server.getPlayerManager().broadcast(Text.literal("§7No qualifying scores recorded."), false);
            return;
        }

        for (int i = 0; i < ranked.size(); i++) {
            Map.Entry<String, Integer> entry = ranked.get(i);
            String playerName = getPlayerName(entry.getKey());
            int val = entry.getValue();
            String scoreStr;
            if (event.type.equals("playtime")) scoreStr = formatTime(val);
            else if (event.type.equals("damage_dealt")) scoreStr = val + " ❤";
            else scoreStr = String.valueOf(val);
            int pointsAwarded = i < 3 ? (3 - i) : 0;
            String pointsStr = pointsAwarded > 0 ? " §6(+" + pointsAwarded + " pts)" : "";
            server.getPlayerManager().broadcast(
                Text.literal("§e#" + (i + 1) + " §f" + playerName + " §7- §a" + scoreStr + pointsStr),
                false
            );
        }
    }
}
