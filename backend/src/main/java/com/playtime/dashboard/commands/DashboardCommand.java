package com.playtime.dashboard.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.playtime.dashboard.events.EventManager;
import com.playtime.dashboard.events.ServerEvent;
import com.playtime.dashboard.web.DashboardWebServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

public class DashboardCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("dashboard")
            .then(CommandManager.literal("event")
                .then(CommandManager.literal("create")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("playtime");
                            builder.suggest("blocks_placed");
                            builder.suggest("blocks_mined");
                            builder.suggest("mob_kills");
                            builder.suggest("fewest_deaths");
                            builder.suggest("damage_dealt");
                            builder.suggest("player_kills");
                            builder.suggest("fish_caught");
                            builder.suggest("daily_streak");
                            return builder.buildFuture();
                        })
                        .then(CommandManager.argument("duration_hours", IntegerArgumentType.integer(1))
                            .then(CommandManager.argument("title", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String type = StringArgumentType.getString(context, "type");
                                    int hours = IntegerArgumentType.getInteger(context, "duration_hours");
                                    String title = StringArgumentType.getString(context, "title");

                                    EventManager manager = EventManager.getInstance();
                                    if (manager.isActiveTitleInUse(title)) {
                                        context.getSource().sendError(Text.literal("An active event already uses that title. Please choose a unique title."));
                                        return 0;
                                    }

                                    manager.startEvent(title, type, hours);
                                    ServerEvent createdEvent = manager.findActiveEventByInput(title);
                                    String createdRef = createdEvent == null ? title : eventRef(createdEvent);
                                    context.getSource().sendFeedback(() -> Text.literal("§aEvent created: " + createdRef), true);
                                    return 1;
                                })
                            )
                        )
                    )
                )
                .then(CommandManager.literal("list")
                    .executes(context -> {
                        List<ServerEvent> active = EventManager.getInstance().getActiveEvents();
                        if (active.isEmpty()) {
                            context.getSource().sendFeedback(() -> Text.literal("§eNo active events."), false);
                        } else {
                            context.getSource().sendFeedback(() -> Text.literal("§6--- Active Events ---"), false);
                            for (ServerEvent event : active) {
                                long remaining = (event.endTime - System.currentTimeMillis()) / 1000;
                                context.getSource().sendFeedback(() -> Text.literal("§e" + event.title + " §7(" + event.id.substring(0, 8) + ") - " + event.type + " §f[" + (remaining / 3600) + "h " + ((remaining % 3600) / 60) + "m left]"), false);
                            }
                        }
                        return 1;
                    })
                )
                .then(CommandManager.literal("stop")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        EventManager.getInstance().stopEvent();
                        context.getSource().sendFeedback(() -> Text.literal("§cEvent stopped (most recently started)."), true);
                        return 1;
                    })
                    .then(CommandManager.argument("id", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            suggestActiveEventTitles(builder);
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String input = StringArgumentType.getString(context, "id");
                            ServerEvent event = EventManager.getInstance().findActiveEventByInput(input);
                            if (event == null) {
                                context.getSource().sendError(Text.literal("Event not found. Use /dashboard event list to view active events."));
                                return 0;
                            }

                            EventManager.getInstance().stopEvent(event.id);
                            context.getSource().sendFeedback(() -> Text.literal("§cEvent stopped: " + eventRef(event)), true);
                            return 1;
                        })
                    )
                )
                .then(CommandManager.literal("setlength")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("id", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            suggestActiveEventTitles(builder);
                            return builder.buildFuture();
                        })
                        .then(CommandManager.argument("hours", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                String input = StringArgumentType.getString(context, "id");
                                int hours = IntegerArgumentType.getInteger(context, "hours");
                                ServerEvent event = EventManager.getInstance().findActiveEventByInput(input);
                                
                                if (event == null) {
                                    context.getSource().sendError(Text.literal("Event not found. Use /dashboard event list to view active events."));
                                    return 0;
                                }
                                event.endTime = event.startTime + (long) hours * 60 * 60 * 1000;
                                EventManager.getInstance().save();
                                context.getSource().sendFeedback(() -> Text.literal("§aUpdated " + eventRef(event) + " length to " + hours + " hours."), true);
                                return 1;
                            })
                        )
                    )
                )
                .then(CommandManager.literal("status")
                    .executes(context -> {
                        List<ServerEvent> active = EventManager.getInstance().getActiveEvents();
                        if (active.isEmpty()) {
                            context.getSource().sendFeedback(() -> Text.literal("§eNo active events."), false);
                        } else {
                            context.getSource().sendFeedback(() -> Text.literal("§6--- Active Events ---"), false);
                            for (ServerEvent event : active) {
                                long remaining = (event.endTime - System.currentTimeMillis()) / 1000;
                                context.getSource().sendFeedback(() -> Text.literal("§e" + event.title + " §7(" + event.id.substring(0, 8) + ") - " + event.type + " §f[" + (remaining / 3600) + "h " + ((remaining % 3600) / 60) + "m left]"), false);
                            }
                        }
                        return 1;
                    })
                )
                .then(CommandManager.literal("clearpoints")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("all")
                        .executes(context -> {
                            EventManager.getInstance().clearAllPoints(0);
                            context.getSource().sendFeedback(() -> Text.literal("§aCleared all points for all players."), true);
                            return 1;
                        })
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                int amount = IntegerArgumentType.getInteger(context, "amount");
                                EventManager.getInstance().clearAllPoints(amount);
                                context.getSource().sendFeedback(() -> Text.literal("§aRemoved " + amount + " points from all players."), true);
                                return 1;
                            })
                        )
                    )
                    .then(CommandManager.literal("user")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(context -> {
                                String player = StringArgumentType.getString(context, "player");
                                EventManager.getInstance().clearUserPoints(player, 0);
                                context.getSource().sendFeedback(() -> Text.literal("§aCleared all points for " + player), true);
                                return 1;
                            })
                            .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    String player = StringArgumentType.getString(context, "player");
                                    int amount = IntegerArgumentType.getInteger(context, "amount");
                                    EventManager.getInstance().clearUserPoints(player, amount);
                                    context.getSource().sendFeedback(() -> Text.literal("§aRemoved " + amount + " points from " + player), true);
                                    return 1;
                                })
                            )
                        )
                    )
                )
                .then(CommandManager.literal("scoreboard")
                    .then(CommandManager.argument("id", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            suggestActiveEventTitles(builder);
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String input = StringArgumentType.getString(context, "id");
                            ServerEvent event = EventManager.getInstance().findActiveEventByInput(input);
                            if (event == null) {
                                context.getSource().sendError(Text.literal("Event not found. Use /dashboard event list to view active events."));
                                return 0;
                            }

                            EventManager.getInstance().setPlayerScoreboardPreference(context.getSource().getPlayer().getUuid(), event.id);
                            context.getSource().sendFeedback(() -> Text.literal("§aScoreboard preference set to: " + eventRef(event)), false);
                            return 1;
                        })
                    )
                )
            )
            .then(CommandManager.literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    com.playtime.dashboard.config.DashboardConfig.load();
                    context.getSource().sendFeedback(() -> Text.literal("§a[Dashboard] Config reloaded!"), true);
                    return 1;
                })
            )
            .then(CommandManager.literal("reparse")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    DashboardWebServer webServer = DashboardWebServer.getInstance();
                    if (webServer == null) {
                        context.getSource().sendError(Text.literal("[Dashboard] Web server is not running; cannot reparse logs."));
                        return 0;
                    }

                    webServer.reparseLogs();
                    context.getSource().sendFeedback(() -> Text.literal("§a[Dashboard] Reparse started. Check server log for completion."), true);
                    return 1;
                })
            )
        );
    }

    private static void suggestActiveEventTitles(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        EventManager.getInstance().getActiveEvents().forEach(event -> {
            String suggestion = quoteIfNeeded(event.title == null ? event.id : event.title);
            builder.suggest(suggestion, Text.literal("ID: " + shortId(event.id)));
        });
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) return "";
        if (value.indexOf(' ') < 0) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String shortId(String id) {
        return id.substring(0, Math.min(8, id.length()));
    }

    private static String eventRef(ServerEvent event) {
        String title = event.title == null || event.title.isBlank() ? event.id : event.title;
        return title + " (" + shortId(event.id) + ")";
    }
}
