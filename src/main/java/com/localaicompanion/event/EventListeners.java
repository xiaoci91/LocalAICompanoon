package com.localaicompanion.event;

import com.localaicompanion.LocalAICompanion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件监听器注册
 * 注册所有游戏事件回调
 */
public class EventListeners {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-Events");

    public static void registerAll() {
        // 服务器启动完成
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[Events] 服务器已启动");
            LocalAICompanion.getInstance().getConfigManager().loadOrCreateDefault();
            LocalAICompanion.getInstance().getMemoryManager().loadWorldMemory(server);
            LocalAICompanion.getInstance().getSecuritySandbox().loadWorldZones(server);
        });

        // 服务器停止
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[Events] 服务器正在停止");
            LocalAICompanion.getInstance().getConfigManager().saveAll();
            LocalAICompanion.getInstance().getMemoryManager().saveWorldMemory(server);
            LocalAICompanion.getInstance().getSecuritySandbox().saveWorldZones(server);
        });

        // 玩家聊天消息
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            handleChatMessage(sender, message.getContent().getString());
        });

        // 玩家加入
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // 玩家重生
        });

        LOGGER.info("[Events] 事件监听器已注册");
    }

    /**
     * 处理玩家聊天消息
     * 如果消息以特定前缀开头，视为对AI同伴说话
     */
    private static void handleChatMessage(ServerPlayerEntity player, String message) {
        if (player == null || message == null) return;

        // 检查是否是对AI同伴说话
        // 支持的前缀：@小艾、小艾、@ai、ai:
        String lower = message.toLowerCase();
        boolean isForCompanion = false;
        String actualMessage = message;

        if (lower.startsWith("@小艾") || lower.startsWith("@ai") || lower.startsWith("小艾") || lower.startsWith("ai:")) {
            isForCompanion = true;
            // 提取实际消息内容
            if (lower.startsWith("@小艾")) {
                actualMessage = message.substring(3).trim();
            } else if (lower.startsWith("@ai")) {
                actualMessage = message.substring(3).trim();
            } else if (lower.startsWith("小艾")) {
                actualMessage = message.substring(2).trim();
            } else if (lower.startsWith("ai:")) {
                actualMessage = message.substring(3).trim();
            }

            // 去掉开头的标点
            actualMessage = actualMessage.replaceAll("^[，,。.!！?？\\s]+", "");
        }

        if (isForCompanion && !actualMessage.isEmpty()) {
            LocalAICompanion.getInstance().getIntentSecurityLayer()
                .processPlayerMessage(player, actualMessage, false);
        }
    }
}
