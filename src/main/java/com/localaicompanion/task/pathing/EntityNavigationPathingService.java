package com.localaicompanion.task.pathing;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实体导航寻路服务
 *
 * 使用Minecraft实体自带的Navigation系统进行寻路
 * 适用于AI同伴实体的基础移动
 *
 * 功能：
 * - 移动到指定位置
 * - 跟随目标实体
 * - 基础的方块采集（走到旁边然后破坏）
 */
public class EntityNavigationPathingService implements PathingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-NavPathing");

    private MobEntity entity;
    private EntityNavigation navigation;
    private World world;
    private boolean initialized = false;

    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicReference<BlockPos> currentTarget = new AtomicReference<>(null);
    private final AtomicReference<CompletableFuture<PathResult>> currentFuture = new AtomicReference<>(null);

    // 跟随相关
    private LivingEntity followTarget;
    private float followDistance = 3.0f;
    private boolean following = false;

    @Override
    public void initialize(LivingEntity entity) {
        if (!(entity instanceof MobEntity)) {
            LOGGER.error("[NavPathing] 实体不是MobEntity，无法使用导航");
            initialized = false;
            return;
        }

        this.entity = (MobEntity) entity;
        this.navigation = this.entity.getNavigation();
        this.world = entity.getWorld();
        this.initialized = true;

        LOGGER.info("[NavPathing] 实体导航寻路服务已初始化");
    }

    @Override
    public CompletableFuture<PathResult> moveTo(BlockPos targetPos) {
        if (!initialized || entity == null || navigation == null) {
            return CompletableFuture.completedFuture(PathResult.failure("寻路服务未初始化"));
        }

        // 停止当前任务
        stopAll();

        currentTarget.set(targetPos);
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);

        try {
            // 开始寻路
            boolean started = navigation.startMovingTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
            if (!started) {
                future.complete(PathResult.failure("无法找到路径"));
                isActive.set(false);
                return future;
            }

            LOGGER.info("[NavPathing] 开始移动到: {}", targetPos);
        } catch (Exception e) {
            LOGGER.error("[NavPathing] moveTo失败: {}", e.getMessage());
            future.complete(PathResult.failure("寻路失败: " + e.getMessage()));
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

        // 先移动到目标旁边
        moveTo(targetPos).thenAccept(result -> {
            if (result.success) {
                // 到达后破坏方块
                try {
                    boolean broken = world.breakBlock(targetPos, true, entity);
                    if (broken) {
                        future.complete(PathResult.successMined(targetPos, 1));
                    } else {
                        future.complete(PathResult.failure("无法破坏方块"));
                    }
                } catch (Exception e) {
                    future.complete(PathResult.failure("破坏方块失败: " + e.getMessage()));
                }
                isActive.set(false);
            } else {
                future.complete(result);
                isActive.set(false);
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<PathResult> moveToAndPlace(BlockPos targetPos, String blockName) {
        // 放置方块功能暂未实现
        return CompletableFuture.completedFuture(PathResult.failure("放置方块功能开发中"));
    }

    @Override
    public void followEntity(LivingEntity target, float followDistance) {
        if (!initialized || entity == null || navigation == null) return;

        this.followTarget = target;
        this.followDistance = followDistance;
        this.following = true;
        isActive.set(true);

        LOGGER.info("[NavPathing] 开始跟随: {}", target.getName().getString());
    }

    @Override
    public CompletableFuture<PathResult> attackEntity(LivingEntity target) {
        // 战斗功能暂未实现
        return CompletableFuture.completedFuture(PathResult.failure("战斗功能开发中"));
    }

    @Override
    public CompletableFuture<PathResult> mineBlock(String blockName, int amount, int searchRadius) {
        if (!initialized || entity == null || world == null) {
            return CompletableFuture.completedFuture(PathResult.failure("寻路服务未初始化"));
        }

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);

        // 在新线程中搜索并采集
        Thread miningThread = new Thread(() -> {
            int mined = 0;
            BlockPos lastPos = entity.getBlockPos();

            try {
                while (mined < amount && isActive.get() && !future.isDone()) {
                    // 搜索附近的目标方块
                    BlockPos target = findNearbyBlock(blockName, searchRadius);
                    if (target == null) {
                        future.complete(PathResult.failure("附近找不到" + blockName));
                        isActive.set(false);
                        return;
                    }

                    LOGGER.info("[NavPathing] 找到目标方块: {}, 已挖: {}/{}", target, mined, amount);

                    // 移动到目标旁边并破坏
                    // 注意：这是在异步线程，需要在服务器线程执行
                    // 简化处理：直接设置目标，等待实体自己走过去
                    BlockPos finalTarget = target;
                    CompletableFuture<PathResult> moveFuture = moveTo(finalTarget);

                    // 等待移动完成（最多等30秒）
                    long startTime = System.currentTimeMillis();
                    while (!moveFuture.isDone() && System.currentTimeMillis() - startTime < 30000) {
                        Thread.sleep(500);
                    }

                    if (moveFuture.isDone() && moveFuture.get().success) {
                        // 破坏方块（需要在服务器线程）
                        // 简化：直接破坏
                        boolean broken = world.breakBlock(finalTarget, true, entity);
                        if (broken) {
                            mined++;
                            lastPos = finalTarget;
                            LOGGER.info("[NavPathing] 已挖 {} 个 {}", mined, blockName);
                        }
                    }

                    Thread.sleep(500);
                }

                if (mined >= amount) {
                    future.complete(PathResult.successMined(lastPos, mined));
                } else {
                    future.complete(PathResult.failure("只挖到了 " + mined + " 个 " + blockName));
                }
            } catch (Exception e) {
                LOGGER.error("[NavPathing] 采集失败: {}", e.getMessage());
                future.complete(PathResult.failure("采集失败: " + e.getMessage()));
            } finally {
                isActive.set(false);
            }
        }, "mining-worker");
        miningThread.setDaemon(true);
        miningThread.start();

        return future;
    }

    /**
     * 搜索附近的指定方块
     */
    private BlockPos findNearbyBlock(String blockName, int radius) {
        if (entity == null || world == null) return null;

        BlockPos entityPos = entity.getBlockPos();

        // 简单的搜索：从近到远扫描
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;

                        BlockPos pos = entityPos.add(dx, dy, dz);
                        String blockId = world.getBlockState(pos).getBlock().getTranslationKey();

                        // 简单匹配
                        if (blockId.contains(blockName) || blockId.contains(blockName.replace("_ore", ""))) {
                            return pos;
                        }
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void stopAll() {
        isActive.set(false);
        following = false;
        followTarget = null;
        currentTarget.set(null);

        CompletableFuture<PathResult> future = currentFuture.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.complete(PathResult.cancelled());
        }

        if (navigation != null) {
            navigation.stop();
        }
    }

    @Override
    public void pause() {
        isPaused.set(true);
        if (navigation != null) {
            navigation.stop();
        }
    }

    @Override
    public void resume() {
        isPaused.set(false);
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
        if (navigation == null || currentTarget.get() == null) return 0.0f;

        Path path = navigation.getCurrentPath();
        if (path == null) return 0.0f;

        int length = path.getLength();
        if (length == 0) return 1.0f;

        int currentIndex = path.getCurrentNodeIndex();
        return (float) currentIndex / length;
    }

    @Override
    public void cleanup() {
        stopAll();
        entity = null;
        navigation = null;
        world = null;
        initialized = false;
    }

    /**
     * Tick更新（需要在实体tick中调用）
     */
    public void tick() {
        if (!initialized || !isActive.get() || isPaused.get()) return;

        // 检查移动任务是否完成
        CompletableFuture<PathResult> future = currentFuture.get();
        if (future != null && !future.isDone() && currentTarget.get() != null) {
            // 检查是否到达目标
            BlockPos target = currentTarget.get();
            double dist = entity.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);

            if (dist < 4.0) {
                // 到达目标
                LOGGER.info("[NavPathing] 到达目标: {}", target);
                future.complete(PathResult.success(target));
                isActive.set(false);
            }
        }

        // 跟随逻辑
        if (following && followTarget != null && followTarget.isAlive()) {
            double dist = entity.squaredDistanceTo(followTarget);
            if (dist > followDistance * followDistance) {
                // 距离太远，走过去
                navigation.startMovingTo(followTarget, 1.0);
            } else {
                // 距离够近，停下
                navigation.stop();
            }
        }
    }

    public boolean isFollowing() {
        return following;
    }

    public LivingEntity getFollowTarget() {
        return followTarget;
    }
}
