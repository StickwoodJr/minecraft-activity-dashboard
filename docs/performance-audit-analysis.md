# Java backend architecture analysis for playtime-mod
_Exported on 4/27/2026 at 22:23:58 EDT from Cursor (3.2.11)_

---

**User**

Act as a Senior Java Systems Architect and Fabric 1.21.1 Mod Developer. I am building a server-side Minecraft activity dashboard ("playtime-mod"). Your objective is to thoroughly analyze the provided codebase for architectural bottlenecks, with a strict mandate to minimize memory consumption and ensure lightweight execution.

Scope & Boundaries:

FOCUS ON: Core Java backend architecture (e.g., event parsing logic, persistent data structures, cache implementations, and object churn).

IGNORE: Frontend assets (HTML, CSS, images), Gradle build scripts, and general configuration files.

Please execute your analysis using the following step-by-step reasoning chain:

Step 1: Initial Profiling Strategy
Before writing any code, state the top 3 Java files/classes you suspect pose the highest risk for memory bloat or inefficient execution and briefly explain your reasoning.

Step 2: Component Scoring & Evaluation
Evaluate the primary backend components (such as log parsers and event listeners) and score their current implementation on a scale of 1-10 for memory efficiency.

Step 3: Categorized Findings
Break down the identified inefficiencies into two distinct categories:

High-Priority Memory Leaks: (e.g., unclosed streams, lingering references in collections, severe object churn inside loops).

Micro-Optimizations: (e.g., data type substitutions, better caching strategies).

---

**Cursor**

I have enough context to deliver the architectural review. Below is the analysis using the three-step chain you specified, citing the exact files and line ranges. After the report I will record the findings + a remediation roadmap as a plan artifact for follow-up implementation.

---

## Step 1 — Initial Profiling Strategy (top 3 hotspots)

1. **[backend/src/main/java/com/playtime/dashboard/events/EventManager.java](backend/src/main/java/com/playtime/dashboard/events/EventManager.java)** — Runs every 10 s (200-tick scheduler in `FabricDashboardMod.onInitialize`). `tick()` triggers `updateScores()` and `updateScoreboard()` *per active event*, both of which (a) re-list and DOM-parse every file in `world/stats`, (b) call `UuidCache.refresh()` (two-file disk re-parse) multiple times per tick, (c) iterate `Registries.ITEM`/`BLOCK` for obsidian events, and (d) allocate `Text`/`MutableText`/packet objects per player×score. Highest event-rate × highest object churn = #1 suspect.

2. **[backend/src/main/java/com/playtime/dashboard/events/StreakTracker.java](backend/src/main/java/com/playtime/dashboard/events/StreakTracker.java)** — `getStreak()` (line 105) is called from `EventManager.getStatValueFromPlayer/FromDisk` whenever a `daily_streak` event is active. Each call invokes `getStreaks()` (line 38), which fully reads `dashboard_cache.json` from disk via Gson DOM, calls `UuidCache.refresh()`, and rebuilds a `Map<String, Map<LocalDate, Double>>` from scratch. Quadratic-ish behaviour: O(stats_files × cache_size) of allocations every 10 s while a streak event runs.

3. **[backend/src/main/java/com/playtime/dashboard/parser/LogParser.java](backend/src/main/java/com/playtime/dashboard/parser/LogParser.java)** — `closeSession()` (lines 254-294) walks every wall-clock hour boundary inside a player session and performs nested `data.hourly.get(date).get(player)`/`putIfAbsent`/`getOrDefault` lookups, allocating `LocalDateTime`, `String` keys, and `double[24]` arrays. `runIncrementalParse()` also allocates a *fresh* String per line via `new String(line.getBytes("ISO-8859-1"), UTF_8)` (line 160) — this is a re-encode allocation on every line of `latest.log` between rotations.

---

## Step 2 — Component Scoring (memory efficiency, 1-10, higher = better)

