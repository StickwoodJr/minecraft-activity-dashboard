package com.playtime.dashboard.events;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    public Map<String, Integer> initialStats = new ConcurrentHashMap<>();
    
    // Map of UUID string to the current delta (score)
    public Map<String, Integer> currentScores = new ConcurrentHashMap<>();

    public ServerEvent() {}

    public ServerEvent(String id, String title, String type, long startTime, long endTime) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
        this.isActive = true;
    }

    /**
     * Creates a point-in-time snapshot of the event's data.
     * This is used to ensure the background save worker has a consistent view
     * that won't be modified by the server thread during serialization.
     */
    public ServerEvent snapshot() {
        ServerEvent snap = new ServerEvent();
        snap.id = this.id;
        snap.title = this.title;
        snap.type = this.type;
        snap.startTime = this.startTime;
        snap.endTime = this.endTime;
        snap.isActive = this.isActive;
        snap.lowerIsBetter = this.lowerIsBetter;
        // Clone the maps to ensure the snapshot is isolated
        snap.initialStats = new HashMap<>(this.initialStats);
        snap.currentScores = new HashMap<>(this.currentScores);
        return snap;
    }
}
