# Minecraft Activity Dashboard

## Overview
This project provides a comprehensive activity dashboard for Minecraft servers. Built as a Java Fabric mod, it tracks player join and leave events, calculates session playtimes, and serves a beautiful, interactive GitHub-style heatmap dashboard directly from the Minecraft server—no external web servers or databases required.

## How It Works
The mod features an embedded lightweight HTTP server that runs quietly in the background of your Minecraft server. It automatically parses your server's log files to calculate player session times and caches this aggregated data in a JSON file. When you visit the dashboard, the embedded server delivers a modern HTML/JS interface alongside the cached session data, keeping the performance overhead virtually non-existent.

## Getting Started

### Prerequisites
- Ubuntu (or any Linux distribution)
- Java Development Kit (JDK) 21 (for the Fabric backend)

### Building Locally
The project is built using Gradle. To compile the mod and synchronize the web assets, run the following command from the `backend/` directory:

```bash
cd backend/
./gradlew build
```

This command automatically executes the `syncWebAssets` task, which copies the single-source-of-truth frontend files from `frontend/` into the mod's resource directory before compilation.

### Running
Once built, drop the resulting `.jar` file into your Fabric server's `mods` folder and start the server. 

By default, the dashboard web server listens on port `8105`. You can access the dashboard by navigating to `http://<your-server-ip>:8105` in your web browser.

## Configuration
All settings are managed via `config/dashboard-config.json` in your server's root directory. The file is automatically generated and updated with new fields as they are added.

| Setting | Default | Description |
| :--- | :--- | :--- |
| `web_port` | `8105` | The port the dashboard web server listens on. |
| `tab_title` | `"Playtime Dashboard"` | The browser tab title. |
| `server_name` | `"MC Server"` | Your server's name (used in titles). |
| `dashboard_title` | `"Player Session Activity"` | The main heading on the dashboard. |
| `dashboard_description` | `"Combined playtime..."` | The sub-heading on the dashboard. |
| `custom_logo_path` | `""` | Path to a local `.jpg` or `.png` to replace the default logo. |
| `favicon_path` | `""` | Path to a local `.ico`, `.png`, or `.jpg` for the tab icon. |
| `ignored_players` | `["ironfarmbot", ...]` | List of player names to exclude from all stats. |
| `incremental_update_interval_minutes` | `5` | How often to scan logs for new session data. |
| `fetch_player_heads` | `true` | Whether to fetch player head skins from Mojang. |

## Features
- **Zero Database**: All data is parsed from logs and stored in a lightweight JSON cache.
- **Dynamic Branding**: Customize the title, description, logo, and favicon without touching code.
- **Privacy Controls**: Robust, case-insensitive player ignore list to hide bots or admin accounts.
- **High Performance**: Minimal impact on server TPS; data processing happens in a background thread.
- **Modern UI**: Dark-mode-first, responsive design with interactive heatmaps and 3D skin previews.