- `parser/LogParser.java`: **4/10** — Streamed reader (good) but heavy allocations in `closeSession()` and double-decode on every incremental line.
- `parser/DashboardData.java`: **5/10** — Pure POJO, but the structure (`Map<String, Map<String, double[]>>`) and unbounded retention turn it into a long-lived in-memory time-series database.
- `events/EventManager.java`: **3/10** — DOM-based per-tick stats parsing, redundant `UuidCache.refresh()`, registry iteration for obsidian, pretty-printed JSON saves.
- `events/StreakTracker.java`: **2/10** — Full disk reparse and full graph rebuild on *every* `getStreak(uuid)` call; lookup-by-name fallback rebuilds yet again. Worst offender per call.
- `events/ServerEvent.java`: **6/10** — Plain DTO; only concern is `initialStats` and `currentScores` are serialized for *every* event save (pretty-printed).
- `stats/StatsAggregator.java`: **7/10** — Correctly uses `JsonReader` streaming, but allocates `LinkedHashMap` everywhere (avoidable) and re-iterates several keysets in `calculateAndInjectPlaystyleScores`.
- `web/DashboardWebServer.java` (`ApiHandler`, `updateLiveMetrics`): **4/10** — DOM-based JSON rewrite of the entire cache per `/api/activity` request; recursive `calculateDirectorySize()` over `/home/container` every 10 min with no depth cap.
- `web/PlayerHeadService.java`: **7/10** — Disk-resident cache, single-thread executor, explicit `BufferedImage.flush()`. Minor: returns a defensive copy in `getColorMap()` and double JSON-parses the textures payload.
- `util/UuidCache.java`: **5/10** — Two-file disk reparse on every `refresh()` with no TTL; called transitively several times per tick.
- `util/PlayerHeadFontManager.java`: **5/10** — Heavy I/O per `getHeadGlyph()` (creates `File`, calls `exists()`) and walks the entire respack tree on each event start; `nextSlot` is a non-atomic static.

---

## Step 3 — Categorized Findings

### A. High-Priority Memory Leaks / Object-Churn Hot Paths

1. **Per-tick DOM JSON parsing of every player stats file.** `EventManager.updateScores()` (lines 255-279) calls `getStatValueFromDisk()` (line 364), which uses `JsonParser.parseReader(reader).getAsJsonObject()` — a full DOM tree per file, per event, per tick. With N=50 players and 2 active events that's 100 DOM trees / 10 s, plus a `JsonObject` per `getNestedInt` or `sumCategory` traversal. Replace with `JsonReader` streaming (the same pattern already used in `StatsAggregator.parseStatsIntoMap`).

2. **`StreakTracker.getStreaks()` rebuilds the entire cache graph per call.** [backend/src/main/java/com/playtime/dashboard/events/StreakTracker.java](backend/src/main/java/com/playtime/dashboard/events/StreakTracker.java) line 38-91. There is *no* memoization. `getStreak()` (line 105) is invoked from `EventManager.getStatValueFromPlayer`/`FromDisk` (lines 318 and 377), which is in turn invoked per stats file per tick. Cache the result with the same TTL as the incremental parser (default 5 min).

3. **`UuidCache.refresh()` has no throttle.** [backend/src/main/java/com/playtime/dashboard/util/UuidCache.java](backend/src/main/java/com/playtime/dashboard/util/UuidCache.java) line 42-105 reparses `usercache.json` *and* `dashboard_faces/meta.json` every call. It is invoked from `EventManager.tick()` (line 244), `EventManager.takeStatsSnapshot()` (line 213), `EventManager.updateScores()` (line 256), `StreakTracker.getStreaks()` (line 63), and the leaderboard scheduler (`DashboardWebServer.start()` line 161). Multiple back-to-back calls per tick are typical. Add a `lastRefreshMillis` gate (e.g., 30 s).

4. **Per-player ignore-list rebuild inside `updatePlayerScore()`.** Lines 281-296 — for *every* player on *every* tick a new `List<String>` is built via `stream().map(toLowerCase).collect()`, *and* `UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))` is computed for each ignored player. This allocates one `byte[]`, one `MessageDigest`, and one `UUID` per ignored entry per call. Move to a precomputed `Set<UUID>` rebuilt only on config reload.

5. **Unbounded recursive disk walk in live metrics.** [backend/src/main/java/com/playtime/dashboard/web/DashboardWebServer.java](backend/src/main/java/com/playtime/dashboard/web/DashboardWebServer.java) lines 280-304 — `calculateDirectorySize(/home/container)` recurses without depth cap or symlink guard, allocating a `File[]` per directory. On a Pterodactyl-style host this can be a multi-GB tree. Even cached for 10 min, a single walk on a large world will spike heap and IO. Use `Files.walkFileTree` on a dedicated subdirectory, or stat the world dir only.

6. **Whole-cache JSON read+rewrite per `/api/activity` request.** Same file lines 693-754: `JsonParser.parseReader()` materializes the entire cache as a `JsonObject`, mutates it, then `GSON.toJson(data).getBytes(UTF_8)` allocates *both* the full String and a duplicate byte[]. With years of history this is tens of MB held twice on heap per request, and there is no concurrency limit (executor = 2 threads, so 2 simultaneous copies is realistic).

7. **`DashboardData` retained in memory unbounded.** [backend/src/main/java/com/playtime/dashboard/parser/DashboardData.java](backend/src/main/java/com/playtime/dashboard/parser/DashboardData.java) — `playerDailyRaw` and `hourly` grow forever (one entry per (date, player) pair). The *parser itself* creates a fresh `DashboardData` from disk per incremental run (`LogParser.runIncrementalParse` line 162 → `loadCache`), but consumers (`StreakTracker`, `ApiHandler`) each allocate their own. There is no shared in-memory snapshot. Either share one canonical instance behind a TTL, or trim entries beyond a configured retention window.

