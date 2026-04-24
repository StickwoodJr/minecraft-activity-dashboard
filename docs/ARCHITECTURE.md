# Architecture

The Minecraft Activity Dashboard utilizes a unified, single-stack architecture centered entirely around a Java Fabric mod. By eliminating legacy external backends (such as Python/Flask), the project drastically simplifies deployment and maintenance. The frontend is built with vanilla HTML, CSS, and JavaScript, ensuring a lightweight and dependency-free user experience.

Data flows smoothly from the server logs to the user's browser. As the Minecraft server runs, the mod's background scheduler incrementally parses the server logs for join and leave events, computing session lengths and player activity. This aggregated data is saved to a local `dashboard_cache.json` file. When a user requests the dashboard, the mod's embedded JDK HTTP server seamlessly serves the static web assets alongside the cached JSON data via lightweight API endpoints (`/api/activity` and `/api/player-meta`), completely decoupling the expensive parsing logic from the real-time web serving.
