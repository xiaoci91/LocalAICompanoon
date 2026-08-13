package com.localaicompanion.intent;

import com.localaicompanion.config.MainConfig;
import com.localaicompanion.config.PermissionConfig;
import com.localaicompanion.llm.LLMClient;
import com.localaicompanion.llm.LLMResponse;
import com.localaicompanion.memory.MemoryManager;
import com.localaicompanion.security.SecuritySandbox;
import com.localaicompanion.task.TaskScheduler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第2层：意图解析安全校验层（项目核心）
 *
 * 这是大模型和游戏世界之间的核心隔离层
 * 所有大模型输出必须经过这里才能转化为游戏操作
 *
 * 职责：
 * 1. 接收LLM返回内容
 * 2. 文本解析、格式校验
 * 3. 权限判断、意图标准化
 * 4. 生成标准任务对象交给下层
 *
 * 核心原则：
 * - 大模型只可以输出"意图建议"，没有直接执行游戏操作的权力
 * - 所有改变世界的行为，必须经过校验层审核通过才允许执行
 * - 解析失败直接丢弃操作请求，只回复聊天
 */
public class IntentSecurityLayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-SecurityLayer");

    private final IntentParser parser;
    private final IntentValidator validator;
    private final LLMClient llmClient;
    private final MemoryManager memoryManager;
    private final TaskScheduler taskScheduler;

    // 解析失败计数（用于提示玩家）
    private int consecutiveParseFailures = 0;
    private static final int MAX_PARSE_FAILURES = 3;

    public IntentSecurityLayer(PermissionConfig permissionConfig, MainConfig mainConfig,
                               SecuritySandbox securitySandbox, LLMClient llmClient,
                               MemoryManager memoryManager, TaskScheduler taskScheduler) {
        this.parser = new IntentParser();
        this.validator = new IntentValidator(permissionConfig, mainConfig, securitySandbox);
        this.llmClient = llmClient;
        this.memoryManager = memoryManager;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 处理玩家消息（入口方法）
     * 异步调用LLM，解析结果，校验权限，提交任务
     *
     * @param player 玩家
     * @param message 玩家消息
     * @param isTaskRequest 是否为任务请求（影响限流策略）
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
                handleLLMError(player, errorMessage, false);
            }
        };

        if (isTaskRequest) {
            llmClient.sendTaskRequest(message, context, callback);
        } else {
            llmClient.sendChatRequest(message, context, callback);
        }

        // 3. 记录到短期记忆
        memoryManager.addChatMessage("player", message);
    }

    /**
     * 处理LLM响应
     */
    private void handleLLMResponse(ServerPlayerEntity player, String responseContent) {
        // 1. 解析意图
        IntentParser.ParseResult parseResult = parser.parse(responseContent);

        if (!parseResult.isTask()) {
            // 纯聊天，直接显示
            consecutiveParseFailures = 0;
            sendNPCMessage(player, parseResult.getChatMessage());
            memoryManager.addChatMessage("npc", parseResult.getChatMessage());
            return;
        }

        StandardTask task = parseResult.getTask();
        task.setOriginalInput(responseContent);

        // 2. 安全校验
        BlockPos targetPos = null; // 暂时传null，后续任务执行时再精确判断
        IntentValidator.ValidationResult validation = validator.validate(task, player, targetPos);

        if (!validation.isAllowed()) {
            // 校验不通过，显示拒绝消息
            LOGGER.warn("[SecurityLayer] 任务被拦截: {} - {}", task.getIntentType(), validation.getDenyReason());
            sendNPCMessage(player, validation.getDenyMessage());
            memoryManager.addChatMessage("npc", validation.getDenyMessage());

            // 记录解析失败
            consecutiveParseFailures++;
            if (consecutiveParseFailures >= MAX_PARSE_FAILURES) {
                sendSystemMessage(player, "提示：我有点理解不了你的指令，试试说简单一点？");
                consecutiveParseFailures = 0;
            }
            return;
        }

        // 3. 校验通过，提交任务到调度器
        consecutiveParseFailures = 0;

        // 先显示NPC说话内容
        if (task.getComment() != null && !task.getComment().isEmpty()) {
            sendNPCMessage(player, task.getComment());
            memoryManager.addChatMessage("npc", task.getComment());
        }

        // 提交任务
        boolean submitted = taskScheduler.submitTask(task, player);
        if (!submitted) {
            sendSystemMessage(player, "任务队列已满，请稍后再试");
        }

        LOGGER.info("[SecurityLayer] 任务通过校验并提交: {}", task);
    }

    /**
     * 处理LLM错误
     */
    private void handleLLMError(ServerPlayerEntity player, String errorMessage, boolean isOOM) {
        LOGGER.error("[SecurityLayer] LLM请求失败: {}", errorMessage);

        if (isOOM) {
            sendSystemMessage(player, "⚠ 显存不足！请切换到更小量化的模型（如q2_K），或降低上下文窗口大小。");
        } else if (errorMessage.contains("连接") || errorMessage.contains("Connection") || errorMessage.contains("refused")) {
            sendSystemMessage(player, "⚠ 无法连接到本地AI服务。请确认Ollama或LM Studio已启动。");
        } else {
            sendSystemMessage(player, "⚠ AI服务暂时不可用: " + errorMessage);
        }
    }

    /**
     * 构建请求上下文
     */
    private String buildContext(ServerPlayerEntity player, String currentMessage) {
        StringBuilder context = new StringBuilder();

        // 短期对话记忆
        String shortTerm = memoryManager.getShortTermContext();
        if (shortTerm != null && !shortTerm.isEmpty()) {
            context.append(shortTerm);
        }

        // 相关长期记忆
        String longTerm = memoryManager.getRelevantLongTermMemory(currentMessage);
        if (longTerm != null && !longTerm.isEmpty()) {
            context.append("\n【相关记忆】\n").append(longTerm);
        }

        return context.toString();
    }

    /**
     * 发送NPC消息（游戏内聊天栏显示）
     */
    private void sendNPCMessage(ServerPlayerEntity player, String message) {
        if (player == null || message == null) return;

        String npcName = "小艾"; // 从配置获取
        Text text = Text.literal("[" + npcName + "] ").append(message);
        player.sendMessage(text, false);
    }

    /**
     * 发送系统消息
     */
    private void sendSystemMessage(ServerPlayerEntity player, String message) {
        if (player == null || message == null) return;

        Text text = Text.literal("§7[系统] " + message);
        player.sendMessage(text, false);
    }

    /**
     * 直接提交系统任务（不经过LLM）
     * 用于危险环境检测、超时处理等内部触发
     */
    public void submitSystemTask(StandardTask task, ServerPlayerEntity player) {
        task.setSource(StandardTask.TaskSource.SYSTEM);
        taskScheduler.submitTask(task, player);
    }

    public IntentParser getParser() {
        return parser;
    }

    public IntentValidator getValidator() {
        return validator;
    }

    public void setLocalServer(boolean localServer) {
        validator.setLocalServer(localServer);
    }
}