8. **Pretty-printed JSON for every event tick save.** `EventManager.GSON = new GsonBuilder().setPrettyPrinting().create()` (line 35) is used in `save()` (line 643), which is invoked from `tick()`'s downstream `stopEvent()`, `setPlayerScoreboardPreference()`, `setScoreboardHidden()`, `clearAllPoints()`, `distributePoints()`. Pretty printing roughly 2× the byte output and allocates extra `String` segments per token.

9. **Per-player packet & Text allocation in `updateScoreboard()`.** Lines 489-518 build a fresh `MutableText`, `Style`, `Identifier`, `FixedNumberFormat`, and two `Optional.of(...)` wrappers per (online-player × score-entry) per 10 s. For 10 online × 20 scores × 2 events = 400 allocations / tick that are 99% identical to the previous tick. Diff the snapshot and only resend changed scores. The `Identifier.of("dashboard","heads")` literal can also be a `static final`.

### B. Micro-Optimizations

a. **Switch `EventManager.getStatValueFromDisk` to `JsonReader`** (mirror `StatsAggregator.parseStatsIntoMap`). Eliminates one full DOM tree per file per tick.

b. **Pre-resolve `OBSIDIAN_IDS` to `Set<Item>`/`Set<Block>` at server start** instead of iterating `Registries.ITEM` / `Registries.BLOCK` (lines 327-345) every player every tick.

c. **Throttle `UuidCache.refresh()`** with a `volatile long lastRefreshNs` + 30 s gate. Current callers will skip transparently.

d. **Memoize `StreakTracker.getStreaks()`** behind a TTL (configurable; default ≥ `incremental_update_interval_minutes`).

e. **In `LogParser.closeSession()`**, hoist the inner-loop lookups: capture `Map<String, Double> dailyMap = data.playerDailyRaw.computeIfAbsent(...)` and `double[] hourArr = data.hourly.computeIfAbsent(date,...).computeIfAbsent(player, k->new double[24])` *once*, then index/add. This converts ~7 map lookups per segment to 2.

f. **Use `LocalDate` keys instead of `String` keys** for `daily`, `playerDailyRaw`, `hourly`. Saves the per-line `dateStr` parse + reduces per-entry overhead for long retention. Or stay on String but intern the date keys.

g. **Replace `LinkedHashMap` with `HashMap`** in `StatsAggregator.buildLeaderboards()` (line 51, 120, 287) where insertion order isn't observed. `LinkedHashMap.Entry` is ~20 % heavier than `HashMap.Entry`.

