package com.playtime.dashboard;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.playtime.dashboard.web.DashboardWebServer;
import com.playtime.dashboard.config.DashboardConfig;

/**
 * Entry point for the Fabric Activity Dashboard mod.
 * Registers lifecycle hooks to start the embedded HTTP dashboard server when the Minecraft
 * server starts, and to gracefully shut it down when the server stops.
 */
public class FabricDashboardMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("fabric-dashboard");
    private DashboardWebServer webServer;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Fabric Activity Dashboard...");
        
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DashboardConfig.load();
            webServer = new DashboardWebServer(server);
            webServer.start();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (webServer != null) {
                webServer.stop();
            }
        });
    }
}
