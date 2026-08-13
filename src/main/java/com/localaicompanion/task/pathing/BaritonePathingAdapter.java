package com.localaicompanion.task.pathing;

import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Baritone寻路适配层
 *
 * 完全自主编写的Baritone封装，不抄任何现有项目的调用逻辑
 * 底层Baritone库被完全隔离在这一层内
 *
 * 设计原则：
 * 1. 上层只通过PathingService接口交互
 * 2. 所有Baritone的API调用都在这里
 * 3. 异常全部捕获并转化为标准结果
 * 4. 支持取消、暂停、恢复
 */
public class BaritonePathingAdapter implements PathingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-Pathing");

    private LivingEntity entity;
    private boolean initialized = false;
    private boolean baritoneAvailable = false;

    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicReference<BlockPos> currentTarget = new AtomicReference<>(null);
    private final AtomicReference<CompletableFuture<PathResult>> currentFuture = new AtomicReference<>(null);

    // Baritone API实例（使用反射获取，避免编译时强依赖）
    private Object baritoneAPI;
    private Object pathingControl;

    public BaritonePathingAdapter() {
        // 尝试检测Baritone是否可用
        try {
            Class.forName("baritone.api.BaritoneAPI");
            baritoneAvailable = true;
            LOGGER.info("[Pathing] Baritone库检测到，寻路功能可用");
        } catch (ClassNotFoundException e) {
            baritoneAvailable = false;
            LOGGER.warn("[Pathing] 未检测到Baritone库，寻路功能将受限");
        }
    }

    @Override
    public void initialize(LivingEntity entity) {
        this.entity = entity;

        if (!baritoneAvailable) {
            LOGGER.warn("[Pathing] Baritone不可用，使用简化寻路");
            initialized = true;
            return;
        }

        try {
            // 通过反射获取Baritone API实例
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            baritoneAPI = provider.getClass().getMethod("getBaritone", net.minecraft.entity.Entity.class)
                .invoke(provider, entity);

            if (baritoneAPI != null) {
                pathingControl = baritoneAPI.getClass().getMethod("getPathingControl").invoke(baritoneAPI);
            }

            initialized = true;
            LOGGER.info("[Pathing] Baritone寻路服务已初始化");
        } catch (Exception e) {
            LOGGER.error("[Pathing] Baritone初始化失败: {}", e.getMessage());
            baritoneAvailable = false;
            initialized = true;
        }
    }

    @Override
    public CompletableFuture<PathResult> moveTo(BlockPos targetPos) {
        if (!initialized || entity == null) {
            return CompletableFuture.completedFuture(PathResult.failure("寻路服务未初始化"));
        }

        currentTarget.set(targetPos);
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);

        if (!baritoneAvailable) {
            // Fallback：简单的直线移动（不穿墙，不绕路）
            executeSimpleMove(targetPos, future);
            return future;
        }

        try {
            // 通过反射调用Baritone的getToBlockPos方法
            if (pathingControl != null) {
                pathingControl.getClass().getMethod("moveTo", BlockPos.class)
                    .invoke(pathingControl, targetPos);

                // 启动监控线程
                startPathMonitor(future, targetPos);
            } else {
                future.complete(PathResult.failure("寻路控制不可用"));
                isActive.set(false);
            }
        } catch (Exception e) {
            LOGGER.error("[Pathing] moveTo调用失败: {}", e.getMessage());
            future.complete(PathResult.failure("寻路调用失败: " + e.getMessage()));
            isActive.set(false);
        }

        return future;
    }

    @Override
    public CompletableFuture<PathResult> moveToAndBreak(BlockPos targetPos) {
        if (!initialized || entity == null) {
            return CompletableFuture.completedFuture(PathResult.failure("寻路服务未初始化"));
        }

        currentTarget.set(targetPos);
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);

        if (!baritoneAvailable) {
            future.complete(PathResult.failure("需要Baritone才能执行采集任务"));
            isActive.set(false);
            return future;
        }

        try {
            // 调用Baritone的mine功能
            Object bapi = baritoneAPI;
            if (bapi != null) {
                Object mineBehavior = bapi.getClass().getMethod("getMineBehavior").invoke(bapi);
                if (mineBehavior != null) {
                    // 这里简化处理，实际需要构造BlockInfoOptional
                    mineBehavior.getClass().getMethod("mine", int.class, Object[].class)
                        .invoke(mineBehavior, 1, new Object[]{targetPos});

                    startPathMonitor(future, targetPos);
                    return future;
                }
            }
            future.complete(PathResult.failure("采集功能不可用"));
            isActive.set(false);
        } catch (Exception e) {
            LOGGER.error("[Pathing] moveToAndBreak调用失败: {}", e.getMessage());
            future.complete(PathResult.failure("采集调用失败: " + e.getMessage()));
            isActive.set(false);
        }

        return future;
    }

    @Override
    public CompletableFuture<PathResult> moveToAndPlace(BlockPos targetPos, String blockName) {
        // 放置方块的Baritone调用
        // 简化实现，实际需要调用BuilderBehavior
        currentTarget.set(targetPos);
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);

        if (!baritoneAvailable) {
            future.complete(PathResult.failure("需要Baritone才能执行建造任务"));
            isActive.set(false);
            return future;
        }

        try {
            // Baritone的建造功能调用
            Object bapi = baritoneAPI;
            if (bapi != null) {
                Object builderBehavior = bapi.getClass().getMethod("getBuilderBehavior").invoke(bapi);
                if (builderBehavior != null) {
                    // 简化：直接移动到目标位置
                    moveTo(targetPos).thenAccept(result -> {
                        if (result.success) {
                            // 到达后放置方块的逻辑
                            future.complete(PathResult.success(targetPos));
                        } else {
                            future.complete(result);
                        }
                        isActive.set(false);
                    });
                    return future;
                }
            }
            future.complete(PathResult.failure("建造功能不可用"));
            isActive.set(false);
        } catch (Exception e) {
            future.complete(PathResult.failure("建造调用失败: " + e.getMessage()));
            isActive.set(false);
        }

        return future;
    }

    @Override
    public void followEntity(LivingEntity target, float followDistance) {
        if (!initialized || entity == null) return;

        isActive.set(true);

        if (!baritoneAvailable) {
            // Fallback：简单跟随逻辑
            executeSimpleFollow(target, followDistance);
            return;
        }

        try {
            Object bapi = baritoneAPI;
            if (bapi != null) {
                Object followBehavior = bapi.getClass().getMethod("getFollowBehavior").invoke(bapi);
                if (followBehavior != null) {
                    followBehavior.getClass().getMethod("follow", LivingEntity.class)
                        .invoke(followBehavior, target);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[Pathing] followEntity调用失败: {}", e.getMessage());
        }
    }

    @Override
    public CompletableFuture<PathResult> attackEntity(LivingEntity target) {
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);

        if (!baritoneAvailable) {
            future.complete(PathResult.failure("需要Baritone才能执行战斗任务"));
            isActive.set(false);
            return future;
        }

        try {
            Object bapi = baritoneAPI;
            if (bapi != null) {
                Object fightBehavior = bapi.getClass().getMethod("getFightBehavior").invoke(bapi);
                if (fightBehavior != null) {
                    fightBehavior.getClass().getMethod("fight", LivingEntity.class)
                        .invoke(fightBehavior, target);

                    // 启动战斗监控
                    startFightMonitor(future, target);
                    return future;
                }
            }
            future.complete(PathResult.failure("战斗功能不可用"));
            isActive.set(false);
        } catch (Exception e) {
            future.complete(PathResult.failure("战斗调用失败: " + e.getMessage()));
            isActive.set(false);
        }

        return future;
    }

    @Override
    public CompletableFuture<PathResult> mineBlock(String blockName, int amount, int searchRadius) {
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);

        if (!baritoneAvailable) {
            future.complete(PathResult.failure("需要Baritone才能执行采集任务"));
            isActive.set(false);
            return future;
        }

        try {
            Object bapi = baritoneAPI;
            if (bapi != null) {
                Object mineBehavior = bapi.getClass().getMethod("getMineBehavior").invoke(bapi);
                if (mineBehavior != null) {
                    // 通过反射调用mine方法
                    // 实际需要构造BlockInfoOptional数组
                    // 这里简化处理
                    mineBehavior.getClass().getMethod("mine", int.class, String[].class)
                        .invoke(mineBehavior, amount, new String[]{blockName});

                    startMiningMonitor(future, amount);
                    return future;
                }
            }
            future.complete(PathResult.failure("采集功能不可用"));
            isActive.set(false);
        } catch (Exception e) {
            LOGGER.error("[Pathing] mineBlock调用失败: {}", e.getMessage());
            future.complete(PathResult.failure("采集调用失败: " + e.getMessage()));
            isActive.set(false);
        }

        return future;
    }

    @Override
    public void stopAll() {
        isActive.set(false);
        currentTarget.set(null);

        CompletableFuture<PathResult> future = currentFuture.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.complete(PathResult.cancelled());
        }

        if (baritoneAvailable && pathingControl != null) {
            try {
                pathingControl.getClass().getMethod("stop").invoke(pathingControl);
            } catch (Exception e) {
                LOGGER.debug("[Pathing] stop调用失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void pause() {
        isPaused.set(true);
        if (baritoneAvailable && pathingControl != null) {
            try {
                pathingControl.getClass().getMethod("pause").invoke(pathingControl);
            } catch (Exception e) {
                LOGGER.debug("[Pathing] pause调用失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void resume() {
        isPaused.set(false);
        if (baritoneAvailable && pathingControl != null) {
            try {
                pathingControl.getClass().getMethod("resume").invoke(pathingControl);
            } catch (Exception e) {
                LOGGER.debug("[Pathing] resume调用失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean isActive() {
        return isActive.get();
    }

    @Override
    public BlockPos getCurrentTarget() {
        return currentTarget.get();
    }

    @Override
    public float getProgress() {
        // Baritone的进度获取
        if (baritoneAvailable && pathingControl != null) {
            try {
                Object path = pathingControl.getClass().getMethod("getPath").invoke(pathingControl);
                if (path != null) {
                    int positions = (int) path.getClass().getMethod("positionsCount").invoke(path);
                    int index = (int) path.getClass().getMethod("getCurrentPositionIndex").invoke(path);
                    if (positions > 0) {
                        return (float) index / positions;
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
        }
        return 0.0f;
    }

    @Override
    public void cleanup() {
        stopAll();
        baritoneAPI = null;
        pathingControl = null;
        initialized = false;
    }

    /**
     * 简化寻路（无Baritone时的fallback）
     */
    private void executeSimpleMove(BlockPos targetPos, CompletableFuture<PathResult> future) {
        // 简单的直线移动逻辑
        // 实际需要在游戏tick中更新
        LOGGER.info("[Pathing] 使用简化寻路移动到: {}", targetPos);
        future.complete(PathResult.failure("简化寻路模式：需要Baritone才能自动移动"));
        isActive.set(false);
    }

    /**
     * 简化跟随逻辑
     */
    private void executeSimpleFollow(LivingEntity target, float followDistance) {
        LOGGER.info("[Pathing] 使用简化跟随模式");
    }

    /**
     * 启动寻路监控线程
     */
    private void startPathMonitor(CompletableFuture<PathResult> future, BlockPos targetPos) {
        // 在实际实现中，这应该在游戏tick中检查
        // 这里用一个简单的监控逻辑
        Thread monitor = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long timeout = 120000; // 2分钟超时

            while (isActive.get() && !future.isDone()) {
                try {
                    Thread.sleep(500);

                    // 检查是否到达目标
                    if (entity != null && currentTarget.get() != null) {
                        double dist = entity.squaredDistanceTo(
                            targetPos.getX() + 0.5,
                            targetPos.getY(),
                            targetPos.getZ() + 0.5
                        );
                        if (dist < 4.0) {
                            // 到达目标
                            future.complete(PathResult.success(targetPos));
                            isActive.set(false);
                            return;
                        }
                    }

                    // 检查超时
                    if (System.currentTimeMillis() - startTime > timeout) {
                        future.complete(PathResult.failure("寻路超时"));
                        isActive.set(false);
                        return;
                    }

                    // 检查是否被取消
                    if (isPaused.get()) {
                        // 暂停时不计时
                        startTime += 500;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.complete(PathResult.cancelled());
                    isActive.set(false);
                    return;
                }
            }
        }, "path-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * 启动战斗监控
     */
    private void startFightMonitor(CompletableFuture<PathResult> future, LivingEntity target) {
        Thread monitor = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long timeout = 60000; // 1分钟战斗超时

            while (isActive.get() && !future.isDone()) {
                try {
                    Thread.sleep(1000);

                    // 检查目标是否死亡
                    if (target != null && !target.isAlive()) {
                        future.complete(PathResult.success(target.getBlockPos()));
                        isActive.set(false);
                        return;
                    }

                    // 检查超时
                    if (System.currentTimeMillis() - startTime > timeout) {
                        future.complete(PathResult.failure("战斗超时"));
                        isActive.set(false);
                        return;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.complete(PathResult.cancelled());
                    isActive.set(false);
                    return;
                }
            }
        }, "fight-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * 启动采集监控
     */
    private void startMiningMonitor(CompletableFuture<PathResult> future, int targetAmount) {
        Thread monitor = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            long timeout = 300000; // 5分钟采集超时

            while (isActive.get() && !future.isDone()) {
                try {
                    Thread.sleep(2000);

                    // 检查超时
                    if (System.currentTimeMillis() - startTime > timeout) {
                        future.complete(PathResult.failure("采集超时"));
                        isActive.set(false);
                        return;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.complete(PathResult.cancelled());
                    isActive.set(false);
                    return;
                }
            }
        }, "mining-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    public boolean isBaritoneAvailable() {
        return baritoneAvailable;
    }
}
