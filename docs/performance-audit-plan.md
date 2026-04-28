---
name: memory perf audit playtime mod
overview: Architectural memory/performance audit of the playtime-mod backend. Captures the top hot paths (per-tick DOM parsing, unthrottled cache refreshes, full-graph rebuilds in StreakTracker, unbounded directory walks) and a prioritized remediation roadmap split into "fix now" (high-priority memory/CPU leaks) and "polish" (micro-optimizations).
todos:
  - id: throttle-uuid-cache
    content: Add a 30 s throttle gate to UuidCache.refresh() and verify all transitive callers (EventManager.tick/init/takeStatsSnapshot/updateScores, StreakTracker.getStreaks, leaderboard scheduler) become no-ops within the window.
    status: completed
  - id: streaming-stats-disk
    content: Rewrite EventManager.getStatValueFromDisk (and its sumCategory / sumIdSetFromDisk / sumTideFromDisk helpers) to use Gson JsonReader streaming instead of JsonParser DOM, mirroring StatsAggregator.parseStatsIntoMap.
    status: completed
  - id: memoize-streaks
    content: Add a TTL-cached snapshot to StreakTracker.getStreaks() (default TTL = incremental_update_interval_minutes); invalidate on cache file mtime change.
    status: completed
  - id: precompute-ignored
    content: Precompute ignored-player Set<UUID> (offline UUIDs) and Set<String> (lowercase names) at DashboardConfig load; use those in EventManager.updatePlayerScore and ApiHandler instead of rebuilding per call.
    status: completed
  - id: stream-api-activity
    content: "Stream /api/activity via JsonWriter to the chunked response body instead of materializing the full JSON String + byte[]; add Content-Encoding: gzip."
    status: completed
  - id: bound-directory-size
    content: Replace recursive calculateDirectorySize on /home/container with a bounded Files.walkFileTree on the world directory only, with depth cap, symlink guard, and longer cache TTL.
    status: completed
  - id: compact-event-saves
    content: Add a compact (non-pretty) Gson instance for EventManager.save() runtime invocations; keep pretty-printed Gson only for human-edited files.
    status: completed
  - id: scoreboard-diffing
    content: Diff EventManager.updateScoreboard against the previous tick's scores and only emit ScoreboardScoreUpdateS2CPacket for changed entries; hoist Identifier.of("dashboard","heads") to a static final.
    status: completed
  - id: preresolve-obsidian-registry
    content: Pre-resolve EventManager.OBSIDIAN_IDS to Set<Item> and Set<Block> at server-start so obsidian_placed / obsidian_mined avoid full registry iteration per stats file.
    status: completed
  - id: logparser-fast-gate
    content: Add a fast indexOf-based prefix gate to LogParser.processLine so non-event lines skip all regex work.
    status: completed
  - id: logparser-closesession-hoist
    content: Hoist nested map lookups in LogParser.closeSession into local references (one computeIfAbsent per segment).
    status: completed
  - id: logparser-incremental-utf8
    content: Replace RandomAccessFile.readLine + new String(latin1->utf8) in LogParser.runIncrementalParse with a positioned BufferedReader so we don't re-encode every line.
    status: completed
  - id: polish-misc
    content: "Apply remaining micro-opts: getColorMap unmodifiable view; AtomicInteger for nextSlot; isUUID gate in getPlayerName; lower-case alias map cache; LRU bounds on UuidCache.networkResolved/networkFailed; LinkedHashMap → HashMap in StatsAggregator; LiveMetrics fields volatile; scheduler thread count."
    status: completed
isProject: false
---

# Memory & Performance Audit — playtime-mod backend

This is an analysis-and-remediation plan; no edits have been made.

## Component scores (memory efficiency, 1-10)

