# Minecraft Activity Dashboard

## Overview
This project provides a comprehensive activity dashboard for Minecraft servers. Built as a Java Fabric mod, it tracks player join and leave events, aggregates in-game statistics, and provides real-time performance monitoring via an interactive dashboard served directly from the server—no external web servers or databases required.

## How It Works
The mod features an embedded lightweight HTTP server that runs quietly in the background of your Minecraft server. It automatically parses your server's log files (including compressed `.log.gz` archives) to calculate player session times and caches this data in a JSON file. When you visit the dashboard, the embedded server delivers a modern HTML/JS interface alongside the cached data, ensuring virtually zero impact on server performance.

## Dashboard Features

### 📅 Activity Heatmap
*   **GitHub-Style Calendar**: Visualize server activity over months with intensity-coded tiles.
*   **Player Filtering**: Search and filter the heatmap by player to see their specific activity patterns and busiest days.
*   **Interactive Day Details**: Click any day to see a detailed player breakdown, including a pie chart of playtime distribution and an hourly activity graph.
*   **Deep Linking**: Navigate directly from a player's profile to their busiest day in history.

### 🔍 Global Player Search
*   **Omni-Search**: Quickly find any player across the entire server history from the main header.
*   **Shortcut Support**: Access the search bar instantly using `Ctrl+K` (or `Cmd+K` on Mac).
*   **Instant Drill-Down**: Selecting a player from the search results opens their full profile modal with 3D skin and session analytics.

### 🏆 Global Leaderboards
*   **Automatic Aggregation**: The mod scans Minecraft world stats to build leaderboards for:
    *   Total Distance Traveled (converted to km)
    *   Player Kills & Mob Kills
    *   Deaths & Damage Taken/Dealt
    *   Special stats like Totems Used, Event Points, and more.
*   **Interactive Sorting**: Search and sort leaderboards by any metric and click players to view their full activity profile.

### 👤 Intelligent Player Profiles
*   **3D Skin Viewer**: Real-time 3D player model rendering with walking animations.
*   **Session Analytics**: View total playtime, session counts, average session length, and the elusive "Longest Session."

