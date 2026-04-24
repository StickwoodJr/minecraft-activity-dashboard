package com.playtime.dashboard.parser;

import java.util.HashMap;
import java.util.Map;

public class DashboardData {
    public Map<String, Double> daily = new HashMap<>();
    public Map<String, Map<String, Double>> playerDailyRaw = new HashMap<>();
    public Map<String, SessionData> sessData = new HashMap<>();
    public Map<String, Map<String, double[]>> hourly = new HashMap<>();

    public static class SessionData {
        public int sessions = 0;
        public double avg = 0.0;
        public double longestSession = 0.0;
    }
}