- [parser/LogParser.java](backend/src/main/java/com/playtime/dashboard/parser/LogParser.java) — 4/10
- [parser/DashboardData.java](backend/src/main/java/com/playtime/dashboard/parser/DashboardData.java) — 5/10
- [events/EventManager.java](backend/src/main/java/com/playtime/dashboard/events/EventManager.java) — 3/10
- [events/StreakTracker.java](backend/src/main/java/com/playtime/dashboard/events/StreakTracker.java) — 2/10
- [events/ServerEvent.java](backend/src/main/java/com/playtime/dashboard/events/ServerEvent.java) — 6/10
- [stats/StatsAggregator.java](backend/src/main/java/com/playtime/dashboard/stats/StatsAggregator.java) — 7/10
- [web/DashboardWebServer.java](backend/src/main/java/com/playtime/dashboard/web/DashboardWebServer.java) — 4/10
- [web/PlayerHeadService.java](backend/src/main/java/com/playtime/dashboard/web/PlayerHeadService.java) — 7/10
- [util/UuidCache.java](backend/src/main/java/com/playtime/dashboard/util/UuidCache.java) — 5/10
- [util/PlayerHeadFontManager.java](backend/src/main/java/com/playtime/dashboard/util/PlayerHeadFontManager.java) — 5/10

## A. High-priority memory / churn issues (fix first)

1. **Per-tick DOM parse of every stats file** — `EventManager.getStatValueFromDisk` (line 364) uses `JsonParser.parseReader(...).getAsJsonObject()`. Replace with `JsonReader` streaming; mirror the pattern already in `StatsAggregator.parseStatsIntoMap`.
2. **`StreakTracker.getStreaks()` rebuilds the full cache graph on every call** (line 38-91), called per stats file per tick when `daily_streak` event is active. Memoize with TTL ≥ incremental parse interval.
3. **`UuidCache.refresh()` reparses two JSON files with no throttle** (line 42-105). Add `volatile long lastRefreshNs` and a 30 s gate; transitive callers (`EventManager.tick`, `takeStatsSnapshot`, `updateScores`, `StreakTracker.getStreaks`, leaderboard scheduler) skip transparently.
4. **`updatePlayerScore` rebuilds ignored list and offline UUIDs per player per tick** (lines 281-296). Precompute `Set<UUID> ignoredUuids` and `Set<String> ignoredLowerNames` at config load.
5. **Unbounded recursive `calculateDirectorySize` over `/home/container`** (`DashboardWebServer` lines 280-304). Bound to the world dir + add `Files.walkFileTree` with depth cap and symlink guard, or eliminate by stat-only on the world folder.
6. **Whole-cache DOM read + rewrite per `/api/activity` hit** (`DashboardWebServer.ApiHandler` lines 693-754). Stream via `JsonWriter` to the response body with chunked transfer; add gzip.
7. **`DashboardData` retained unbounded** — add a configurable retention window (e.g., 365 days) trimmed at parser save time, and share one canonical in-memory snapshot rather than each consumer reparsing from disk.
8. **Pretty-printed JSON for runtime saves** in `EventManager.save()` (line 643). Use a compact `Gson` instance for hot saves.
9. **`updateScoreboard()` allocates packets/Text/Optional per player×score every 10 s** (lines 489-518). Diff the previous snapshot and resend only changed scores; hoist the `Identifier.of("dashboard","heads")` to a `static final`.

## B. Micro-optimizations (polish)

- Pre-resolve `OBSIDIAN_IDS` to `Set<Item>`/`Set<Block>` at server start instead of iterating `Registries.ITEM`/`BLOCK` per stats file per tick (`EventManager` lines 327-345).
- `LogParser.closeSession()` (lines 254-294): hoist nested map lookups; capture `Map<String,Double>` and `double[24]` references once per segment.
- Use `LocalDate` (or interned `String`) for daily/hourly keys to cut per-entry overhead on long retention.
- Replace `LinkedHashMap` with `HashMap` in `StatsAggregator.buildLeaderboards` (lines 51, 120, 287) where insertion order is unused.
- `PlayerHeadService.getColorMap()` (line 168) — return `Collections.unmodifiableMap(metaMap)` instead of a defensive copy.
- `PlayerHeadFontManager.nextSlot` (line 21) — switch to `AtomicInteger`; remove the `synchronized` block inside `computeIfAbsent` (line 78) — known footgun on `ConcurrentHashMap`.
- Cache a `Set<String>` of player names with confirmed face PNGs to skip `new File(...).exists()` per `getHeadGlyph` call.
- Add a fast prefix gate to `LogParser.processLine` so 95 % of log lines are skipped before any regex runs.
- `LogParser.runIncrementalParse` line 160 — replace `RandomAccessFile.readLine()` + `new String(...,UTF_8)` re-encode with a `BufferedReader` over `FileInputStream` already positioned at `lastByteOffset`.
- `EventManager.getPlayerName()` (line 598) — pre-check with the existing `isUUID()` regex (line 654) instead of catching `IllegalArgumentException` from `UUID.fromString` on the hot scoreboard path.
- Cache a single lower-case alias map at config load to drop the three `containsKey` calls in `EventManager.getPlayerName()` and `LogParser.normalizePlayer()`.
- Bump `DashboardWebServer.scheduler` to 3 threads (or use a one-shot thread for historical parse) so the historical parse can't starve incremental updates.
- Mark `cachedWorldSizeMb` and `lastWorldSizeCheck` as `volatile`, or fold them into the immutable `LiveMetricsSnapshot`.
- Bound `UuidCache.networkResolved` / `networkFailed` with an LRU (e.g., access-order `LinkedHashMap` capped at 1024) to avoid unbounded growth from web clients.

