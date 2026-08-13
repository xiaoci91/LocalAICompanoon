package com.localaicompanion.task;

import com.localaicompanion.intent.StandardTask;
import com.localaicompanion.task.state.TaskState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.util.UUID;

/**
 * 任务实例
 * 包装StandardTask，添加运行时状态和调度信息
 *
 * 这是任务调度器管理的基本单元
 */
public class Task {
    // 任务实例ID（不同于StandardTask的taskId）
    private final String instanceId;

    // 标准任务定义
    private final StandardTask standardTask;

    // 任务状态
    private volatile TaskState state;

    // 发起任务的玩家
    private final ServerPlayerEntity owner;

    // 任务开始时间
    private volatile long startTime;

    // 任务结束时间
    private volatile long endTime;

    // 已重试次数
    private volatile int retryCount;

    // 当前执行位置
    private volatile BlockPos currentTargetPos;

    // 任务进度 (0.0 - 1.0)
    private volatile float progress;

    // 最后错误信息
    private volatile String lastError;

    // 任务执行结果数据
    private volatile Object resultData;

    public Task(StandardTask standardTask, ServerPlayerEntity owner) {
        this.instanceId = UUID.randomUUID().toString();
        this.standardTask = standardTask;
        this.state = TaskState.PENDING;
        this.owner = owner;
        this.startTime = 0;
        this.endTime = 0;
        this.retryCount = 0;
        this.progress = 0.0f;
        this.lastError = null;
    }

    /**
     * 开始执行
     */
    public void start() {
        if (state == TaskState.PENDING || state == TaskState.RETRYING || state == TaskState.PAUSED) {
            this.state = TaskState.RUNNING;
            if (startTime == 0) {
                this.startTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * 暂停
     */
    public void pause() {
        if (state == TaskState.RUNNING) {
            this.state = TaskState.PAUSED;
        }
    }

    /**
     * 恢复
     */
    public void resume() {
        if (state == TaskState.PAUSED) {
            this.state = TaskState.RUNNING;
        }
    }

    /**
     * 完成
     */
    public void complete() {
        this.state = TaskState.COMPLETED;
        this.endTime = System.currentTimeMillis();
        this.progress = 1.0f;
    }

    /**
     * 失败
     */
    public void fail(String error) {
        this.lastError = error;
        if (retryCount < standardTask.getMaxRetries()) {
            // 还可以重试
            this.state = TaskState.RETRYING;
            this.retryCount++;
        } else {
            // 超过最大重试次数，彻底失败
            this.state = TaskState.FAILED;
            this.endTime = System.currentTimeMillis();
        }
    }

    /**
     * 取消
     */
    public void cancel() {
        this.state = TaskState.CANCELLED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 超时
     */
    public void timeout() {
        this.state = TaskState.TIMEOUT;
        this.endTime = System.currentTimeMillis();
        this.lastError = "任务超时";
    }

    /**
     * 被抢占
     */
    public void preempt() {
        if (state.canBePreempted()) {
            this.state = TaskState.PREEMPTED;
        }
    }

    /**
     * 中断（NPC死亡等）
     */
    public void interrupt(String reason) {
        this.state = TaskState.INTERRUPTED;
        this.endTime = System.currentTimeMillis();
        this.lastError = reason;
    }

    /**
     * 检查是否超时
     */
    public boolean isTimedOut() {
        if (state != TaskState.RUNNING) return false;
        if (startTime == 0) return false;
        return System.currentTimeMillis() - startTime > standardTask.getTimeoutMs();
    }

    /**
     * 获取已运行时间（毫秒）
     */
    public long getElapsedTime() {
        if (startTime == 0) return 0;
        if (state.isTerminal()) {
            return endTime - startTime;
        }
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 获取剩余时间（毫秒）
     */
    public long getRemainingTime() {
        if (startTime == 0) return standardTask.getTimeoutMs();
        long elapsed = System.currentTimeMillis() - startTime;
        return Math.max(0, standardTask.getTimeoutMs() - elapsed);
    }

    // Getters
    public String getInstanceId() {
        return instanceId;
    }

    public StandardTask getStandardTask() {
        return standardTask;
    }

    public TaskState getState() {
        return state;
    }

    public ServerPlayerEntity getOwner() {
        return owner;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public BlockPos getCurrentTargetPos() {
        return currentTargetPos;
    }

    public float getProgress() {
        return progress;
    }

    public String getLastError() {
        return lastError;
    }

    public Object getResultData() {
        return resultData;
    }

    // Setters
    public void setCurrentTargetPos(BlockPos currentTargetPos) {
        this.currentTargetPos = currentTargetPos;
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0, Math.min(1, progress));
    }

    public void setResultData(Object resultData) {
        this.resultData = resultData;
    }

    @Override
    public String toString() {
        return "Task{" +
            "id='" + instanceId.substring(0, 8) + "'" +
            ", type=" + standardTask.getIntentType() +
            ", state=" + state +
            ", progress=" + String.format("%.0f%%", progress * 100) +
            ", retry=" + retryCount + "/" + standardTask.getMaxRetries() +
            '}';
    }
}
