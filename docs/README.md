# Minecraft Activity Dashboard

## Overview
This project provides a comprehensive activity dashboard for Minecraft servers. Built as a Java Fabric mod, it tracks player join and leave events, aggregates in-game statistics, and serves a beautiful, interactive GitHub-style heatmap dashboard directly from the Minecraft server—no external web servers or databases required.

## How It Works
The mod features an embedded lightweight HTTP server that runs quietly in the background of your Minecraft server. It automatically parses your server's log files (including compressed `.log.gz` archives) to calculate player session times and caches this data in a JSON file. When you visit the dashboard, the embedded server delivers a modern HTML/JS interface alongside the cached data, ensuring virtually zero impact on server performance.

## Dashboard Features

### 📅 Activity Heatmap
*   **GitHub-Style Calendar**: Visualize server activity over months with intensity-coded tiles.
*   **Interactive Day Details**: Click any day to see a detailed player breakdown, including a pie chart of playtime distribution and an hourly activity graph.
*   **Deep Linking**: Navigate directly from a player's profile to their busiest day in history.

### 🏆 Global Leaderboards
*   **Automatic Aggregation**: The mod scans Minecraft world stats to build leaderboards for:
    *   Total Distance Traveled (converted to km)
    *   Player Kills & Mob Kills
    *   Deaths & Damage Taken/Dealt
    *   Special stats like Totems Used, Times Slept, and more.
*   **Interactive Sorting**: Sort leaderboards by any metric and click players to view their full activity profile.

### 👤 Intelligent Player Profiles
*   **3D Skin Viewer**: Real-time 3D player model rendering with walking animations.
*   **Session Analytics**: View total playtime, session counts, average session length, and the elusive "Longest Session."

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

## Configuration
Settings are managed via `config/dashboard-config.json`. The file is automatically generated on first run.

| Setting | Default | Description |
| :--- | :--- | :--- |
| `web_port` | `8105` | The port the dashboard web server listens on. |
| `logs_directory` | `""` | Custom path to server logs (defaults to `./logs`). |
| `stats_world_name` | `"world"` | The name of your world folder to read stats from. |
| `tab_title` | `"Playtime Dashboard"` | The browser tab title. |
| `dashboard_title` | `"Player Session Activity"` | The main heading on the dashboard. |
| `custom_logo_path` | `""` | Path to a local `.jpg` or `.png` for the dashboard logo. |
| `ignored_players` | `["ironfarmbot", ...]` | List of player names to exclude from all stats. |
| `incremental_update_interval_minutes` | `5` | Frequency of log scanning for new data. |
| `leaderboard_update_interval_minutes` | `10` | Frequency of world stats aggregation. |
| `fetch_player_heads` | `true` | Enable fetching skin textures from Mojang API. |

## Performance & Privacy
- **Zero Database**: No SQL setup required; uses an optimized JSON flat-file cache.
- **Background Processing**: All log parsing and stats aggregation happens on a low-priority background thread to prevent server lag or TPS drops.
- **Case-Insensitive Filters**: Robust privacy controls to hide bots, admins, or specific players from public view.
