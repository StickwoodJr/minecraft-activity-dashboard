package com.playtime.dashboard.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.playtime.dashboard.events.EventManager;
import com.playtime.dashboard.events.ServerEvent;
import com.playtime.dashboard.config.DashboardConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EventsHandler implements HttpHandler {
    private static final Gson GSON = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        EventManager manager = EventManager.getInstance();
        java.util.List<ServerEvent> activeEvents = manager.getActiveEvents();
        Map<String, Integer> allTime = manager.getAllTimePoints();

        DashboardConfig cfg = DashboardConfig.get();
        Map<String, Integer> allTimeFiltered = new HashMap<>();
        for (Map.Entry<String, Integer> entry : allTime.entrySet()) {
            if (!cfg.isIgnored(manager.resolvePlayerName(entry.getKey()), entry.getKey())) {
                allTimeFiltered.put(entry.getKey(), entry.getValue());
            }
        }

        JsonObject response = new JsonObject();
        Set<String> allUuids = new HashSet<>(allTimeFiltered.keySet());
        response.add("activeEvents", GSON.toJsonTree(activeEvents));
        for (ServerEvent event : activeEvents) {
            allUuids.addAll(event.currentScores.keySet());
        }
        response.add("allTimePoints", GSON.toJsonTree(allTimeFiltered));
        response.add("streaks", GSON.toJsonTree(com.playtime.dashboard.events.StreakTracker.getInstance().getStreaks()));
        response.add("uuidToName", GSON.toJsonTree(manager.resolveNames(allUuids)));

        byte[] json = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
        exchange.sendResponseHeaders(200, json.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }
}
