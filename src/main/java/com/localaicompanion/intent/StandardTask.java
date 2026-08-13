package com.localaicompanion.intent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 标准化任务对象
 * 所有大模型输出的意图都必须解析为这个标准对象
 * 这是大模型和游戏世界之间的隔离层
 *
 * 大模型不能直接下发底层操作指令，只能输出高层意图
 * 具体执行由模组自己的任务调度器决定
 */
public class StandardTask {
    // 任务唯一ID
    private final String taskId;

    // 意图类型
    private final IntentType intentType;

    // 目标（方块ID、物品名、实体名等）
    private final String target;

    // 数量
    private final int amount;

    // NPC要说的话（显示给玩家）
    private final String comment;

    // 附加参数
    private final Map<String, Object> parameters;

    // 任务创建时间
    private final long createdAt;

    // 任务超时时间（毫秒）
    private long timeoutMs;

    // 最大重试次数
    private int maxRetries;

    // 任务优先级（数值越大优先级越高）
    private int priority;

    // 来源（PLAYER_CHAT / AUTO / SYSTEM）
    private TaskSource source;

    // 玩家原始输入（用于调试和记忆）
    private String originalInput;

    public StandardTask(IntentType intentType, String target, int amount, String comment) {
        this.taskId = UUID.randomUUID().toString();
        this.intentType = intentType;
        this.target = target;
        this.amount = amount;
        this.comment = comment;
        this.parameters = new HashMap<>();
        this.createdAt = System.currentTimeMillis();
        this.timeoutMs = 120000; // 默认2分钟超时
        this.maxRetries = 2;
        this.priority = 5; // 默认优先级
        this.source = TaskSource.PLAYER_CHAT;
        this.originalInput = "";
    }

    /**
     * 创建一个纯聊天任务（不触发游戏行为）
     */
    public static StandardTask createChat(String message) {
        StandardTask task = new StandardTask(IntentType.CHAT, null, 0, message);
        task.priority = 10; // 聊天优先级最高
        return task;
    }

    /**
     * 创建一个停止任务
     */
    public static StandardTask createStop(String reason) {
        StandardTask task = new StandardTask(IntentType.STOP, null, 0, reason);
        task.priority = 100; // 停止任务最高优先级
        task.source = TaskSource.SYSTEM;
        return task;
    }

    /**
     * 创建一个返回玩家身边的任务
     */
    public static StandardTask createReturn(String reason) {
        StandardTask task = new StandardTask(IntentType.RETURN, null, 0, reason);
        task.priority = 50;
        task.source = TaskSource.SYSTEM;
        return task;
    }

    public String getTaskId() {
        return taskId;
    }

    public IntentType getIntentType() {
        return intentType;
    }

    public String getTarget() {
        return target;
    }

    public int getAmount() {
        return amount;
    }

    public String getComment() {
        return comment;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void addParameter(String key, Object value) {
        parameters.put(key, value);
    }

    public Object getParameter(String key) {
        return parameters.get(key);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public TaskSource getSource() {
        return source;
    }

    public void setSource(TaskSource source) {
        this.source = source;
    }

    public String getOriginalInput() {
        return originalInput;
    }

    public void setOriginalInput(String originalInput) {
        this.originalInput = originalInput;
    }

    /**
     * 判断任务是否已超时
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > timeoutMs;
    }

    /**
     * 判断是否为聊天类任务（不修改世界）
     */
    public boolean isChatOnly() {
        return intentType.isChatOnly();
    }

    /**
     * 判断是否为世界修改类任务
     */
    public boolean isWorldModifying() {
        return intentType.isWorldModifying();
    }

    @Override
    public String toString() {
        return "StandardTask{" +
            "taskId='" + taskId.substring(0, 8) + "..." + '\'' +
            ", intentType=" + intentType +
            ", target='" + target + '\'' +
            ", amount=" + amount +
            ", priority=" + priority +
            '}';
    }

    /**
     * 任务来源枚举
     */
    public enum TaskSource {
        PLAYER_CHAT,    // 玩家聊天触发
        AUTO,           // 自动触发（如危险环境检测）
        SYSTEM,         // 系统触发（如超时、死亡）
        GUI             // GUI操作触发
    }
}
