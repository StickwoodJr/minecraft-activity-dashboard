package com.playtime.dashboard.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.playtime.dashboard.events.EventManager;
import com.playtime.dashboard.events.ServerEvent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class DashboardCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("dashboard")
            .then(CommandManager.literal("event")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("playtime");
                            builder.suggest("blocks_placed");
                            builder.suggest("blocks_mined");
                            builder.suggest("mob_kills");
                            return builder.buildFuture();
                        })
                        .then(CommandManager.argument("duration_hours", IntegerArgumentType.integer(1))
                            .then(CommandManager.argument("title", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String type = StringArgumentType.getString(context, "type");
                                    int hours = IntegerArgumentType.getInteger(context, "duration_hours");
                                    String title = StringArgumentType.getString(context, "title");
                                    
                                    EventManager.getInstance().startEvent(title, type, hours);
                                    context.getSource().sendFeedback(() -> Text.literal("§aEvent created: " + title), true);
                                    return 1;
                                })
                            )
                        )
                    )
                )
                .then(CommandManager.literal("stop")
                    .executes(context -> {
                        EventManager.getInstance().stopEvent();
                        context.getSource().sendFeedback(() -> Text.literal("§cEvent stopped."), true);
                        return 1;
                    })
                )
                .then(CommandManager.literal("status")
                    .executes(context -> {
                        ServerEvent active = EventManager.getInstance().getActiveEvent();
                        if (active == null) {
                            context.getSource().sendFeedback(() -> Text.literal("§eNo active event."), false);
                        } else {
                            context.getSource().sendFeedback(() -> Text.literal("§6Active Event: §f" + active.title), false);
                            context.getSource().sendFeedback(() -> Text.literal("§6Type: §f" + active.type), false);
                            long remaining = (active.endTime - System.currentTimeMillis()) / 1000;
                            context.getSource().sendFeedback(() -> Text.literal("§6Remaining: §f" + (remaining / 60) + "m " + (remaining % 60) + "s"), false);
                        }
                        return 1;
                    })
                )
                .then(CommandManager.literal("clearpoints")
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
            )
        );
    }
}
