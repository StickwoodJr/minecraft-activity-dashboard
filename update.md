# Playtime Mod Update Log

## Implemented In-Game Stats Tracking

This update successfully implements in-game stats tracking via a background parser and a new set of API endpoints, complete with frontend visualization.

### Backend Changes

1. **Scheduler Upgrade (`DashboardWebServer.java`)**
   - Increased the `ScheduledExecutorService` pool size from 1 to 2 to unlock true parallelism between the log parse task and the new leaderboard aggregation task.

2. **Configuration Updates (`DashboardConfig.java`)**
   - Added `stats_world_name` (default: "world") to locate the stats directory.
   - Added `leaderboard_update_interval_minutes` (default: 10) to separate the heavier stats parsing interval from the incremental log parsing.
   - Added `player_aliases` Map to allow server admins to manually resolve orphaned offline-mode or old UUIDs to a clean display name.

3. **UUID Cache Service (`UuidCache.java`)**
   - Implemented a robust bidirectional UUID <-> Username cache.
   - It primarily reads from the local `usercache.json`.
   - **Mojang API Fallback:** If a UUID is missing from the local cache, it safely attempts to resolve the latest username directly from Mojang's Session server, remembering failures to prevent rate-limit spam.

4. **Stats Aggregator (`StatsAggregator.java`)**
   - Implemented memory-efficient stream parsing using Gson's `JsonReader`.
   - **Filtered Metrics:** Instead of parsing all stats, it strictly extracts 13 targeted metrics: Distance Traveled (km), Players Killed, Mobs Killed, Deaths, Damage Taken (Hearts), Damage Dealt (Hearts), Times Slept, Totems of Undying Used, Times Talked to Villager, Silverfish Killed, Wither Killed, and Ender Dragon Killed.
   - **Unit Conversions:** 
     - Consolidates all `_one_cm` metrics, sums them up, and divides by `100,000` to yield accurate `Distance Traveled (km)`.
     - Divides raw `damage_taken` and `damage_dealt` points by `10` to convert to standard "Hearts".
   - Filters out entirely unresolved UUIDs to keep the leaderboards clean.

5. **New API Endpoints & Tasks**
   - Added `/api/leaderboards` (served by `LeaderboardHandler`) which provides the top players for each stat.
   - Added `/api/player-stats/{username}` (served by `PlayerStatsHandler`) which pipes a specific player's raw JSON file dynamically on demand.
   - Registered a new background task in `DashboardWebServer` that performs atomic atomic writes to `dashboard_leaderboards.json`.

### Frontend Changes (`mc-activity-heatmap-v13.html`)

1. **Tab Navigation**
   - Built a sleek top-level Tab switcher to toggle seamlessly between "Playtime" and "Leaderboards".

2. **Leaderboards View**
   - Created a heavily styled, hardcoded grid specifically designed for the 13 filtered stats.
   - Limits the view to the **Top 10 players** per category.
   - Handles exceptionally long player names gracefully via Flexbox truncations (`text-overflow: ellipsis`) rather than cramping up the numbers.
   - Dynamically appends units (`km`, ` Hearts`) to values based on the stat context.

3. **Player Profile Modal**
   - **In-Game Stats Panel:** Clicking on a player triggers an API fetch to dynamically populate their specific filtered in-game stats.
   - **Unified Design:** Styled the in-game stats section to perfectly match the dark "stat-card" grid layout used for their general Playtime KPIs.
   - **3D Skin Viewer Fixes:** Disabled the continuous spin animation. Hardcoded the player model's Y-rotation to `-0.5` radians to provide a dynamic 45-degree angle presentation instead of a flat front-facing view.