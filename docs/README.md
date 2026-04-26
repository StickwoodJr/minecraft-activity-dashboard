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
*   **Admin-Driven Events**: Create timed competitions (e.g., "Most Hours This Week" or "Most Mob Kills") using `/dashboard event create`.
*   **Live Scoreboards**: Progress is tracked in real-time on a premium in-game sidebar, featuring formatted time displays (`Dd Hh Mm Ss`) for playtime and hidden raw scores for a clean look.
*   **Player Head Rendering**: The mod automatically generates a dynamic resource pack mapping player face PNGs to custom Unicode characters. This allows the in-game event scoreboard to render player head icons natively, right beside their names!
*   **Web Leaderboard**: A dedicated "Events" tab on the dashboard provides a live, interactive view of the competition for players outside the game.
*   **Automatic Rewards**: At the end of each event, players earn "All-Time Points" based on their placement, which are tracked on a permanent server-wide leaderboard.
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
All commands require Operator level 2 permission.

| Command | Description |
| :--- | :--- |
| `/dashboard event create <type> <hours> <title>` | Start a new timed event (playtime, mob_kills, blocks_placed, blocks_mined). |
| `/dashboard event stop` | Manually end the current event and distribute points. |
| `/dashboard event status` | View details and remaining time for the active event. |
| `/dashboard event clearpoints all [amount]` | Clear or reduce all-time points for all players. |
| `/dashboard event clearpoints user <name> [amount]` | Clear or reduce all-time points for a specific player. |

## Configuration
Settings are managed via `config/dashboard-config.json`. The file is automatically generated on first run.

| Setting | Default | Description |
| :--- | :--- | :--- |
| `web_port` | `8105` | The port the dashboard web server listens on. |
| `logs_directory` | `""` | Custom path to server logs (defaults to `./logs`). |
| `stats_world_name` | `"world"` | The name of your world folder to read stats from. |
| `tab_title` | `"Playtime Dashboard"` | The browser tab title. |
| `dashboard_title` | `"Activity Dashboard"` | The main heading on the dashboard. |
| `custom_logo_path` | `""` | Path to a local `.jpg` or `.png` for the dashboard logo. |
| `enable_dynmap` | `true` | Toggle the Dynmap tab on/off. |
| `dynmap_url` | `"http://149.56.155.7:8032"` | The URL of your Dynmap instance. |
| `ignored_players` | `["ironfarmbot", ...]` | List of player names to exclude from all stats. |
| `incremental_update_interval_minutes` | `5` | Frequency of log scanning for new data. |
| `leaderboard_update_interval_minutes` | `10` | Frequency of world stats aggregation. |
| `fetch_player_heads` | `true` | Enable fetching skin textures from Mojang API. |
| `resource_pack_url` | `"http://<ip>:8105/respack.zip"` | The URL where clients download the dynamically generated custom font resource pack. Automatically synced to `server.properties`. |
| `enable_live_tab` | `true` | Toggle the Live Metrics tab on/off. |
| `live_update_interval_seconds` | `3` | Frequency of performance metrics polling. |

## Performance & Privacy
- **Zero Database**: No SQL setup required; uses an optimized JSON flat-file cache.
- **Background Processing**: All log parsing and stats aggregation happens on a low-priority background thread to prevent server lag or TPS drops.
- **Case-Insensitive Filters**: Robust privacy controls to hide bots, admins, or specific players from public view.
