# Architecture

The Minecraft Activity Dashboard utilizes a unified, single-stack architecture centered entirely around a Java Fabric mod. This design eliminates external backends (databases, web servers) to ensure zero-overhead deployment while maintaining high performance.

## Core Components

### 1. Embedded High-Performance Web Server
The mod hosts a dedicated, thread-pooled HTTP server using the JDK's native `com.sun.net.httpserver`. 
- **Static Assets**: Serves the single-page application (SPA) directly from the JAR resources.
- **REST APIs**: Provides lightweight JSON endpoints for activity data, live metrics, and leaderboards.
- **Gzip Negotiation**: All high-volume API responses are compressed on-the-fly to minimize network bandwidth.

### 2. Intelligent Data Processing
Data flows from multiple server sources into a unified analytical engine:
- **Log Processor**: An incremental, index-gated parser that scans Minecraft logs (`latest.log` and compressed archives). It uses fast-prefix gating to skip non-lifecycle lines, significantly reducing CPU overhead during historical reparsing.
- **Stats Streaming**: Instead of loading full DOM trees, the engine uses **GSON Streaming** to process multi-megabyte Minecraft stats files. This maintains a flat memory profile even on servers with hundreds of players.
- **Streak Engine**: A memoized graph builder that calculates daily playtime milestones across the server's history, optimized with TTL-based snapshots.

### 3. Real-time In-Game Integration
The mod bridges the gap between the web dashboard and the live game world:
- **Event Manager**: Manages multiple concurrent timed competitions (playtime, mining, mob kills, etc.).
- **Scoreboard Packet Diffing**: A sophisticated network optimization that tracks the state of each player's sidebar. It only emits update packets when values or formatting actually change, drastically reducing packet churn.
- **3D Player Rendering**: The dashboard integrates a 3D skin viewer that fetches textures via a bounded LRU cache to prevent memory bloat from repeated skin requests.

## Data Persistence
The system uses a **Zero-Database** approach:
- **`dashboard_cache.json`**: Stores historical activity, session data, and daily aggregates.
- **`dashboard_events.json`**: Persists active and historical competition states.
- **`usercache.json`**: Leveraged alongside a local throttled UUID cache to resolve player identities without redundant disk I/O.

## Frontend Strategy
The user interface is a dependency-free, vanilla HTML/JS application:
- **State-Preserving Navigation**: Uses a custom tab manager to switch views (Map, Stats, Live) without reloading page state or losing Dynmap positioning.
- **Client-Side Aggregation**: Offloads sorting and filtering logic to the browser, keeping the backend API lean and focused on data delivery.
- **Responsive Aesthetics**: Modern, premium design with dark mode support and interactive visualizations (D3.js-style heatmaps).
