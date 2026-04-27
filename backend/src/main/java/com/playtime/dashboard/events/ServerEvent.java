package com.playtime.dashboard.events;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a single server-wide event tracking specific player statistics.
 */
public class ServerEvent {
    public String id;
    public String title;
    public String type; // e.g., "playtime", "blocks_placed", "blocks_mined", "mob_kills"
    public long startTime;
    public long endTime;
    public boolean isActive;
    public boolean lowerIsBetter = false;

    // Map of UUID string to the value of the stat when the event started
    public Map<String, Integer> initialStats = new HashMap<>();
    
    // Map of UUID string to the current delta (score)
    public Map<String, Integer> currentScores = new HashMap<>();

    public ServerEvent() {}

    public ServerEvent(String id, String title, String type, long startTime, long endTime) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
        this.isActive = true;
    }
}
