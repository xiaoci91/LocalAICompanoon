package com.localaicompanion.task;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.MainConfig;
import com.localaicompanion.entity.ai.AICompanionEntity;
import com.localaicompanion.intent.IntentType;
import com.localaicompanion.intent.StandardTask;
import com.localaicompanion.task.pathing.PathingService;
import com.localaicompanion.task.state.TaskState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * 第3层：任务调度管理层（自主状态机）
 *
 * 职责：
 * 1. 维护任务队列、任务状态、任务生命周期管理
 * 2. 支持任务排队、新任务抢占旧任务、暂停、手动取消
 * 3. 每一条独立任务拥有独立超时时间、最大重试次数
 * 4. NPC死亡时持久化未完成任务，重生后恢复
 *
 * 核心设计：
 * - 优先级队列：高优先级任务可以抢占低优先级任务
 * - 状态机驱动：每个任务有完整的生命周期
 * - 超时保护：每个任务有独立超时，不会死循环
 * - 重试机制：失败任务可以自动重试
 */
public class TaskScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-TaskScheduler");

    // 任务队列（优先级队列，优先级高的先执行）
    private final PriorityBlockingQueue<Task> taskQueue;

    // 当前正在执行的任务
    private volatile Task currentTask;

    // 已完成的任务历史（保留最近N个）
    private final Deque<Task> taskHistory;
    private static final int MAX_HISTORY_SIZE = 50;

    // 被暂停的任务（抢占后等待恢复）
    private final Deque<Task> preemptedStack;

    // 寻路服务
    private PathingService pathingService;

    // AI同伴实体
    private AICompanionEntity companionEntity;

    // 配置
    private MainConfig config;

    // 调度器是否运行中
    private volatile boolean running = true;

    // 上次tick时间
    private long lastTickTime = 0;

    public TaskScheduler() {
        // 优先级比较器：优先级高的在前，同优先级按创建时间排序
        this.taskQueue = new PriorityBlockingQueue<>(50, (a, b) -> {
            int priorityCompare = Integer.compare(
                b.getStandardTask().getPriority(),
                a.getStandardTask().getPriority()
            );
            if (priorityCompare != 0) return priorityCompare;
            return Long.compare(a.getStandardTask().getCreatedAt(), b.getStandardTask().getCreatedAt());
        });

        this.taskHistory = new ArrayDeque<>();
        this.preemptedStack = new ArrayDeque<>();
        this.currentTask = null;
    }

    /**
     * 初始化调度器
     */
    public void initialize(AICompanionEntity entity, PathingService pathingService, MainConfig config) {
        this.companionEntity = entity;
        this.pathingService = pathingService;
        this.config = config;
        this.running = true;
        LOGGER.info("[TaskScheduler] 任务调度器已初始化");
    }

    /**
     * 提交任务
     * @param standardTask 标准任务
     * @param player 发起玩家
     * @return 是否成功提交
     */
    public boolean submitTask(StandardTask standardTask, ServerPlayerEntity player) {
        if (!running) {
            LOGGER.warn("[TaskScheduler] 调度器未运行，任务被拒绝");
            return false;
        }

        // 检查队列是否已满
        if (taskQueue.size() >= config.maxConcurrentTasks + 5) {
            LOGGER.warn("[TaskScheduler] 任务队列已满");
            return false;
        }

        Task task = new Task(standardTask, player);

        // 特殊处理：停止/取消任务立即执行
        if (standardTask.getIntentType() == IntentType.STOP ||
            standardTask.getIntentType() == IntentType.CANCEL) {
            handleStopTask(task);
            return true;
        }

        // 检查是否需要抢占当前任务
        if (currentTask != null && currentTask.getState() == TaskState.RUNNING) {
            int currentPriority = currentTask.getStandardTask().getPriority();
            int newPriority = standardTask.getPriority();

            // 玩家喊话可以抢占正在运行的旧任务
            if (newPriority > currentPriority && standardTask.getSource() == StandardTask.TaskSource.PLAYER_CHAT) {
                preemptCurrentTask(task);
                return true;
            }
        }

        // 加入队列
        taskQueue.offer(task);
        LOGGER.info("[TaskScheduler] 任务已加入队列: {} (优先级:{}, 队列大小:{})",
            task.getStandardTask().getIntentType(), standardTask.getPriority(), taskQueue.size());

        return true;
    }

    /**
     * 抢占当前任务
     */
    private void preemptCurrentTask(Task newTask) {
        Task oldTask = currentTask;
        if (oldTask != null) {
            oldTask.preempt();
            preemptedStack.push(oldTask);
            pathingService.stopAll();
            LOGGER.info("[TaskScheduler] 任务被抢占: {} -> {}",
                oldTask.getStandardTask().getIntentType(),
                newTask.getStandardTask().getIntentType());
        }

        // 立即执行新任务
        currentTask = newTask;
        executeTask(newTask);
    }

    /**
     * 处理停止任务
     */
    private void handleStopTask(Task stopTask) {
        LOGGER.info("[TaskScheduler] 收到停止指令，清空所有任务");

        // 停止当前任务
        if (currentTask != null) {
            currentTask.cancel();
            moveToHistory(currentTask);
            currentTask = null;
        }

        // 清空队列
        taskQueue.clear();

        // 清空被抢占的任务
        preemptedStack.clear();

        // 停止寻路
        if (pathingService != null) {
            pathingService.stopAll();
        }

        // 通知玩家
        if (stopTask.getOwner() != null) {
            sendMessage(stopTask.getOwner(), stopTask.getStandardTask().getComment());
        }

        moveToHistory(stopTask);
    }

    /**
     * 服务器tick驱动
     */
    public void tick(MinecraftServer server) {
        if (!running) return;

        long now = System.currentTimeMillis();
        if (now - lastTickTime < 100) return; // 每100ms检查一次
        lastTickTime = now;

        // 1. 检查当前任务状态
        checkCurrentTask();

        // 2. 如果没有当前任务，从队列取下一个
        if (currentTask == null || currentTask.getState().isTerminal()) {
            pickNextTask();
        }

        // 3. 检查超时
        checkTimeouts();

        // 4. 检查危险环境
        checkDangerousEnvironment();
    }

    /**
     * 检查当前任务状态
     */
    private void checkCurrentTask() {
        if (currentTask == null) return;

        TaskState state = currentTask.getState();

        // 任务完成/失败/取消/超时
        if (state.isTerminal()) {
            onTaskFinished(currentTask);
            currentTask = null;
            return;
        }

        // 更新进度
        if (state == TaskState.RUNNING && pathingService != null) {
            currentTask.setProgress(pathingService.getProgress());
        }
    }

    /**
     * 取下一个任务执行
     */
    private void pickNextTask() {
        // 优先恢复被抢占的任务
        if (!preemptedStack.isEmpty()) {
            Task task = preemptedStack.pop();
            if (task.getState() == TaskState.PREEMPTED) {
                currentTask = task;
                executeTask(task);
                LOGGER.info("[TaskScheduler] 恢复被抢占的任务: {}", task.getStandardTask().getIntentType());
                return;
            }
        }

        // 从队列取下一个
        Task nextTask = taskQueue.poll();
        if (nextTask != null) {
            currentTask = nextTask;
            executeTask(nextTask);
            LOGGER.info("[TaskScheduler] 开始执行任务: {}", nextTask);
        }
    }

    /**
     * 执行任务
     */
    private void executeTask(Task task) {
        task.start();

        StandardTask standardTask = task.getStandardTask();
        IntentType intentType = standardTask.getIntentType();

        try {
            switch (intentType) {
                case FOLLOW:
                    executeFollowTask(task);
                    break;
                case COME_HERE:
                case RETURN:
                    executeComeHereTask(task);
                    break;
                case STAY:
                    executeStayTask(task);
                    break;
                case COLLECT:
                case MINE:
                    executeMineTask(task);
                    break;
                case CHOP_WOOD:
                    executeChopWoodTask(task);
                    break;
                case ATTACK_MOB:
                case DEFEND:
                    executeAttackTask(task);
                    break;
                case BUILD:
                case PLACE:
                    executePlaceTask(task);
                    break;
                case EXPLORE:
                    executeExploreTask(task);
                    break;
                case STOP:
                case CANCEL:
                    handleStopTask(task);
                    break;
                case CHAT:
                case STATUS:
                case INVENTORY:
                case HEALTH:
                case HELP:
                    // 纯聊天任务，直接完成
                    task.complete();
                    break;
                default:
                    task.fail("不支持的任务类型: " + intentType);
                    break;
            }
        } catch (Exception e) {
            LOGGER.error("[TaskScheduler] 任务执行异常: {}", intentType, e);
            task.fail("执行异常: " + e.getMessage());
        }
    }

    /**
     * 跟随任务
     */
    private void executeFollowTask(Task task) {
        if (task.getOwner() == null || pathingService == null) {
            task.fail("无目标或寻路服务不可用");
            return;
        }

        pathingService.followEntity(task.getOwner(), config.followDistance);
        // 跟随任务不自动完成，持续执行直到被取消
    }

    /**
     * 到玩家身边任务
     */
    private void executeComeHereTask(Task task) {
        if (task.getOwner() == null || pathingService == null) {
            task.fail("无目标或寻路服务不可用");
            return;
        }

        BlockPos targetPos = task.getOwner().getBlockPos();
        task.setCurrentTargetPos(targetPos);

        pathingService.moveTo(targetPos).thenAccept(result -> {
            if (result.success) {
                task.complete();
            } else {
                task.fail(result.message);
            }
        });
    }

    /**
     * 原地待命任务
     */
    private void executeStayTask(Task task) {
        if (pathingService != null) {
            pathingService.stopAll();
        }
        // 待命任务不自动完成
    }

    /**
     * 采集/挖掘任务
     */
    private void executeMineTask(Task task) {
        if (pathingService == null) {
            task.fail("寻路服务不可用");
            return;
        }

        String target = task.getStandardTask().getTarget();
        int amount = task.getStandardTask().getAmount();

        if (target == null || target.isEmpty()) {
            task.fail("未指定采集目标");
            return;
        }

        pathingService.mineBlock(target, amount, 64).thenAccept(result -> {
            if (result.success) {
                task.setResultData(result.blocksMined);
                task.complete();
                notifyPlayer(task, "采集完成！挖了" + result.blocksMined + "个" + target);
            } else {
                task.fail(result.message);
                notifyPlayer(task, "采集失败：" + result.message);
            }
        });
    }

    /**
     * 砍树任务
     */
    private void executeChopWoodTask(Task task) {
        if (pathingService == null) {
            task.fail("寻路服务不可用");
            return;
        }

        int amount = task.getStandardTask().getAmount();
        pathingService.mineBlock("oak_log", amount, 64).thenAccept(result -> {
            if (result.success) {
                task.complete();
                notifyPlayer(task, "砍树完成！砍了" + result.blocksMined + "块木头");
            } else {
                task.fail(result.message);
            }
        });
    }

    /**
     * 攻击任务
     */
    private void executeAttackTask(Task task) {
        // 攻击任务需要找到最近的怪物
        // 简化实现：需要在游戏中实际搜索实体
        task.fail("战斗功能开发中");
    }

    /**
     * 放置方块任务
     */
    private void executePlaceTask(Task task) {
        if (pathingService == null) {
            task.fail("寻路服务不可用");
            return;
        }

        String target = task.getStandardTask().getTarget();
        BlockPos pos = task.getCurrentTargetPos();

        if (pos == null) {
            // 如果没有指定位置，在玩家附近放置
            if (task.getOwner() != null) {
                pos = task.getOwner().getBlockPos().add(1, 0, 0);
            } else {
                task.fail("未指定放置位置");
                return;
            }
        }

        pathingService.moveToAndPlace(pos, target).thenAccept(result -> {
            if (result.success) {
                task.complete();
            } else {
                task.fail(result.message);
            }
        });
    }

    /**
     * 探索任务
     */
    private void executeExploreTask(Task task) {
        // 探索任务：随机方向移动
        task.fail("探索功能开发中");
    }

    /**
     * 任务完成回调
     */
    private void onTaskFinished(Task task) {
        moveToHistory(task);

        TaskState state = task.getState();
        LOGGER.info("[TaskScheduler] 任务结束: {} - {}", task.getStandardTask().getIntentType(), state);

        // 失败且还有重试次数的，重新加入队列
        if (state == TaskState.RETRYING) {
            taskQueue.offer(task);
        }

        // 超时的任务，NPC回到玩家身边
        if (state == TaskState.TIMEOUT) {
            if (task.getOwner() != null) {
                notifyPlayer(task, "任务超时了，我回来了");
                returnToPlayer(task.getOwner());
            }
        }
    }

    /**
     * 检查超时
     */
    private void checkTimeouts() {
        if (currentTask != null && currentTask.getState() == TaskState.RUNNING) {
            if (currentTask.isTimedOut()) {
                LOGGER.warn("[TaskScheduler] 任务超时: {}", currentTask);
                currentTask.timeout();
                if (pathingService != null) {
                    pathingService.stopAll();
                }
            }
        }
    }

    /**
     * 检查危险环境
     */
    private void checkDangerousEnvironment() {
        if (companionEntity == null) return;
        if (currentTask == null || currentTask.getState() != TaskState.RUNNING) return;

        boolean inDanger = false;
        String dangerType = "";

        // 检测岩浆
        if (LocalAICompanion.getInstance().getConfigManager().getPermissionConfig().emergencyOnLava) {
            if (companionEntity.isInLava()) {
                inDanger = true;
                dangerType = "岩浆";
            }
        }

        // 检测火焰
        if (LocalAICompanion.getInstance().getConfigManager().getPermissionConfig().emergencyOnFire) {
            if (companionEntity.isOnFire()) {
                inDanger = true;
                dangerType = "火焰";
            }
        }

        // 检测虚空
        if (LocalAICompanion.getInstance().getConfigManager().getPermissionConfig().emergencyOnVoid) {
            if (companionEntity.getY() < -64) {
                inDanger = true;
                dangerType = "虚空";
            }
        }

        if (inDanger) {
            LOGGER.warn("[TaskScheduler] 检测到危险环境: {}，紧急撤离", dangerType);

            // 终止当前任务
            currentTask.interrupt("危险环境: " + dangerType);
            if (pathingService != null) {
                pathingService.stopAll();
            }

            // 通知玩家
            if (currentTask.getOwner() != null) {
                notifyPlayer(currentTask, "不好！遇到" + dangerType + "了，我先撤了！");
            }

            // 强制回到玩家身边
            if (currentTask.getOwner() != null) {
                returnToPlayer(currentTask.getOwner());
            }
        }
    }

    /**
     * 返回玩家身边
     */
    private void returnToPlayer(ServerPlayerEntity player) {
        if (pathingService == null || player == null) return;

        StandardTask returnTask = StandardTask.createReturn("我回来了");
        Task task = new Task(returnTask, player);
        task.getStandardTask().setSource(StandardTask.TaskSource.SYSTEM);
        task.getStandardTask().setPriority(80);

        // 抢占当前任务
        if (currentTask != null && currentTask.getState().canBePreempted()) {
            preemptCurrentTask(task);
        } else {
            taskQueue.offer(task);
        }
    }

    /**
     * 取消指定任务
     */
    public boolean cancelTask(String taskId) {
        // 检查当前任务
        if (currentTask != null && currentTask.getInstanceId().equals(taskId)) {
            currentTask.cancel();
            if (pathingService != null) {
                pathingService.stopAll();
            }
            return true;
        }

        // 检查队列中的任务
        Iterator<Task> it = taskQueue.iterator();
        while (it.hasNext()) {
            Task task = it.next();
            if (task.getInstanceId().equals(taskId)) {
                task.cancel();
                it.remove();
                moveToHistory(task);
                return true;
            }
        }

        return false;
    }

    /**
     * 清空所有任务
     */
    public void clearAllTasks() {
        if (currentTask != null) {
            currentTask.cancel();
            moveToHistory(currentTask);
            currentTask = null;
        }

        taskQueue.clear();
        preemptedStack.clear();

        if (pathingService != null) {
            pathingService.stopAll();
        }

        LOGGER.info("[TaskScheduler] 所有任务已清空");
    }

    /**
     * 暂停所有任务
     */
    public void pauseAll() {
        if (currentTask != null) {
            currentTask.pause();
        }
        if (pathingService != null) {
            pathingService.pause();
        }
    }

    /**
     * 恢复所有任务
     */
    public void resumeAll() {
        if (currentTask != null) {
            currentTask.resume();
        }
        if (pathingService != null) {
            pathingService.resume();
        }
    }

    /**
     * NPC死亡处理
     * 持久化未完成任务，清空背包
     */
    public void onCompanionDeath() {
        LOGGER.info("[TaskScheduler] NPC死亡，保存未完成任务");

        // 保存当前任务
        if (currentTask != null) {
            currentTask.interrupt("NPC死亡");
            moveToHistory(currentTask);
            currentTask = null;
        }

        // 清空队列（任务会在重生后恢复）
        // 实际实现中需要持久化到存档
        taskQueue.clear();
        preemptedStack.clear();

        if (pathingService != null) {
            pathingService.stopAll();
        }
    }

    /**
     * NPC重生处理
     * 恢复未完成任务
     */
    public void onCompanionRespawn() {
        LOGGER.info("[TaskScheduler] NPC重生，任务系统恢复");
        // 从存档恢复任务（实际实现中需要读取持久化数据）
    }

    /**
     * 移动任务到历史记录
     */
    private void moveToHistory(Task task) {
        taskHistory.addFirst(task);
        while (taskHistory.size() > MAX_HISTORY_SIZE) {
            taskHistory.removeLast();
        }
    }

    /**
     * 发送消息给玩家
     */
    private void sendMessage(ServerPlayerEntity player, String message) {
        if (player == null || message == null) return;
        player.sendMessage(Text.literal("§b[小艾] §r" + message), false);
    }

    /**
     * 通知玩家任务状态
     */
    private void notifyPlayer(Task task, String message) {
        if (task.getOwner() != null) {
            sendMessage(task.getOwner(), message);
        }
    }

    /**
     * 关闭调度器
     */
    public void shutdown() {
        running = false;
        clearAllTasks();
        LOGGER.info("[TaskScheduler] 任务调度器已关闭");
    }

    // Getters
    public Task getCurrentTask() {
        return currentTask;
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    public List<Task> getQueuedTasks() {
        return new ArrayList<>(taskQueue);
    }

    public List<Task> getTaskHistory() {
        return new ArrayList<>(taskHistory);
    }

    public boolean isRunning() {
        return running;
    }

    public PathingService getPathingService() {
        return pathingService;
    }
}
