package com.localaicompanion.intent;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.llm.LLMClient;
import com.localaicompanion.llm.LLMResponse;
import com.localaicompanion.memory.MemoryManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 意图安全层 - 简化版
 *
 * 只处理聊天对话，不处理任务
 * 所有大模型输出都作为聊天消息显示
 */
public class IntentSecurityLayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-SecurityLayer");

    private final LLMClient llmClient;
    private final MemoryManager memoryManager;

    public IntentSecurityLayer(LLMClient llmClient, MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.memoryManager = memoryManager;
    }

    /**
     * 处理玩家消息（入口方法）
     * 异步调用LLM，返回聊天回复
     *
     * @param player 玩家
     * @param message 玩家消息
     * @param isTaskRequest 是否为任务请求（保留参数但忽略）
     */
    public void processPlayerMessage(ServerPlayerEntity player, String message, boolean isTaskRequest) {
        // 1. 构建上下文（短期记忆 + 相关长期记忆）
        String context = buildContext(player, message);

        // 2. 异步调用LLM
        LLMClient.LLMCallback callback = new LLMClient.LLMCallback() {
            @Override
            public void onSuccess(LLMResponse response) {
                handleLLMResponse(player, response.getContent());
            }

            @Override
            public void onError(String errorMessage) {
                handleLLMError(player, errorMessage);
            }
        };

        llmClient.sendChatRequest(message, context, callback);

        // 3. 记录到短期记忆
        memoryManager.addChatMessage("player", message);
    }

    /**
     * 处理LLM响应 - 直接作为聊天消息显示
     */
    private void handleLLMResponse(ServerPlayerEntity player, String responseContent) {
        if (responseContent == null || responseContent.trim().isEmpty()) {
            responseContent = "（空响应）";
        }

        // 直接显示聊天消息
        sendNPCMessage(player, responseContent);

        // 记录到记忆
        memoryManager.addChatMessage("npc", responseContent);
    }

    /**
     * 处理LLM错误
     */
    private void handleLLMError(ServerPlayerEntity player, String errorMessage) {
        String errorReply = "抱歉，我现在有点累，等会儿再聊吧~";
        sendNPCMessage(player, errorReply);
        memoryManager.addChatMessage("npc", errorReply);

        LOGGER.error("[SecurityLayer] LLM调用失败: {}", errorMessage);
    }

    /**
     * 构建上下文
     */
    private String buildContext(ServerPlayerEntity player, String message) {
        StringBuilder context = new StringBuilder();

        // 添加短期记忆
        context.append(memoryManager.getShortTermContext());

        // 添加长期记忆（相关的）
        context.append(memoryManager.getRelevantLongTermMemory(message));

        return context.toString();
    }

    /**
     * 发送NPC聊天消息
     */
    private void sendNPCMessage(ServerPlayerEntity player, String message) {
        Text text = Text.literal("§b[小艾] §r" + message);
        player.sendMessage(text, false);
    }
}