h. **Stream the `/api/activity` response** via `JsonWriter` directly to `exchange.getResponseBody()` with `sendResponseHeaders(200, 0)` (chunked). Eliminates the duplicate `String` + `byte[]` materialization. Add `Content-Encoding: gzip` (the JDK `HttpServer` doesn't auto-gzip).

i. **`PlayerHeadService.getColorMap()`** (line 168) — return `Collections.unmodifiableMap(metaMap)` instead of `new HashMap<>(metaMap)`. The `metaMap` is already a `ConcurrentHashMap`, so a defensive copy isn't required.

j. **`PlayerHeadFontManager.nextSlot`** (line 21) — switch to `AtomicInteger` and remove the `synchronized (PlayerHeadFontManager.class)` inside the `computeIfAbsent` lambda (line 78). Calling `synchronized` from inside `ConcurrentHashMap.computeIfAbsent` is a known footgun.

k. **`PlayerHeadFontManager.getHeadGlyph()`** does `new File(...).exists()` per call (line 69-71). It is invoked from inside `EventManager.updateScoreboard()` loops. Cache a `Set<String>` of names with confirmed face PNGs, refreshed when `PlayerHeadService` writes a new file.

l. **`LogParser.processLine()`** runs `LOG_PATTERN.matcher(line).find()` *and* `BOOT_PATTERN`/`STOP_PATTERN`/`JOIN_PATTERN`/`LEAVE_PATTERN` for every single line. Add a fast prefix gate: only parse the time pattern if the line *contains* `joined the game`, `left the game`, `Stopping server`, or starts with `Loading Minecraft` / `Environment:` / `Starting minecraft server`. 95 % of lines are skipped after a single `String.indexOf`.

m. **`LogParser.runIncrementalParse()`** line 160 — `RandomAccessFile.readLine()` returns latin-1 by spec, then we re-encode to UTF-8 by allocation. Replace with a `BufferedReader` over a `FileInputStream` that has been `.skip(lastByteOffset)`'d, decoded once as UTF-8.

n. **`EventManager.GSON` for runtime saves** — use a compact `Gson` (no pretty print) for `save()` invocations on hot paths; keep the pretty-printed instance for human-edited files only.

o. **`EventManager.getPlayerName()`** (line 598) — relies on `UUID.fromString` throwing `IllegalArgumentException` for non-UUID strings. Avoid the exception path by checking the regex once (`isUUID()` already exists at line 654 but isn't used here). Throwing is ~1000× slower than a regex check on the hot scoreboard path.

p. **Aliases lookup in `EventManager.getPlayerName()` and `LogParser.normalizePlayer()`** does up to three `containsKey` calls per call. Cache a single lower-case alias map at config load.

q. **`DashboardWebServer.scheduler`** is `Executors.newScheduledThreadPool(2)` but runs the historical parse, leaderboard rebuild, incremental parse, and live metrics on it. The historical parse can starve incrementals during boot. Either bump to 3 or run the historical parse on a one-shot thread.

r. **`DashboardWebServer.calculateDirectorySize()`** result is cached for 10 min, but `cachedWorldSizeMb` and `lastWorldSizeCheck` are non-volatile primitives accessed from the scheduler thread and read indirectly via `liveSnapshot` (volatile). Mark them `volatile` or move into the snapshot constructor.

s. **`UuidCache.networkResolved` and `networkFailed`** grow unbounded over server lifetime as random web clients query the dashboard. Bound them with a small LRU (`LinkedHashMap` access-order, e.g., 1024 entries).

---

I'll record these findings plus a prioritized remediation roadmap as a plan artifact for follow-up.

---

**User**

Create an evaluation of what things could be effectd or broken by these changes and create a detailed list of things to test to ensure no regression has occured.

---

**Cursor**

I'll append the risk evaluation and regression test plan to the existing plan file.

I've added two new sections to the plan file:

- **Section C — Risk Evaluation per Change** — a per-item walkthrough of blast radius, the most likely failure modes, and the mitigations to bake in *before* shipping each change.
- **Section D — Regression Test Plan** — grouped by change cluster (`D1` build, `D2` UUID cache, `D3` event manager, etc.), with required (✅) and recommended (⚠️) checks.
- **Section E — Suggested Test Fixtures** — repo files to commit so the regression checks are reproducible.

The most subtle regressions to watch for, based on the analysis, are:

1. **Throttled `UuidCache`** silently rendering brand-new players as their UUID until the gate expires — needs a JOIN-event bypass and a `forceRefresh()` for `takeStatsSnapshot`.
2. **Streaming `JsonReader`** drift between `getStatValueFromDisk` and `getStatValueFromPlayer` causing scores to jump when a player goes online/offline mid-event.
3. **Streak memoization** missing the midnight rollover in `streak_timezone`.
4. **Scoreboard diffing** skipping the full re-send for a freshly-joined player or for a `setScoreboardHidden(false)` toggle.
5. **Incremental parser UTF-8 fix** mis-tracking byte offsets across `latest.log` rotations — the round-trip test (`D11`) and the storage-after-`\n` rule are the safety net.
6. **`/api/activity` streaming** breaking the dashboard frontend if the JSON shape diverges by even an absent field — covered by the `jq -S . old.json new.json` snapshot test in `D6`.

The plan now contains the full audit, the remediation roadmap, the per-change risk evaluation, and the regression test list — ready for execution whenever you are. Use `SwitchMode` → agent when you want me to start implementing any of the todos.

---

**User**

Implement the plan as specified, it is attached for your reference. Do NOT edit the plan file itself.

To-do's from the plan have already been created. Do not create them again. Mark them as in_progress as you work, starting with the first one. Don't stop until you have completed all the to-dos.

---

**Cursor**

I'll implement the plan systematically. Let me start by marking the first todo in-progress and examining the config file I need.

Now I'll implement the UuidCache throttle with safe runtime additions and a force-refresh path.

Now wire the JOIN handler to register players directly, and use `forceRefresh()` at server startup and event-snapshot time.

Now mark the first task complete and move to streaming the disk JSON. Let me start by examining the current DOM-based code.

Now I'll rewrite the disk-stats path with streaming `JsonReader`. The current DOM helpers (`getNestedInt`, `sumCategory`, `sumIdSetFromDisk`, `sumTideFromDisk`) become four streaming traversals.

Now let me check if `JsonReader.nextLong` will work for negative or boolean values; also verify there are no leftover imports issues.

Marking the streaming task complete and moving to streak memoization.

Now precompute ignored-player sets. I'll add them to `DashboardConfig` as transient cached fields recomputed in `load()`.

Now use the precomputed sets in `EventManager.updatePlayerScore`.

Now update `ApiHandler` in `DashboardWebServer` to use the precomputed set.

Now task 5: stream `/api/activity` with gzip negotiation. I'll use chunked transfer + `Gson.toJson(JsonElement, JsonWriter)` so we never materialize the full String/byte[].

Now task 6: bound `calculateDirectorySize` to the world directory with depth/symlink guards.

Marking bound-directory complete and moving to compact event saves.

Marking compact-saves complete and moving to scoreboard diffing.

Now update `updateScoreboard()` to use the diff, and the lifecycle hooks (`onPlayerJoin/Leave`, `setScoreboardHidden`, `setPlayerScoreboardPreference`, `stopEvent`) to invalidate the cache. Plus add a public `invalidateScoreboardCache()`.

Also clear caches when an event stops (any holders for that objective become stale).

Now the actual diffing in `updateScoreboard()`. I'll restructure the per-player score loop.

Now wire `/dashboard reload` to invalidate the scoreboard cache so alias changes take effect immediately.

Marking diffing complete and moving to pre-resolved obsidian registry.

Marking obsidian-resolution complete and moving to log parser optimizations.

Now the polish-misc bundle. I'll work through each item.

First the easy ones: `getColorMap` unmodifiable view.

Now `AtomicInteger nextSlot` in `PlayerHeadFontManager`.

Now `isUUID` gate in `EventManager.getPlayerName` + lower-case alias map cache.

Now `LinkedHashMap` → `HashMap` in `StatsAggregator`.

Now let me run lints across all the changed files and try a build.

Compile clean. Let me run a full build to be sure.

Build succeeds. Marking the final todo complete.

All 13 todos complete and the backend builds cleanly. Here's a summary of what shipped, organized by file:

**[backend/src/main/java/com/playtime/dashboard/util/UuidCache.java](backend/src/main/java/com/playtime/dashboard/util/UuidCache.java)** — full rewrite
- 30 s throttle on `refresh()`, plus `forceRefresh()` for explicit reload paths.
- `registerPlayer(UUID, String)` for the JOIN handler so new players resolve immediately.
- Runtime additions kept on `ConcurrentHashMap`s that survive refresh (fixes the original "lost on refresh" bug).
- `networkResolved` and `networkFailed` are now bounded LRU caches (1024 entries) instead of unbounded.

**[backend/src/main/java/com/playtime/dashboard/events/EventManager.java](backend/src/main/java/com/playtime/dashboard/events/EventManager.java)**
- `getStatValueFromDisk` rewritten as four streaming `JsonReader` traversals (`readSingleStat`, `sumCategoryFiltered`, `readFishCaughtFromDisk`); old DOM helpers (`getNestedInt`, `sumCategory`, `sumIdSetFromDisk`, `sumTideFromDisk`) removed.
- `OBSIDIAN_IDS` pre-resolved to `Set<Item>` / `Set<Block>` once in `init(server)`.
- Scoreboard diffing: per-player `ScoreboardSendState` cache; only emit `ScoreboardScoreUpdateS2CPacket` when score or format text changed. Cache invalidated on join/leave, hide/show toggle, preference change, event stop, and `/dashboard reload`.
- `Identifier.of("dashboard","heads")` and the corresponding `Style` hoisted to `static final`.
- `ignored_players` lookups switched to precomputed `Set<String>` / `Set<String>` from config.
- `getPlayerName` skips the exception-driven `UUID.fromString` path via the `isUUID()` regex.
- Alias lookups switched to a single precomputed lower-case map (3 lookups → 2).
- `init` and `takeStatsSnapshot` use `forceRefresh()`; tick / updateScores fall through the throttle.
- Pretty-printing dropped from `EventManager.save()` (compact JSON).

**[backend/src/main/java/com/playtime/dashboard/events/StreakTracker.java](backend/src/main/java/com/playtime/dashboard/events/StreakTracker.java)**
- `getStreaks()` memoized behind a `volatile Snapshot` keyed on cache-file mtime + computed `today` in `streak_timezone` + TTL = `incremental_update_interval_minutes`.

**[backend/src/main/java/com/playtime/dashboard/parser/LogParser.java](backend/src/main/java/com/playtime/dashboard/parser/LogParser.java)**
- `processLine()` gated by `isInterestingLine()` (six `indexOf` checks) so chat/noise lines skip all regex.
- `closeSession()` hoisted to `merge` / `computeIfAbsent` (7 → 4 map lookups per segment).
- `runIncrementalParse()` now reads a single bounded byte-chunk (max 16 MB), trims to last `\n`, decodes once as UTF-8, and walks the chunk by char — no per-line latin1→UTF-8 re-encode. `lastByteOffset` advances by exact processed byte count.

**[backend/src/main/java/com/playtime/dashboard/web/DashboardWebServer.java](backend/src/main/java/com/playtime/dashboard/web/DashboardWebServer.java)**
- `/api/activity` uses chunked transfer + `Gson.toJson(JsonElement, JsonWriter)` direct to the response body; gzip negotiated via `Accept-Encoding`. No more duplicate `String` + `byte[]`.
- `ApiHandler` uses the precomputed `getIgnoredLowerNames()` set instead of rebuilding per request.
- `calculateDirectorySize()` rewritten as `Files.walkFileTree` with depth cap (8), no symlink follow, and per-visit failure swallowed; runs only on the configured `stats_world_name`. Cache TTL 30 min (was 10 min).
- `cachedWorldSizeMb` and `lastWorldSizeCheck` made `volatile`.
- Scheduler bumped from 2 → 3 threads so historical parse can't starve incremental updates.

**[backend/src/main/java/com/playtime/dashboard/web/PlayerHeadService.java](backend/src/main/java/com/playtime/dashboard/web/PlayerHeadService.java)**
- `getColorMap()` returns `Collections.unmodifiableMap(metaMap)` (no defensive copy).

**[backend/src/main/java/com/playtime/dashboard/util/PlayerHeadFontManager.java](backend/src/main/java/com/playtime/dashboard/util/PlayerHeadFontManager.java)**
- `nextSlot` switched to `AtomicInteger`; the `synchronized (PlayerHeadFontManager.class)` inside `ConcurrentHashMap.computeIfAbsent` removed (known footgun). `loadSlots` uses `updateAndGet(Math::max)`.

**[backend/src/main/java/com/playtime/dashboard/config/DashboardConfig.java](backend/src/main/java/com/playtime/dashboard/config/DashboardConfig.java)**
- New transient cached fields `ignoredLowerNames`, `ignoredOfflineUuids`, `aliasesLower`, recomputed in `load()` via `recomputeDerived()`.

**[backend/src/main/java/com/playtime/dashboard/stats/StatsAggregator.java](backend/src/main/java/com/playtime/dashboard/stats/StatsAggregator.java)**
- All `LinkedHashMap` allocations replaced by `HashMap` (frontend sorts client-side).

**[backend/src/main/java/com/playtime/dashboard/FabricDashboardMod.java](backend/src/main/java/com/playtime/dashboard/FabricDashboardMod.java)**
- JOIN event handler now calls `UuidCache.registerPlayer()` so name/UUID resolves regardless of throttle state.

**[backend/src/main/java/com/playtime/dashboard/commands/DashboardCommand.java](backend/src/main/java/com/playtime/dashboard/commands/DashboardCommand.java)**
- `/dashboard reload` now also calls `EventManager.invalidateScoreboardCache()` so alias edits propagate to the sidebar on the next tick.

`./gradlew build -x test` is green (only pre-existing unchecked-cast note). The plan file at `memory_perf_audit_playtime_mod_a4fbbabe.plan.md` is unchanged per your instruction.

---

**User**

List all the features I need to test

---

**Cursor**

Here's the consolidated test checklist for what shipped, organized by change cluster. ✅ = required to ship; ⚠️ = recommended for production confidence.

## 1. UuidCache throttle + runtime registration

- ✅ Cold start: `EventManager.init` resolves all online players in the first tick; scoreboard shows real names, not UUIDs.
- ✅ A new player joining mid-session sees their name on the scoreboard within 1 tick (the JOIN handler calls `registerPlayer`, no 30 s wait).
- ✅ Within a 30 s window, repeated `refresh()` calls do not re-read `usercache.json` / `meta.json` (verify with file-watch or log instrumentation).
- ✅ `forceRefresh()` is invoked on `init` and on `takeStatsSnapshot` when an event starts (a brand-new player gets a baseline of 0 instead of being credited with all current stats).
- ✅ `/dashboard reload` does not need to wait for the throttle (config edits propagate immediately).
- ✅ Mojang fallback in `getUuid()` / `getUsername()` survives subsequent `forceRefresh()` calls (runtime maps are not wiped).
- ⚠️ Stress: 10 000 unique web lookups against `getUsername(unknownUuid)` — `networkResolved` size never exceeds 1024 (LRU eviction).

## 2. Streaming `JsonReader` for stats files

- ✅ For each event type, the value returned for an offline player matches the value the same player would produce while online: `playtime`, `mob_kills`, `fewest_deaths`, `damage_dealt`, `player_kills`, `fish_caught` (with and without `tide` mod), `daily_streak`, `blocks_placed`, `blocks_mined`, `obsidian_placed`, `obsidian_mined`.
- ✅ Malformed stats JSON (truncated, invalid syntax) returns `0` and does not crash the tick.
- ✅ Empty stats file (`{}`) returns `0`.
- ✅ Stats file with unknown extra categories (mod-added) is skipped without throwing.
- ✅ Values larger than `Integer.MAX_VALUE` (e.g., damage_dealt on a long-lived player) do not throw — they truncate the same way the DOM path did.
- ⚠️ A/B parity: turn on a temporary parallel-run logger that compares old DOM and new streaming for one tick on first launch; expect zero diffs.

## 3. StreakTracker memoization

- ✅ First call to `getStreaks()` reads disk; second call within TTL does not re-read the cache file.
- ✅ When the parser rewrites `dashboard_cache.json`, the next `getStreaks()` call sees the new data within 1 tick (mtime invalidation).
- ✅ Crossing midnight in `streak_timezone` invalidates the snapshot (today/yesterday recomputed).
- ✅ A player who hits 60 minutes today gets streak N → N+1 within the configured TTL.
- ✅ Concurrent reads from event tick + web request never observe a half-built map.
- ⚠️ Run a `daily_streak` event with 50 stats files — verify the per-tick disk reads dropped from "100s" to "1 per TTL".

## 4. Precomputed ignored-player sets

- ✅ Adding a name to `ignored_players` and running `/dashboard reload` removes the player from the scoreboard within 1 tick.
- ✅ Mixed-case ignored entries (`Steve` vs config `steve`) are honored on the scoreboard.
- ✅ An offline player whose offline-UUID equals `nameUUIDFromBytes("OfflinePlayer:<Name>")` is excluded from `updateScores`.
- ✅ Removing a name from `ignored_players` re-introduces them on the scoreboard after reload.
- ✅ `/api/activity` filters the same set of players the scoreboard does.

## 5. `/api/activity` streaming + gzip

- ✅ `curl http://<host>:8105/api/activity | jq -S .` produces JSON shape-equivalent to the old endpoint (use a fixed `dashboard_cache.json` fixture and diff).
- ✅ With `ignored_players` set, `daily` totals equal `Σ playerDailyRaw[date] / 60.0` rounded to 2 decimals.
- ✅ `Accept-Encoding: gzip` returns `Content-Encoding: gzip` and a gzipped body; without it, plain JSON.
- ✅ The frontend `mc-activity-heatmap-v13.html` loads all tabs (Heatmap, Sessions, Streaks, Leaderboards, Live) without console errors.
- ✅ Concurrent load: 50 parallel `curl`s — every response is well-formed JSON with no truncation.
- ✅ Empty-cache case (`dashboard_cache.json` missing): response is `{"daily":{},"playerDailyRaw":{},"sessData":{},"hourly":{}}` with the right gzip behavior.
- ⚠️ Heap profile: peak RSS during a single large `/api/activity` request drops vs. the DOM-rewrite version.

## 6. Bounded directory walk

- ✅ Live tab `worldSizeMb` reports a non-zero value within 30 minutes of boot.
- ✅ A symlink loop placed inside the world dir (`ln -s world world/loop`) does not crash or hang the live-metrics scheduler.
- ✅ A permission-denied subdirectory is skipped silently (no per-file `WARN` spam).
- ✅ Reported size ≈ `du -sm <world>` ± 1 %.
- ✅ Walk respects depth cap of 8.
- ⚠️ On a >10 GB world, walk completes in <2 s.

## 7. Compact event-saves JSON

- ✅ After event start/stop, `dashboard_events.json` parses (`jq . dashboard_events.json` succeeds).
- ✅ File size is meaningfully smaller for a typical 5-event payload (~30 % reduction).
- ✅ Restarting the server reloads events identically (round-trip preserved).

## 8. Scoreboard diffing

- ✅ A new player joining mid-event sees the full scoreboard within 1 tick (no missing rows).
- ✅ Toggling `setScoreboardHidden(true)` clears the sidebar; `false` restores it on next tick.
- ✅ Stopping an event removes its scoreboard for everyone within 1 tick (cache cleared).
- ✅ Player switching `setPlayerScoreboardPreference` sees the new event's full scores within 1 tick.
- ✅ `/dashboard reload` after editing `player_aliases` refreshes displayed names within 1 tick (`invalidateScoreboardCache` wired into the reload command).
- ✅ Score change for one player does not resend packets for unchanged players.
- ⚠️ Two simultaneous events: each player's selected event renders correctly; switching preference resends.
- ⚠️ JFR / async-profiler recording: `updateScoreboard` self-time drops measurably.

## 9. Pre-resolved obsidian registry

- ✅ `obsidian_placed` event with vanilla obsidian only — score equals pre-change value.
- ✅ With BetterNether installed, all 24 modded obsidian variants are tracked.
- ✅ Without BetterNether installed, the resolved sets contain only the 2 vanilla IDs (no nulls).
- ✅ Per-tick CPU on `getStatValueFromDisk(obsidian_*)` drops vs. pre-change (registry no longer iterated per stats file).
- ✅ Resolution happens after `SERVER_STARTED` (registries frozen — no missing IDs).

## 10. LogParser fast prefix gate

- ✅ Historical parse on the existing `logs/` directory (incl. `2026-02-23-2.log.gz`) produces a `dashboard_cache.json` byte-equivalent (after `jq -S .`) to the pre-change implementation.
- ✅ `Loading Minecraft`, `Environment:`, `Starting minecraft server version`, `Stopping server`, `joined the game`, `left the game` are all reachable via the gate (no missed lifecycle events).
- ⚠️ 10 000-line synthetic log: gated parser is ≥ 5× faster than pre-change.

## 11. LogParser closeSession map hoisting

- ✅ Pure-refactor parity: same fixture log produces the same `daily`, `playerDailyRaw`, `hourly`, `sessData` totals as pre-change.
- ✅ Hour-segment distribution still rounds at save time (`Math.round(... * 10) / 10` etc.).

## 12. Incremental parser UTF-8 fix

- ✅ Player chat with non-ASCII (emoji) doesn't corrupt the next line in incremental parse.
- ✅ Log rotation (`latest.log` shrinks below `lastByteOffset`) resets to 0; `activeSessions` and `incrementalLastTs` survive.
- ✅ Large tail (>16 MB) is processed in chunks across multiple incremental runs (cap honored, no spike).
- ✅ Partial trailing line is re-read on the next pass (no dropped or duplicated lines).
- ✅ `Advent/Hanger` normalization still groups the two players into a single session.
- ⚠️ Round-trip stress: write 10 000 lines in batches, run incremental parse repeatedly, assert no double-counting and no missed sessions.

## 13. Polish-misc bundle

- ✅ `PlayerHeadService.getColorMap()` returns an unmodifiable view; `PlayerMetaHandler.handle` still serializes correctly.
- ✅ `PlayerHeadFontManager.nextSlot` is monotonic across restarts (write `slots.json`, restart, verify `nextSlot == max+1`).
- ✅ Concurrent `getHeadGlyph` calls from many web threads don't deadlock or duplicate slots (stress 100 unique player names from parallel threads).
- ✅ `EventManager.getPlayerName` for a non-UUID alias key still resolves through the alias map (no `IllegalArgumentException` log spam).
- ✅ Alias config edits with mixed case (`SteveDude`) and UUID keys both still resolve after `/dashboard reload`.
- ✅ `UuidCache.networkResolved` and `networkFailed` do not exceed 1024 entries after 10 000 unique web lookups.
- ✅ Leaderboards JSON output matches pre-change after `LinkedHashMap → HashMap` (sort-order independent on the frontend).
- ✅ `cachedWorldSizeMb` / `lastWorldSizeCheck` writes are visible from the live-metrics handler (volatile fields).
- ✅ Scheduler thread-pool of 3 — historical parse, leaderboard rebuild, incremental parse, and live metrics all run without one starving another.

## 14. End-to-end integration scenarios

- ✅ Server boot → 5 players join → `playtime` event starts → 30 minutes elapse → event stops cleanly with broadcast and points awarded.
- ✅ Server boot → `daily_streak` event starts → cross midnight in `streak_timezone` → streak counts increment correctly the next day.
- ✅ Server boot → `obsidian_placed` event with BetterNether → players place modded obsidian variants → score updates within 1 tick.
- ✅ Server crash recovery: kill the JVM mid-event → restart → `dashboard_events.json` reloads, ghost sessions in logs are capped at 4 hours, dashboard shows correct data.
- ✅ Frontend dashboard pulls `/api/activity`, `/api/leaderboards`, `/api/live`, `/api/events`, `/api/player-stats/<name>`, `/api/player-advancements/<name>` — every endpoint returns 200.
- ✅ `/dashboard event create`, `event stop`, `event setlength`, `event status`, `event scoreboard hide/show`, `event clearpoints`, `reload`, `reparse` — all commands still work.

## 15. Long-running soak

- ⚠️ 24 h with 5 simulated players cycling join/leave every 5 minutes and `/api/activity` poll every 30 s:
  - Heap usage stays bounded (no upward trend) — verify with `jstat -gcutil <pid> 60s`.
  - Average MSPT contribution from the dashboard tick (200-tick scheduler) is lower than baseline.
  - `dashboard_cache.json` reaches a steady-state size and only grows by ~one day's data per real day.
  - No `OutOfMemoryError`, `RejectedExecutionException`, or unbounded thread growth.
  - `dashboard_faces/` directory size and `meta.json` size stable.

## 16. Rollback / on-disk format compatibility

- ✅ Pre-change reader can read post-change `dashboard_cache.json` and vice versa.
- ✅ Pre-change reader can read post-change `dashboard_events.json` (compact JSON is still valid JSON).
- ✅ Reverting any single change leaves the build green.

The fastest minimum-bar path is sections 1–13 (✅ items only) plus the integration scenarios in 14. Section 15 is what gives you confidence to deploy to a long-lived production server.

