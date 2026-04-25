# Session Notes: Implementing In-Game Stats Tracking

## Goal
Implement in-game stats tracking (distance travelled, blocks broken, etc.) for the Playtime Dashboard mod, while preserving the existing log-based playtime tracking system.

## Constraints & Requirements
1.  **Preserve Playtime Tracking:** The existing log-based playtime tracking (`LogParser.java`, `dashboard_cache.json`) must remain completely untouched and functional.
2.  **Full Fidelity:** Track all stats in their entirety (every specific block, item, mob, and custom stat).
3.  **Extreme Efficiency (CPU/Memory):** The implementation must have the smallest possible memory footprint and avoid impacting server performance.

## The Architectural Plan: "On-Demand & Pre-Calculated"

To achieve full fidelity without loading massive amounts of data into the server's RAM, we will utilize Minecraft's native stat tracking (`world/stats/<uuid>.json`) and split the data access into two distinct patterns:

### 1. Server-Wide Leaderboards (Pre-Calculated Cache)
For the main dashboard overview we will rank every player for every stat.

*   **Background Task:** Add a new job to the existing `DashboardWebServer` scheduler (running on the configured `incremental_update_interval_minutes`).
*   **Streaming Aggregation:** This task will use `Gson`'s `JsonReader` to stream through every `world/stats/*.json` file token-by-token.
*   **Bounded Memory Structure:** As it streams, it maintains a priority queue for every stat category encountered (e.g., `minecraft:mined -> minecraft:diamond_ore`). It only keeps the top values and player names in memory, immediately discarding the rest. Since the user wants ALL players ranked, we will stream the data into a structure `Map<Category, Map<Stat, Map<Player, Integer>>>`. This will take slightly more memory than a Top 10 list but is still much more efficient than keeping the entire JSON tree in memory.
*   **Save to Disk:** Once streaming finishes, it saves this optimized leaderboard data to a new file: `dashboard_leaderboards.json`.
*   **New API Endpoint:** `GET /api/leaderboards` will simply serve this static, pre-calculated JSON file to the frontend.

### 2. Individual Player Profiles (On-Demand Streaming)
When a user wants to see *everything* about a specific player (full fidelity), we will stream their native stats file directly.

*   **New API Endpoint:** `GET /api/player-stats/{username}`
*   **How it works:**
    1.  The server looks up the requested `{username}` in the Minecraft User Cache to get their UUID.
    2.  It locates the corresponding `world/stats/<uuid>.json` file.
    3.  It **streams the file directly to the HTTP response** using a small, fixed-size byte buffer (e.g., 8KB).
*   **Efficiency:** The server never parses or loads the JSON DOM into a Java Object or RAM. It acts as a simple pipe from disk to network.

### 3. Frontend Integration
The `mc-activity-heatmap-v13.html` will be updated to consume these new endpoints:
*   **Leaderboards View:** Fetches `/api/leaderboards` to display top players across various categories.
*   **Player Stats View:** Clicking a player fetches `/api/player-stats/{username}` to display their exhaustive, full-fidelity list of stats.

## Resource Usage Summary
*   **CPU:** Very low. JSON streaming is incredibly fast. Relying on vanilla Minecraft stats avoids adding heavy event listeners to the server tick loop.
*   **RAM:** Extremely low. No massive JSON DOMs are ever loaded into memory. Files are either piped directly to the network (buffer) or stream-parsed into tiny "Top X" objects before being written back to disk.
*   **Disk I/O:** Moderate, but isolated to background threads and executed asynchronously every few minutes.
