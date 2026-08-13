package com.localaicompanion.task.state;

/**
 * 任务状态枚举
 * 任务生命周期的完整状态机
 */
public enum TaskState {
    // 等待执行
    PENDING("等待中"),

    // 正在执行
    RUNNING("执行中"),

    // 暂停
    PAUSED("已暂停"),

    // 重试中（失败后等待重试）
    RETRYING("重试中"),

    // 被抢占（高优先级任务打断）
    PREEMPTED("被抢占"),

    // 执行成功
    COMPLETED("已完成"),

    // 执行失败（超过最大重试次数）
    FAILED("已失败"),

    // 被取消
    CANCELLED("已取消"),

    // 超时
    TIMEOUT("已超时"),

    // 被中断（NPC死亡等）
    INTERRUPTED("被中断");

    private final String displayName;

    TaskState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否为终态（任务已结束）
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED ||
               this == TIMEOUT || this == INTERRUPTED;
    }

    /**
     * 是否可以被抢占
     */
    public boolean canBePreempted() {
        return this == PENDING || this == RUNNING || this == PAUSED;
    }

    /**
     * 是否正在执行（活跃状态）
     */
    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == PAUSED || this == RETRYING;
    }
}
