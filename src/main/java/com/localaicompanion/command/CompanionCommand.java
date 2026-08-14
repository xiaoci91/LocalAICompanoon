package com.localaicompanion.command;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.entity.ai.AICompanionEntity;
import com.localaicompanion.entity.ai.AICompanionEntities;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 模组命令注册
 * /companion summon - 召唤AI同伴
 * /companion dismiss - 解散AI同伴
 * /companion talk <消息> - 和AI同伴说话
 * /companion status - 查看状态
 */
public class CompanionCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("companion")
                .requires(source -> source.hasPermissionLevel(0))
                .then(CommandManager.literal("summon")
                    .executes(context -> summonCompanion(context.getSource())))
                .then(CommandManager.literal("dismiss")
                    .executes(context -> dismissCompanion(context.getSource())))
                .then(CommandManager.literal("toggle")
                    .executes(context -> toggleCompanion(context.getSource())))
                .then(CommandManager.literal("talk")
                    .then(CommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> talkToCompanion(
                            context.getSource(),
                            StringArgumentType.getString(context, "message")))))
                .then(CommandManager.literal("status")
                    .executes(context -> showStatus(context.getSource())))
                .then(CommandManager.literal("help")
                    .executes(context -> showHelp(context.getSource())))
        );

        dispatcher.register(
            CommandManager.literal("ai")
                .requires(source -> source.hasPermissionLevel(0))
                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                    .executes(context -> talkToCompanion(
                        context.getSource(),
                        StringArgumentType.getString(context, "message"))))
        );
    }

    private static int summonCompanion(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("只有玩家可以使用此命令"));
            return 0;
        }

        // 检查是否已经有同伴了
        AICompanionEntity existing = findCompanion(player);
        if (existing != null) {
            source.sendFeedback(() -> Text.literal("§e你已经有AI同伴了！先解散再召唤吧"), false);
            return 0;
        }

        BlockPos pos = player.getBlockPos().add(1, 0, 1);

        AICompanionEntity companion = AICompanionEntities.AI_COMPANION.create(player.getWorld());
        if (companion != null) {
            companion.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), 0, 0);
            companion.initializeCompanion(player);
            player.getWorld().spawnEntity(companion);
            source.sendFeedback(() -> Text.literal("§aAI同伴已召唤！试试和它说话吧~"), false);
            return 1;
        }

        source.sendError(Text.literal("召唤失败"));
        return 0;
    }

    private static int dismissCompanion(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        AICompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendError(Text.literal("你还没有AI同伴"));
            return 0;
        }

        // 真正移除实体
        companion.discard();
        source.sendFeedback(() -> Text.literal("§aAI同伴已解散，记忆已保留"), false);
        return 1;
    }

    /**
     * 切换同伴召唤状态（有就解散，没有就召唤）
     */
    private static int toggleCompanion(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        AICompanionEntity existing = findCompanion(player);
        if (existing != null) {
            // 有就解散
            existing.discard();
            source.sendFeedback(() -> Text.literal("§aAI同伴已解散"), false);
        } else {
            // 没有就召唤
            BlockPos pos = player.getBlockPos().add(1, 0, 1);
            AICompanionEntity companion = AICompanionEntities.AI_COMPANION.create(player.getWorld());
            if (companion != null) {
                companion.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), 0, 0);
                companion.initializeCompanion(player);
                player.getWorld().spawnEntity(companion);
                source.sendFeedback(() -> Text.literal("§aAI同伴已召唤！"), false);
            }
        }
        return 1;
    }

    /**
     * 查找玩家的AI同伴
     */
    private static AICompanionEntity findCompanion(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        List<AICompanionEntity> list = world.getEntitiesByClass(
            AICompanionEntity.class,
            player.getBoundingBox().expand(100),
            entity -> entity.isOwner(player)
        );
        if (list.isEmpty()) return null;
        return list.get(0);
    }

    private static int talkToCompanion(ServerCommandSource source, String message) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        LocalAICompanion.getInstance().getIntentSecurityLayer()
            .processPlayerMessage(player, message, false);
        return 1;
    }

    private static int showStatus(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        var config = LocalAICompanion.getInstance().getConfigManager();
        var scheduler = LocalAICompanion.getInstance().getTaskScheduler();

        source.sendFeedback(() -> Text.literal("===== AI同伴状态 ====="), false);
        source.sendFeedback(() -> Text.literal("运行模式: " + config.getMainConfig().runMode), false);
        source.sendFeedback(() -> Text.literal("任务队列: " + scheduler.getQueueSize() + "个等待中"), false);

        var current = scheduler.getCurrentTask();
        if (current != null) {
            source.sendFeedback(() -> Text.literal("当前任务: " + current.getStandardTask().getIntentType().getDisplayName()), false);
        }

        return 1;
    }

    private static int showHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("===== AI同伴命令 ====="), false);
        source.sendFeedback(() -> Text.literal("/companion summon - 召唤AI同伴"), false);
        source.sendFeedback(() -> Text.literal("/companion dismiss - 解散AI同伴"), false);
        source.sendFeedback(() -> Text.literal("/companion talk <消息> - 和AI同伴说话"), false);
        source.sendFeedback(() -> Text.literal("/ai <消息> - 快捷说话"), false);
        source.sendFeedback(() -> Text.literal("/companion status - 查看状态"), false);
        return 1;
    }
}