## Suggested execution order

1. Throttle `UuidCache.refresh()` (single small change, knocks out the most-called offender).
2. Add streaming `JsonReader` to `EventManager.getStatValueFromDisk`.
3. Memoize `StreakTracker.getStreaks()`.
4. Precompute ignored-player sets at config load.
5. Stream `ApiHandler` response + add gzip.
6. Bound `calculateDirectorySize` (depth + dir scope).
7. Switch `EventManager.save()` to compact JSON; tackle scoreboard diffing.
8. Walk the micro-optimizations list.

Each item above is independently shippable — recommend small PRs per cluster (cache, parser, web).

---

# Risk Evaluation & Regression Test Plan

## C. Risk Evaluation per Change

For every remediation item, "Blast radius" = subsystems that can silently regress; "Failure mode" = the most likely visible bug.

### C1. Throttle `UuidCache.refresh()` (30 s gate)

- **Blast radius:** every code path that depends on a freshly-known player name — scoreboard rendering, leaderboards, `/api/activity`, streak resolution, `EventManager.takeStatsSnapshot`, `DashboardCommand` lookups by name.
- **Failure modes:**
  - A player who first connects after the last refresh but before the gate expires renders as their UUID in the scoreboard / dashboard.
  - `EventManager.takeStatsSnapshot` runs when an admin starts an event; if throttled, brand-new players miss their `0` baseline and effectively get retroactively "credited" with all current stats once they appear.
  - `DashboardCommand` `/dashboard points` style commands that resolve names may show "unknown" briefly.
- **Mitigations to bake in:**
  - Add `forceRefresh()` (no throttle) for `init`, `startEvent`/`takeStatsSnapshot`, and explicit `/dashboard reload`.
  - On `ServerPlayConnectionEvents.JOIN`, push the joining player's name+UUID directly into the maps (bypass file read).

### C2. Streaming `JsonReader` in `EventManager.getStatValueFromDisk`

- **Blast radius:** every event score that reads from disk for offline players (i.e., everything except the players currently online whose values come from `getStatValueFromPlayer`).
- **Failure modes:**
  - Drift between `getStatValueFromDisk` and `getStatValueFromPlayer` — a numeric mismatch means scores tick unexpectedly when a player goes online/offline mid-event.
  - Tide-mod / BetterNether sums computed differently (registry iteration vs. JSON keyset scan).
  - Malformed stats files: current code returns `0` via blanket `catch`. Streaming code must do the same — partial-state must not poison the running total.
  - Skipping unknown sub-objects requires `reader.skipValue()`; missing one will throw `IllegalStateException`.
- **Mitigations to bake in:**
  - Add a parallel-run mode in dev (compute both old DOM and new streaming, log if they differ) for one tick on first launch.
  - Keep the outermost `try/catch` returning `0`.

### C3. Memoize `StreakTracker.getStreaks()`

- **Blast radius:** `daily_streak` event scores; the streaks tab in the dashboard frontend (if exposed).
- **Failure modes:**
  - Across midnight in `streak_timezone` the cached snapshot is stale; a player's "qualifying day" boundary is missed → 0 displayed instead of N+1.
  - When `dashboard_cache.json` is overwritten by the incremental parser, the cache must be invalidated; otherwise the streak shown is up to TTL minutes behind reality.
  - Race: the parser writes the file mid-`getStreaks()` call → mtime read may pick up the new file but the in-memory map still reflects the old read.
