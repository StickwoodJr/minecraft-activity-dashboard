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
By default, the dashboard web server listens on port `5000`. You can access the dashboard by navigating to `http://<your-server-ip>:5000` in your web browser. (Port and other settings can be configured via `dashboard-config.json` in your server's config directory).
