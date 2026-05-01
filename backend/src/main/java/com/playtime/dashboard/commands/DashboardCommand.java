package com.playtime.dashboard.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.playtime.dashboard.events.EventManager;
import com.playtime.dashboard.events.ServerEvent;
import com.playtime.dashboard.web.DashboardWebServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
                            builder.suggest("obsidian_placed");
                            builder.suggest("obsidian_mined");
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
                        sendEventOverview(context.getSource());
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
                        sendEventOverview(context.getSource());
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
                            sendEventDetail(context.getSource(), event);
                            return 1;
                        })
                    )
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
                    .then(CommandManager.literal("hide")
                        .executes(context -> {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendError(Text.literal("This command must be run by a player."));
                                return 0;
                            }
                            EventManager.getInstance().setScoreboardHidden(player.getUuid(), true);
                            context.getSource().sendFeedback(() -> Text.literal("§aEvent scoreboard hidden. Use §f/dashboard event scoreboard show§a to bring it back."), false);
                            return 1;
                        })
                    )
                    .then(CommandManager.literal("show")
                        .executes(context -> {
                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendError(Text.literal("This command must be run by a player."));
                                return 0;
                            }
                            EventManager.getInstance().setScoreboardHidden(player.getUuid(), false);
                            context.getSource().sendFeedback(() -> Text.literal("§aEvent scoreboard shown."), false);
                            return 1;
                        })
                    )
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

                            net.minecraft.server.network.ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendError(Text.literal("This command must be run by a player."));
                                return 0;
                            }
                            EventManager mgr = EventManager.getInstance();
                            mgr.setScoreboardHidden(player.getUuid(), false);
                            mgr.setPlayerScoreboardPreference(player.getUuid(), event.id);
                            context.getSource().sendFeedback(() -> Text.literal("§aScoreboard preference set to: " + eventRef(event)), false);
                            return 1;
                        })
                    )
                )
            )
            .then(CommandManager.literal("debug")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    EventManager mgr = EventManager.getInstance();

                    // Snapshot values once so the copy payload matches what is displayed
                    boolean dirty        = mgr.isDirty();
                    int submitted        = mgr.getSaveSubmitCount();
                    int completed        = mgr.getSaveCompleteCount();
                    int activeEvents     = mgr.getActiveEvents().size();
                    boolean execAlive    = !mgr.isExecutorShutdown();

                    String copyPayload =
                        "Dashboard Debug\n" +
                        "Dirty flag: " + dirty + "\n" +
                        "Save tasks submitted: " + submitted + "\n" +
                        "Save tasks completed: " + completed + "\n" +
                        "Active events: " + activeEvents + "\n" +
                        "Executor alive: " + execAlive;

                    Text copyButton = Text.literal(" §7[§bCopy§7]")
                        .styled(s -> s
                            .withClickEvent(new ClickEvent(
                                ClickEvent.Action.COPY_TO_CLIPBOARD, copyPayload))
                            .withHoverEvent(new net.minecraft.text.HoverEvent(
                                net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                Text.literal("§7Click to copy debug info"))));

                    context.getSource().sendMessage(
                        Text.literal("§6--- Dashboard Debug ---").append(copyButton));
                    context.getSource().sendMessage(Text.literal("§eDirty flag: §f" + dirty));
                    context.getSource().sendMessage(Text.literal("§eSave tasks submitted: §f" + submitted));
                    context.getSource().sendMessage(Text.literal("§eSave tasks completed: §f" + completed));
                    context.getSource().sendMessage(Text.literal("§eActive events: §f" + activeEvents));
                    context.getSource().sendMessage(Text.literal("§eExecutor alive: §f" + execAlive));
                    return 1;
                })
            )
            .then(CommandManager.literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    com.playtime.dashboard.config.DashboardConfig.load();
                    EventManager.getInstance().invalidateScoreboardCache();
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

    private static void sendEventOverview(ServerCommandSource source) {
        List<ServerEvent> active = EventManager.getInstance().getActiveEvents();
        if (active == null || active.isEmpty()) {
            source.sendMessage(Text.literal("§eNo active events."));
            return;
        }
        source.sendMessage(Text.literal("§6--- Active Events (" + active.size() + ") ---"));
        for (ServerEvent event : active) {
            long remainingSec = Math.max(0, (event.endTime - System.currentTimeMillis()) / 1000);
            String remainingStr = formatDuration(remainingSec);
            int participants = event.currentScores == null ? 0 : (int) event.currentScores.values().stream().filter(v -> v != null && v > 0).count();
            source.sendMessage(Text.literal(
                "§e" + (event.title == null ? event.id : event.title)
                    + " §7(" + shortId(event.id) + ") "
                    + "§b" + event.type
                    + " §f[§a" + remainingStr + " left§f] "
                    + "§7participants: §f" + participants
            ));
        }
    }

    private static void sendEventDetail(ServerCommandSource source, ServerEvent event) {
        long now = System.currentTimeMillis();
        long remainingSec = Math.max(0, (event.endTime - now) / 1000);
        long elapsedSec = Math.max(0, (now - event.startTime) / 1000);
        long totalSec = Math.max(1, (event.endTime - event.startTime) / 1000);
        int pct = (int) Math.min(100, Math.max(0, (elapsedSec * 100) / totalSec));

        source.sendMessage(Text.literal("§6--- Event: §e" + (event.title == null ? event.id : event.title) + " §6---"));
        source.sendMessage(Text.literal("§7id: §f" + event.id));
        source.sendMessage(Text.literal("§7type: §b" + event.type + (event.lowerIsBetter ? " §7(lower is better)" : "")));
        source.sendMessage(Text.literal("§7elapsed: §f" + formatDuration(elapsedSec) + " §7/ §f" + formatDuration(totalSec) + " §7(" + pct + "%)"));
        source.sendMessage(Text.literal("§7remaining: §a" + formatDuration(remainingSec)));

        if (event.currentScores == null || event.currentScores.isEmpty()) {
            source.sendMessage(Text.literal("§7No scores recorded yet."));
            return;
        }

        List<Map.Entry<String, Integer>> ranked = event.currentScores.entrySet().stream()
            .filter(e -> e.getValue() != null && e.getValue() > 0)
            .sorted(event.lowerIsBetter ? Map.Entry.comparingByValue() : Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());

        if (ranked.isEmpty()) {
            source.sendMessage(Text.literal("§7No qualifying scores yet."));
            return;
        }

        int top = Math.min(10, ranked.size());
        source.sendMessage(Text.literal("§6Top " + top + ":"));
        EventManager mgr = EventManager.getInstance();
        for (int i = 0; i < top; i++) {
            Map.Entry<String, Integer> entry = ranked.get(i);
            int rank = i + 1;
            int val = entry.getValue();
            String name = mgr.resolvePlayerName(entry.getKey());
            String scoreStr;
            if (event.type.equals("playtime")) scoreStr = formatDuration(val);
            else if (event.type.equals("damage_dealt")) scoreStr = val + " ❤";
            else scoreStr = String.valueOf(val);
            String pointsStr = rank <= 3 ? " §6(+" + (4 - rank) + " pts)" : "";
            source.sendMessage(Text.literal("§e#" + rank + " §f" + name + " §7- §a" + scoreStr + pointsStr));
        }
        if (ranked.size() > top) {
            int remaining = ranked.size() - top;
            source.sendMessage(Text.literal("§7...and " + remaining + " more."));
        }
    }

    private static String formatDuration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0 || d > 0) sb.append(h).append("h ");
        if (m > 0 || h > 0 || d > 0) sb.append(m).append("m ");
        sb.append(s).append("s");
        return sb.toString();
    }
}