- **Mitigations to bake in:**
  - Invalidate when `cacheFile.lastModified()` changes OR when the local date in `streak_timezone` changes vs. the cached snapshot's `today`.
  - Use `volatile Snapshot` reference + atomic compare-and-set semantics; do not synchronize `getStreaks`.

### C4. Precompute ignored-player sets

- **Blast radius:** `EventManager.updatePlayerScore` (line 281), `LogParser.processLine` (lines 223 / 235), `ApiHandler` (line 695).
- **Failure modes:**
  - Config hot-reload (`/dashboard reload`) doesn't rebuild the precomputed sets → admins add an ignored player but they keep showing on the scoreboard.
  - Mixed-case names in `ignored_players` config — comparison must lowercase both sides; missing one side will fail to ignore.
  - Offline-UUID derivation: `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` must use the exact name capitalization the server uses for that player. Pre-compute both `ignoredOfflineUuids` (for offline) and online players resolved by name lowercase.
- **Mitigations to bake in:**
  - Hook the precompute into `DashboardConfig.load()` and any reload path.
  - Unit-test name → offline-UUID parity against vanilla Minecraft.

### C5. Stream `/api/activity` via `JsonWriter`

- **Blast radius:** the entire dashboard frontend (`mc-activity-heatmap-v13.html`). The streamed JSON shape must be byte-for-byte equivalent (modulo whitespace).
- **Failure modes:**
  - Field omissions when `playerDailyRaw` is empty — the frontend may default differently than today.
  - `ignored_players` array placement — if added before `daily` in stream order, JSON parsers don't care, but unit tests that compare strings will.
  - Chunked transfer with `sendResponseHeaders(200, 0)` keeps the connection alive — must close output stream in a `finally`/try-with-resources.
  - Gzip: only respond gzipped when `Accept-Encoding: gzip` is in the request; some lightweight clients (and Cursor's URL probes) won't.
  - Concurrent requests now share more state (the UuidCache snapshot, the streaming filter logic) — verify thread safety.
- **Mitigations to bake in:**
  - Snapshot-test the JSON output against the current implementation for a fixed `dashboard_cache.json` fixture.
  - Negotiate gzip strictly via `Accept-Encoding`.

### C6. Bound `calculateDirectorySize`

- **Blast radius:** `liveSnapshot.worldSizeMb` shown in the live tab.
- **Failure modes:**
  - Reported size differs from old behavior on Pterodactyl hosts (which exposes `/home/container`). Users may report "world shrank!".
  - Symlinks loops (a symlinked `world` → `world_backup`) cause infinite recursion in `Files.walkFileTree` if not guarded with `FOLLOW_LINKS=false`.
  - Permission denied in subdirs — old `listFiles` returned `null` and silently skipped; `Files.walkFileTree` throws `AccessDeniedException` per file. Must catch `IOException` per visit.
  - Depth cap may underreport if the world has deep DIM directories (`world/DIM-1/region/...`).
- **Mitigations to bake in:**
  - Sum over `${stats_world_name}` only (defaults to `world`); document the change in CHANGELOG.
  - Use `EnumSet.noneOf(FileVisitOption.class)` (don't follow symlinks).

### C7. Compact JSON for `EventManager.save()`

- **Blast radius:** `dashboard_events.json` only.
- **Failure modes:**
  - Manual-edit ergonomics worsen for admins; not a runtime regression.
  - Some text editors balk at very long single-line JSON; verify file remains under reasonable length.
- **Mitigations to bake in:**
  - Provide a `--pretty-print events` admin command or doc snippet (`jq . dashboard_events.json > pretty.json`).

### C8. Scoreboard diffing

- **Blast radius:** in-game sidebar for every online player.
- **Failure modes:**
  - Newly-joined player or freshly-resync'd objective doesn't get the full score list because diff skipped "unchanged" entries.
  - Renaming an event title doesn't propagate because diff keys on score value, not text/format.
  - Toggling `hiddenScoreboards` stops working if the diff filter sees "no change" and skips the SIDEBAR-null packet.
  - Player aliases changing live — the displayed name doesn't refresh until the underlying score changes.
- **Mitigations to bake in:**
  - Track `(scoreValue, displayText, numberFormatText)` triple per (objective, holder); resend if *any* component changed.
  - On player join (`ServerPlayConnectionEvents.JOIN`) or `setScoreboardHidden` toggle, reset that player's "last sent" cache so the next tick sends everything.

### C9. Pre-resolve `OBSIDIAN_IDS` to `Set<Item>`/`Set<Block>`

- **Blast radius:** `obsidian_placed` / `obsidian_mined` event types.
- **Failure modes:**
  - Resolution must run after `SERVER_STARTED` (registries are frozen by then). If resolved earlier (e.g., in a `static` block), BetterNether IDs may be missing.
  - Items removed by a mod uninstall between sessions: stale IDs in the set are harmless (`getStat` returns 0).
- **Mitigations to bake in:**
  - Lazy-init from the first `init(server)` call; clear on shutdown for hot-reload safety.

### C10. `LogParser` fast prefix gate

- **Blast radius:** historical and incremental session reconstruction.
- **Failure modes:**
  - Missing one of the lifecycle phrases ("Loading Minecraft", "Environment:", "Starting minecraft server version", "Stopping server", "joined the game", "left the game") makes that day's sessions silently zero out or run past server crashes.
  - Server-mod variants that rephrase join/leave (rare on vanilla Fabric) get skipped.
- **Mitigations to bake in:**
  - Centralize the prefix list as `private static final String[] LIFECYCLE_MARKERS = { ... }` and assert each is reachable in the gate.

### C11. `LogParser.closeSession` map hoisting

- **Blast radius:** numerical accuracy of `daily`, `playerDailyRaw`, `hourly`.
- **Failure modes:**
  - Pure refactor; the only risk is mis-typing the loop and double-counting a segment.
- **Mitigations to bake in:**
  - Snapshot a fixture log file's parsed totals before/after.

### C12. Incremental parser UTF-8 fix

- **Blast radius:** `latest.log` parsing between rotations.
- **Failure modes:**
  - Switching from `RandomAccessFile.readLine()` to a `BufferedReader` over `FileInputStream` makes byte-offset tracking harder — the reader buffers ahead, so `getFilePointer()`-equivalent must be replaced by a `CountingInputStream` wrapper. Mis-tracking causes either dropped lines (offset advanced past unread bytes) or duplicated lines (offset rewound).
  - Multi-byte UTF-8 boundary at `lastByteOffset` (e.g., player chat with emoji) — if the previous run wrote `lastByteOffset` mid-codepoint, the new reader corrupts the next line.
  - Log rotation detection (`latestLog.length() < lastByteOffset`) must remain.
- **Mitigations to bake in:**
  - Always seek to a *line boundary* — when storing `lastByteOffset`, store the position *after* `\n`.
  - Round-trip test: write a log with non-ASCII names, run incremental parse twice, assert no double-counting.

### C13. Polish-misc bundle

- `getColorMap` unmodifiable view — verify `PlayerMetaHandler.handle` (line 650) doesn't mutate; it only reads, so OK.
- `AtomicInteger nextSlot` — must seed with `max(loaded slot) + 1`. Bug surface: off-by-one if seeded with `size()`.
- `isUUID` gate in `getPlayerName` — alias map keys can be either UUID strings or names; gate must only short-circuit the *UUID lookup*, not the alias lookup that follows.
- Bounded LRU on `UuidCache.networkResolved` — eviction order matters; LRU on access (`LinkedHashMap(_, _, true)`) needs external sync. Wrap with `Collections.synchronizedMap`.
- `LinkedHashMap → HashMap` in `StatsAggregator` — frontend must not depend on insertion order. Verify the leaderboard rendering sorts client-side (it does, by value).
- LiveMetrics fields — `cachedWorldSizeMb` / `lastWorldSizeCheck` are read in the scheduler thread only; folding into the snapshot is the cleanest fix.

---

## D. Regression Test Plan

Tests are grouped by suite; ✅ items are minimum bar to ship; ⚠️ are recommended for confidence on a live server.

### D1. Build / Static Checks

- ✅ `./gradlew build` succeeds with no new warnings.
- ✅ `./gradlew checkstyle` (if configured) is clean.
- ✅ `./gradlew runServer` (Fabric dev env) boots without `ClassNotFound` / `NoSuchMethodError`.
- ⚠️ Run `jdeps --jdk-internals` to confirm no new internal API usage was introduced by `JsonWriter` streaming or `Files.walkFileTree`.

### D2. UuidCache throttle (C1)

- ✅ Cold start: `EventManager.init` resolves all online players' names within 1 tick (verify via `/dashboard list` or scoreboard).
- ✅ A new player joining mid-session has their name appear on the scoreboard within 1 tick (requires the JOIN-hook bypass).
- ✅ Within the 30 s window, calling `refresh()` 1000× returns immediately and only one disk read is performed (instrument with a counter or `LOGGER.debug`).
- ✅ `/dashboard reload` (or whatever explicit refresh exists) bypasses the throttle.
- ⚠️ Concurrent calls from the tick thread + web thread + scheduler must produce identical maps (no torn reads). Use `volatile` references + immutable map snapshots.

### D3. EventManager streaming JSON (C2)

- ✅ For a fixed `world/stats/<uuid>.json` fixture, `getStatValueFromDisk(playtime)` returns the same int as the previous DOM implementation. Repeat for: `mob_kills`, `fewest_deaths`, `damage_dealt`, `player_kills`, `fish_caught` (with and without `tide` mod), `daily_streak`, `blocks_placed`, `blocks_mined`, `obsidian_placed`, `obsidian_mined`.
- ✅ Malformed stats file (truncated, invalid JSON) returns `0` (no crash, no poisoned counters).
- ✅ Empty stats file (`{}`) returns `0`.
- ✅ Stats file with extra unknown categories (e.g., `mod:custom_stat`) is skipped without throwing.
- ⚠️ Parity check: run an event live, force one player offline, verify their score doesn't change between the last `getStatValueFromPlayer` tick and the first `getStatValueFromDisk` tick after disconnect.

### D4. StreakTracker memoization (C3)

- ✅ First call to `getStreaks()` reads disk; second call within TTL reads zero bytes (instrument).
- ✅ When `dashboard_cache.json` is overwritten (incremental parser run), the next `getStreaks()` call sees the new data within one tick.
- ✅ Crossing midnight in `streak_timezone` invalidates the cache and recomputes `today` / `yesterday`.
- ✅ A player who hits 60 minutes today has their streak go from N → N+1 within ≤ TTL minutes after the parser updates the cache.
- ⚠️ Concurrent reads (event tick + web request) must observe a consistent snapshot — never a half-built map.

### D5. Ignored-player sets (C4)

- ✅ Adding a name to `ignored_players` and reloading config removes the player from the scoreboard within 1 tick.
- ✅ Mixed-case ignored entries (e.g., `Steve` vs config `steve`) are honored.
- ✅ Offline player whose offline-UUID equals `nameUUIDFromBytes("OfflinePlayer:Name")` is excluded from `updateScores`.
- ✅ Removing a name from `ignored_players` re-introduces them after reload.
- ⚠️ Stress: 100 ignored entries — per-tick CPU drops vs. before (microbench on `updatePlayerScore`).

### D6. /api/activity streaming + gzip (C5)

- ✅ `curl http://localhost:<port>/api/activity` returns JSON identical (after `jq -S .`) to the prior implementation, for the same `dashboard_cache.json`.
- ✅ With `ignored_players` configured: `daily` totals match the recomputed sums (sum over `playerDailyRaw[date]/60.0`, rounded to 2 decimals).
- ✅ `Accept-Encoding: gzip` returns gzipped body with `Content-Encoding: gzip`; without it, plain JSON.
- ✅ Frontend `mc-activity-heatmap-v13.html` loads all tabs (Heatmap, Sessions, Streaks, Leaderboards, Live) without console errors.
- ✅ Concurrent requests (load test, 50 parallel `curl`s) all return well-formed JSON with no truncation.
- ⚠️ Heap profile: peak heap during a single `/api/activity` request drops vs. the DOM rewrite (use `jcmd <pid> GC.heap_info` before/after).

### D7. Bounded directory size (C6)

- ✅ Live tab `worldSizeMb` reports a number > 0 within 10 minutes of boot.
- ✅ Symlink loop (`ln -s world world/loop`) does not crash or hang the live metrics scheduler.
- ✅ Permission-denied subdir is skipped silently (no `WARN` spam per file).
- ✅ Reported size ≈ `du -sm <world>` ± 1 %.
- ⚠️ On a large world (>10 GB), the walk completes in <2 s.

### D8. Compact event saves (C7)

- ✅ After an event start/stop, `dashboard_events.json` is valid JSON (`jq . dashboard_events.json` succeeds).
- ✅ File size is smaller (≥ 30 % reduction for a typical 5-event payload).
- ✅ Restarting the server reloads events identically (round-trip).

### D9. Scoreboard diffing (C8)

- ✅ A new player joining mid-event sees the full scoreboard within 1 tick (no missing rows).
- ✅ Toggling `setScoreboardHidden(true)` clears the sidebar; `false` restores it on next tick.
- ✅ Renaming an event title (if supported) updates the sidebar header within 1 tick.
- ✅ Player alias change (config reload) refreshes the displayed name.
- ✅ Server-thread CPU time spent in `updateScoreboard` drops measurably (use `jfr` recording).
- ⚠️ Two simultaneous events: each player's selected event renders correctly; switching preference resends.

### D10. Obsidian registry pre-resolution (C9)

- ✅ `obsidian_placed` event with vanilla obsidian only — score matches pre-change.
- ✅ With BetterNether installed, all 24 modded obsidian variants tracked.
- ✅ Without BetterNether installed, the set contains only the 2 vanilla IDs (no `null` or empty entries).
- ✅ Per-tick CPU on `getStatValueFromDisk(obsidian_*)` drops vs. pre-change (microbench).

### D11. Log parser changes (C10, C11, C12)

- ✅ Historical parse on the project's existing `logs/` dir (incl. `2026-02-23-2.log.gz`) produces identical `dashboard_cache.json` to the pre-change implementation. Diff with `jq -S . old.json new.json`.
- ✅ Crash-recovery: a log with `Starting minecraft server` mid-day (no preceding `Stopping server`) caps ghost sessions at 4 hours.
- ✅ Player with non-ASCII chat (e.g., a chat line containing emoji) does not corrupt the next line in incremental parse.
- ✅ Log rotation: when `latest.log` shrinks, `lastByteOffset` resets to 0 and `activeSessions` is preserved.
- ✅ `Advent/Hanger` normalization still groups the two players into a single session.
- ⚠️ Ten-thousand-line synthetic log: regex-gated parsing throughput is ≥ 5× faster than pre-change.

### D12. Polish-misc bundle (C13)

- ✅ `PlayerHeadFontManager` slot allocation is monotonic across restarts (write `slots.json`, restart, verify `nextSlot == max+1`).
- ✅ Concurrent `getHeadGlyph` calls from many web threads don't deadlock or duplicate slots (stress test 100 unique player names).
- ✅ `getPlayerName` for a non-UUID alias key still resolves through the alias map (no `IllegalArgumentException` log spam).
- ✅ `UuidCache.networkResolved` does not exceed 1024 entries even after 10 000 unique web lookups (LRU eviction working).
- ✅ Leaderboards JSON output matches pre-change after `LinkedHashMap → HashMap` (sort-order independent).

### D13. Long-running soak

- ⚠️ Run the modded server for 24 h with ≥ 5 simulated players cycling join/leave every 5 minutes and a `/api/activity` poll every 30 s.
  - Heap usage stays bounded (no upward trend) — verify with `jstat -gcutil <pid> 60s`.
  - Average MSPT contribution from the dashboard tick (200-tick scheduler) drops vs. baseline.
  - No `OutOfMemoryError`, no `RejectedExecutionException`, no growing thread count.
  - `dashboard_cache.json` reaches a steady-state size and only grows by ~one day's data per real day.

### D14. Rollback safety

- ✅ Each PR is independent and can be reverted without breaking the others (CI gate: revert one commit, `./gradlew build` still passes).
- ✅ On-disk file formats (`dashboard_cache.json`, `dashboard_events.json`, `meta.json`, `slots.json`) remain forward+backward compatible — the optimized writer's output is consumable by the prior reader and vice versa.

## E. Suggested Test Fixtures to Add to the Repo

- `backend/src/test/resources/fixtures/stats/<uuid>.json` — one canonical stats file per stat type the events scoring path reads.
- `backend/src/test/resources/fixtures/logs/2026-02-23-2.log.gz` — already present; pin a "golden" `dashboard_cache.json` produced by the current parser as the regression baseline.
- `backend/src/test/resources/fixtures/cache/dashboard_cache.golden.json` — golden output for `/api/activity` to snapshot-test the streaming writer.
- `backend/src/test/resources/fixtures/usercache.json` and `meta.json` — exercises both `UuidCache` sources.