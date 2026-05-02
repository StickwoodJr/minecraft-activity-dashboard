# Minecraft Activity Dashboard

A high-performance Fabric mod that tracks player activity and provides a beautiful, interactive web dashboard.

## Features

-   🔥 **Interactive Heatmap**: Visualize server activity over months and years.
-   📊 **Player Statistics**: Detailed breakdowns of playtime, sessions, and averages.
-   🏆 **Leaderboards**: Competitive tracking for kills, deaths, blocks, and more.
-   📅 **Event System**: Automated and manual server-wide competitions.
-   ⚡ **Live Metrics**: Real-time TPS, MSPT, and CPU monitoring.
-   🔄 **Identity Normalization**: Merge multiple accounts/aliases into a single display identity.
-   🛡️ **Privacy Controls**: Easily ignore staff or bot accounts from all tracking.

## Configuration

The mod generates a `dashboard-config.json` in your server's `config/` directory.

### Identity Management & Aliases

You can group multiple players (by name or UUID) into a single "Synthetic Identity" using the `player_aliases` map. This is useful for players with alt accounts or those who have changed their names.

```json
\"player_aliases\": {
  \"Stickwood_Jr\": \"Stickwood\",
  \"Stickwood_Alt\": \"Stickwood\",
  \"Stickwood_Jr_UUID\": \"Stickwood\"
}
```

When aliases are defined:
1. All playtime and stats from both accounts are **merged** into the target name (\"Stickwood\").
2. **Daily Streaks** are shared; activity on any aliased account contributes to the single identity's streak.
3. The dashboard will display the target name exclusively.

### Primary Head Configuration

By default, the dashboard tries to fetch the Minecraft skin head for the display name. If your display name is synthetic (e.g., \"Advent/Hanger\"), you should specify which real Minecraft account should provide the avatar:

```json
\"primary_player_heads\": {
  \"Advent/Hanger\": \"Advent\"
}
```

## Commands

-   `/dashboard status`: Show web server status and port.
-   `/dashboard rebuild`: Manually trigger a full log and stats re-parse.
-   `/dashboard debug rebuild-meta`: Force-clear the player head cache and re-fetch all metadata.
-   `/dashboard event start <title> <type> <hours>`: Start a server event.
-   `/dashboard event stop <id>`: Manually end an event.

## API Endpoints

-   `/api/activity`: Full historical playtime data.
-   `/api/leaderboards`: Statistical rankings.
-   `/api/live`: Current server performance and online players.
-   `/api/events`: Active and historical event data.

## License

MIT