### 🗺️ Live Dynmap Integration
*   **Embedded World Map**: View your live [Dynmap](https://www.curseforge.com/minecraft/mc-mods/dynmap) directly inside the dashboard.
*   **State-Preserving Tabs**: Switch between playtime stats and the live map without losing your zoom level or position.
*   **Togglable**: Easily enable or disable the map tab via configuration.

### ⚡ Live Server Performance
*   **Real-Time Metrics**: Monitor Server TPS, MSPT (Tick Times), CPU usage, and JVM Memory directly from the dashboard.
*   **Container Optimized**: Specialized support for Pterodactyl/Docker environments, reporting actual folder size (`/home/container`) rather than misleading host partition data.
*   **Live Player List**: See who is online right now. Click any online player to jump straight to their full activity history.
*   **Header KPI Widget**: Monitor players, TPS, and MSPT instantly from the main header, regardless of which tab you are currently viewing.
*   **Color-Coded Status**: Visual health indicators (Green/Yellow/Red) for at-a-glance monitoring.

### 🎖️ Server Events & Competitions
*   **Multiple Concurrent Events**: Run multiple competitions simultaneously (e.g., "Mega Mining Mayhem" and "Playtime Challenge").
*   **Admin-Driven Events**: Create timed competitions using `/dashboard event create`. Supported types: `playtime`, `mob_kills`, `blocks_placed`, `blocks_mined`, `fewest_deaths`, `damage_dealt`, `player_kills`, `fish_caught`, `daily_streak`, `obsidian_placed`, and `obsidian_mined`.
*   **Live Scoreboards**: Progress is tracked in real-time on a premium in-game sidebar. Each player can choose which event to track via `/dashboard event scoreboard`.
    *   **Real-time Timer**: Displays time remaining directly on the sidebar.
    *   **Sleek Layout**: Scores are right-aligned for better readability.
    *   **Privacy Controls**: Players can hide/show their personal scoreboard.
*   **Inverted Leaderboards**: Support for `fewest_deaths` where the lowest score wins, correctly sorted on the Minecraft sidebar.
*   **Player Head Rendering**: Native rendering of player heads on the sidebar using a dynamic resource pack.
*   **Web Leaderboard**: A dedicated "Events" tab on the dashboard with individual cards for each active event, live timers, and interactive leaderboards.
*   **Automatic Rewards**: Earn "All-Time Points" for top placements, tracked on a permanent server-wide leaderboard.

### 🔥 Daily Playtime Streaks
*   **Activity Milestones**: Automatically tracks players who reach 60 minutes of playtime in a calendar day.
*   **Log-Derived Persistence**: Streaks are calculated from historical activity logs, ensuring consistency even after server restarts.
*   **Visual Recognition**: Current streaks are displayed in player profiles and have their own dedicated "Daily Playtime Streak" leaderboard.

## Getting Started

### Prerequisites
- Fabric-compatible Minecraft Server
- Java Development Kit (JDK) 21

### Building Locally
The project is built using Gradle. To compile the mod and synchronize the web assets:

```bash
cd backend/
./gradlew build
```

The `syncWebAssets` task automatically copies the frontend files from `frontend/` into the mod's resources before compilation, ensuring a single source of truth for UI changes.

### Installation
Once built, drop the resulting `.jar` file from `backend/build/libs/` into your server's `mods` folder and start the server.

By default, the dashboard is accessible at `http://<your-server-ip>:8105`.

## Server Commands

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/dashboard event create <type> <hours> <title>` | Start a new timed event. | OP (2) |
| `/dashboard event stop [id]` | Stop a specific event (or the latest if no ID provided). | OP (2) |
| `/dashboard event list` | List all active events and their IDs. | All |
| `/dashboard event status [id]` | View details and remaining time for an event (optional ID for detailed top-10). | All |
| `/dashboard event setlength <id> <hours>` | Update the duration of an active event. | OP (2) |
| `/dashboard event scoreboard <id>` | Switch your personal sidebar to track a specific event (unhides if hidden). | All |
| `/dashboard event scoreboard hide` | Hide your personal event sidebar. | All |
| `/dashboard event scoreboard show` | Show your personal event sidebar. | All |
| `/dashboard event clearpoints all [amount]` | Clear or reduce all-time points for all players. | OP (2) |
| `/dashboard event clearpoints user <name> [amount]` | Clear or reduce all-time points for a specific player. | OP (2) |
| `/dashboard reparse` | Force a full re-parse of all server logs to refresh activity data. | OP (2) |
| `/dashboard reload` | Reload the mod configuration. | OP (2) |
| `/dashboard debug` | Print EventManager persistence health (dirty flag, save counters, executor status). | OP (2) |
| `/dashboard debug worldsize` | Print world-size executor state: cached size, last/next walk timing, config, and executor status. Submits a thread-identity check to the server log. | OP (2) |
| `/dashboard debug uuid <identifier>` | Inspect UUID/Name cache state (Runtime/Disk/Network), last network attempt timing, and cooldown status. | OP (2) |

## Configuration
Settings are managed via `config/dashboard-config.json`. The file is automatically generated on first run.

| Setting | Default | Description | Hot-Reloadable |
| :--- | :--- | :--- | :--- |
| `config_version` | `1` | Configuration schema version. | Yes |
| `web_port` | `8105` | The port the dashboard web server listens on. | **No (Restart Required)** |
| `logs_directory` | `""` | Custom path to server logs (defaults to `./logs`). | **No (Restart Required)** |
| `stats_world_name` | `"world"` | The name of your world folder to read stats from. | **No (Restart Required)** |
| `tab_title` | `"Playtime Dashboard"` | The browser tab title. | Yes |
| `dashboard_title` | `"Activity Dashboard"` | The main heading on the dashboard. | Yes |
| `custom_logo_path` | `""` | Path to a local `.jpg` or `.png` for the dashboard logo. | Yes |
| `enable_dynmap` | `true` | Toggle the Dynmap tab on/off. | Yes |
| `dynmap_url` | `""` | The URL of your Dynmap instance. | Yes |
| `ignored_players` | `[]` | List of player names to exclude from all stats. | Yes |
| `max_concurrent_events` | `3` | Maximum number of events that can run at once. | Yes |
| `streak_timezone` | `"America/Toronto"` | Timezone used for daily streak calculation. | Yes |
| `incremental_update_interval_minutes` | `5` | Frequency of log scanning for new data. | Yes |
| `leaderboard_update_interval_minutes` | `10` | Frequency of world stats aggregation. | Yes |
| `fetch_player_heads` | `true` | Enable fetching skin textures from Mojang API. | Yes |
| `resource_pack_url` | `""` | The URL where clients download the custom font resource pack. | **No (Restart Required)** |
| `enable_live_tab` | `true` | Toggle the Live Metrics tab on/off. | Yes |
| `live_update_interval_seconds` | `3` | Frequency of performance metrics polling. | Yes |
| `world_size_refresh_minutes` | `30` | How often (in minutes) to recompute the world directory size in the background. | Yes |
| `world_size_max_depth` | `8` | Maximum directory recursion depth for the world size walk. | Yes |
| `uuid_refresh_cooldown_seconds` | `3600` | Seconds to wait before re-resolving unknown or failed player names/UUIDs via Mojang. | Yes |

## Performance & Privacy
- **Zero Database**: No SQL setup required; uses an optimized JSON flat-file cache.
- **Background Processing**: All log parsing and stats aggregation happens on a low-priority background thread to prevent server lag or TPS drops.
- **Case-Insensitive Filters**: Robust privacy controls to hide bots, admins, or specific players from public view.
