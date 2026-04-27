package com.playtime.dashboard;

import com.playtime.dashboard.commands.DashboardCommand;
import com.playtime.dashboard.config.DashboardConfig;
import com.playtime.dashboard.events.EventManager;
import com.playtime.dashboard.events.StreakTracker;
import com.playtime.dashboard.web.DashboardWebServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Fabric Activity Dashboard mod.
 * Registers lifecycle hooks to start the embedded HTTP dashboard server when the Minecraft
 * server starts, and to gracefully shut it down when the server stops.
 */
public class FabricDashboardMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("fabric-dashboard");
    private DashboardWebServer webServer;
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Fabric Activity Dashboard...");
        
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            DashboardCommand.register(dispatcher);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            StreakTracker.getInstance().onPlayerJoin(handler.player);
            EventManager.getInstance().onPlayerJoin(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            StreakTracker.getInstance().onPlayerLeave(handler.player);
            EventManager.getInstance().onPlayerLeave(handler.player);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DashboardConfig.load();
            EventManager.getInstance().init(server);
            webServer = new DashboardWebServer(server);
            webServer.start();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter >= 200) { // Every 10 seconds
                EventManager.getInstance().tick();
                StreakTracker.getInstance().tick(server);
                tickCounter = 0;
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (webServer != null) {
                webServer.stop();
            }
            EventManager.getInstance().save();
        });
    }
}
