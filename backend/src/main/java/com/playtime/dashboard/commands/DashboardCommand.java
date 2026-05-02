package com.playtime.dashboard.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.playtime.dashboard.events.EventManager;
import com.playtime.dashboard.events.ServerEvent;
import com.playtime.dashboard.util.UuidCache;
import com.playtime.dashboard.web.DashboardWebServer;
import com.playtime.dashboard.config.DashboardConfig;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class DashboardCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("dashboard")
            .then(literal("event")
                .then(literal("create")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("type", StringArgumentType.string())
                        .then(argument("hours", IntegerArgumentType.integer(1))
                            .then(argument("title", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String type = StringArgumentType.getString(context, "type");
                                    int hours = IntegerArgumentType.getInteger(context, "hours");
                                    String title = StringArgumentType.getString(context, "title");
                                    EventManager.getInstance().createEvent(context.getSource().getServer(), type, hours, title);
                                    return 1;
                                })
                            )
                        )
                    )
                )
                .then(literal("stop")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        EventManager.getInstance().stopEvent();
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7aEvent stopped."), false);
                        return 1;
                    })
                    .then(argument("id", StringArgumentType.string())
                        .executes(context -> {
                            String id = StringArgumentType.getString(context, "id");
                            EventManager.getInstance().stopEvent(id);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7aEvent " + id + " stopped."), false);
                            return 1;
                        })
                    )
                )
                .then(literal("list")
                    .executes(context -> {
                        context.getSource().sendFeedback(() -> Text.literal("\u00a76Active Events:"), false);
                        for (ServerEvent event : EventManager.getInstance().getActiveEvents()) {
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7e- " + event.title + " (ID: " + event.id + ")"), false);
                        }
                        return 1;
                    })
                )
                .then(literal("status")
                    .executes(context -> {
                        for (ServerEvent event : EventManager.getInstance().getActiveEvents()) {
                            context.getSource().sendFeedback(() -> Text.literal("\u00a76" + event.title + ": \u00a7e" + event.getRemainingTimeString()), false);
                        }
                        return 1;
                    })
                    .then(argument("id", StringArgumentType.string())
                        .executes(context -> {
                            String id = StringArgumentType.getString(context, "id");
                            ServerEvent event = EventManager.getInstance().findActiveEventByInput(id);
                            if (event == null) {
                                context.getSource().sendError(Text.literal("Event not found."));
                                return 0;
                            }
                            context.getSource().sendFeedback(() -> Text.literal("\u00a76" + event.title + ": \u00a7e" + event.getRemainingTimeString()), false);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a76Top Players:"), false);
                            event.scores.entrySet().stream()
                                .sorted((a, b) -> event.lowerIsBetter ? Double.compare(a.getValue(), b.getValue()) : Double.compare(b.getValue(), a.getValue()))
                                .limit(10)
                                .forEach(entry -> {
                                    String name = UuidCache.getInstance().getUsername(UUID.fromString(entry.getKey())).orElse(entry.getKey());
                                    context.getSource().sendFeedback(() -> Text.literal("\u00a7e- " + name + ": " + String.format("%.1f", entry.getValue())), false);
                                });
                            return 1;
                        })
                    )
                )
                .then(literal("setlength")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("id", StringArgumentType.string())
                        .then(argument("hours", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                String id = StringArgumentType.getString(context, "id");
                                int hours = IntegerArgumentType.getInteger(context, "hours");
                                ServerEvent event = EventManager.getInstance().findActiveEventByInput(id);
                                if (event == null) {
                                    context.getSource().sendError(Text.literal("Event not found."));
                                    return 0;
                                }
                                event.endTimeEpoch = System.currentTimeMillis() + (long) hours * 3600000;
                                EventManager.getInstance().markDirty();
                                context.getSource().sendFeedback(() -> Text.literal("\u00a7aEvent " + event.title + " duration updated."), false);
                                return 1;
                            })
                        )
                    )
                )
                .then(literal("scoreboard")
                    .then(literal("hide")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player != null) {
                                EventManager.getInstance().setScoreboardHidden(player.getUuid(), true);
                                context.getSource().sendFeedback(() -> Text.literal("\u00a7aEvent scoreboard hidden."), false);
                            }
                            return 1;
                        })
                    )
                    .then(literal("show")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player != null) {
                                EventManager.getInstance().setScoreboardHidden(player.getUuid(), false);
                                context.getSource().sendFeedback(() -> Text.literal("\u00a7aEvent scoreboard shown."), false);
                            }
                            return 1;
                        })
                    )
                    .then(argument("id", StringArgumentType.string())
                        .executes(context -> {
                            String id = StringArgumentType.getString(context, "id");
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;
                            
                            ServerEvent event = EventManager.getInstance().findActiveEventByInput(id);
                            if (event == null) {
                                context.getSource().sendError(Text.literal("Event not found."));
                                return 0;
                            }
                            
                            EventManager mgr = EventManager.getInstance();
                            mgr.setScoreboardHidden(player.getUuid(), false);
                            mgr.setPlayerScoreboardPreference(player.getUuid(), event.id);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7aNow tracking event: " + event.title), false);
                            return 1;
                        })
                    )
                )
                .then(literal("clearpoints")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(literal("all")
                        .executes(context -> {
                            EventManager.getInstance().clearAllTimePoints(null, -1);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7aAll-time points cleared for all players."), false);
                            return 1;
                        })
                        .then(argument("amount", IntegerArgumentType.integer(0))
                            .executes(context -> {
                                int amount = IntegerArgumentType.getInteger(context, "amount");
                                EventManager.getInstance().clearAllTimePoints(null, amount);
                                context.getSource().sendFeedback(() -> Text.literal("\u00a7aAll-time points reduced to " + amount + " for all players."), false);
                                return 1;
                            })
                        )
                    )
                    .then(literal("user")
                        .then(argument("player", StringArgumentType.string())
                            .executes(context -> {
                                String name = StringArgumentType.getString(context, "player");
                                EventManager.getInstance().clearAllTimePoints(name, -1);
                                context.getSource().sendFeedback(() -> Text.literal("\u00a7aAll-time points cleared for " + name), false);
                                return 1;
                            })
                            .then(argument("amount", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "player");
                                    int amount = IntegerArgumentType.getInteger(context, "amount");
                                    EventManager.getInstance().clearAllTimePoints(name, amount);
                                    context.getSource().sendFeedback(() -> Text.literal("\u00a7aAll-time points reduced to " + amount + " for " + name), false);
                                    return 1;
                                })
                            )
                        )
                    )
                )
            )
            .then(literal("reparse")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal("\u00a7eStarting full log re-parse..."), false);
                    DashboardWebServer server = DashboardWebServer.getInstance();
                    if (server != null) {
                        server.triggerReparse();
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7aRe-parse complete. Activity data updated."), false);
                    } else {
                        context.getSource().sendError(Text.literal("Web server not running."));
                    }
                    return 1;
                })
            )
            .then(literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    if (DashboardConfig.load()) {
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7aDashboard configuration reloaded."), false);
                    } else {
                        context.getSource().sendError(Text.literal("Reload failed! The dashboard-config.json likely has a syntax error. Check the console for details. Falling back to defaults."));
                    }
                    return 1;
                })
            )
            .then(literal("debug")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    EventManager mgr = EventManager.getInstance();
                    context.getSource().sendFeedback(() -> Text.literal("\u00a76EventManager Persistence Health:"), false);
                    context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Dirty: " + mgr.isDirty()), false);
                    context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Save Count: " + mgr.getSaveCount()), false);
                    context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Last Save Attempt: " + mgr.getLastSaveTimestamp()), false);
                    context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Background Thread Alive: " + mgr.isExecutorActive()), false);
                    return 1;
                })
                .then(literal("worldsize")
                    .executes(context -> {
                        DashboardWebServer ws = DashboardWebServer.getInstance();
                        if (ws == null) {
                            context.getSource().sendError(Text.literal("Web server not running."));
                            return 0;
                        }
                        context.getSource().sendFeedback(() -> Text.literal("\u00a76World Size Executor State:"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Cached Size: " + String.format("%.2f GB", ws.getCachedWorldSize())), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Last Walk: " + ws.getLastWorldSizeWalk()), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Next Walk: " + ws.getNextWorldSizeWalk()), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Refresh Interval: " + DashboardConfig.get().world_size_refresh_minutes + "m"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Max Depth: " + DashboardConfig.get().world_size_max_depth), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Thread Active: " + ws.isWorldSizeThreadActive()), false);
                        
                        context.getSource().sendFeedback(() -> Text.literal("\u00a77(Running thread-identity check to console...)"), false);
                        ws.logWorldSizeThreadId();
                        return 1;
                    })
                )
                .then(literal("uuid")
                    .then(argument("input", StringArgumentType.string())
                        .executes(context -> {
                            String input = StringArgumentType.getString(context, "input");
                            UuidCache cache = UuidCache.getInstance();
                            UUID resolvedUuid = null;
                            try {
                                resolvedUuid = UUID.fromString(input);
                            } catch (Exception e) {
                                resolvedUuid = cache.getUuid(input).orElse(null);
                            }

                            if (resolvedUuid == null) {
                                context.getSource().sendError(Text.literal("Could not resolve UUID for input: " + input));
                                return 0;
                            }

                            String uuidStr = resolvedUuid.toString();
                            context.getSource().sendFeedback(() -> Text.literal("\u00a76UUID Cache Status for: \u00a7b" + input), false);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Resolved UUID: " + uuidStr), false);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7e- In Disk Cache: " + cache.isCachedOnDisk(uuidStr)), false);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7e- In Runtime Cache: " + cache.isCachedInRuntime(uuidStr)), false);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Last Mojang Attempt: " + cache.getLastAttemptTimestamp(uuidStr)), false);
                            context.getSource().sendFeedback(() -> Text.literal("\u00a7e- Currently Throttled: " + cache.isThrottled(uuidStr)), false);
                            return 1;
                        })
                    )
                )
                .then(literal("rebuild-meta")
                    .executes(context -> {
                        DashboardWebServer ws = DashboardWebServer.getInstance();
                        if (ws == null) {
                            context.getSource().sendError(Text.literal("Web server not running."));
                            return 0;
                        }
                        ws.getHeadService().clearCache();
                        ws.triggerHeadFetches();
                        context.getSource().sendFeedback(() -> Text.literal("\u00a7aPlayer head metadata cache cleared and rebuild triggered. Heads will appear on the dashboard gradually as they are fetched from Mojang."), false);
                        return 1;
                    })
                )
            )
        );
    }
}
