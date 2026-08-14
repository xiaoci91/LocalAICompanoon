package com.localaicompanion.task.pathing;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
 * 所有操作都在服务器线程中通过tick()驱动
 *
 * 功能：
 * - 移动到指定位置
 * - 跟随目标实体
 * - 采集方块（挖矿、砍树）
 * - 攻击实体（打怪）
 */
public class EntityNavigationPathingService implements PathingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-NavPathing");

    private MobEntity entity;
    private EntityNavigation navigation;
    private World world;
    private boolean initialized = false;

    // 活动状态
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicReference<BlockPos> currentTarget = new AtomicReference<>(null);

    // 当前任务的future
    private final AtomicReference<CompletableFuture<PathResult>> currentFuture = new AtomicReference<>(null);

    // 任务类型
    private enum TaskType {
        NONE, MOVE_TO, MINE_BLOCK, ATTACK_ENTITY, FOLLOW
    }

    private TaskType currentTaskType = TaskType.NONE;

    // 跟随相关
    private LivingEntity followTarget;
    private float followDistance = 3.0f;
    private boolean following = false;

    // 采集相关
    private String mineBlockName;
    private int mineAmount;
    private int mineRadius;
    private int minedCount = 0;
    private BlockPos currentMineTarget;
    private int mineCooldown = 0;

    // 攻击相关
    private LivingEntity attackTarget;
    private int attackCooldown = 0;

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

        stopAll();

        currentTarget.set(targetPos);
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);
        currentTaskType = TaskType.MOVE_TO;

        try {
            boolean started = navigation.startMovingTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
            if (!started) {
                future.complete(PathResult.failure("无法找到路径"));
                isActive.set(false);
                currentTaskType = TaskType.NONE;
                return future;
            }

            LOGGER.info("[NavPathing] 开始移动到: {}", targetPos);
        } catch (Exception e) {
            LOGGER.error("[NavPathing] moveTo失败: {}", e.getMessage());
            future.complete(PathResult.failure("寻路失败: " + e.getMessage()));
            isActive.set(false);
            currentTaskType = TaskType.NONE;
        }

        return future;
    }

    @Override
    public CompletableFuture<PathResult> moveToAndBreak(BlockPos targetPos) {
        if (!initialized || entity == null || world == null) {
            return CompletableFuture.completedFuture(PathResult.failure("寻路服务未初始化"));
        }

        stopAll();

        currentTarget.set(targetPos);
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);
        currentTaskType = TaskType.MINE_BLOCK;
        currentMineTarget = targetPos;
        mineBlockName = "";
        mineAmount = 1;
        minedCount = 0;

        // 先移动到目标旁边
        navigation.startMovingTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);

        LOGGER.info("[NavPathing] 开始移动并破坏方块: {}", targetPos);

        return future;
    }

    @Override
    public CompletableFuture<PathResult> moveToAndPlace(BlockPos targetPos, String blockName) {
        return CompletableFuture.completedFuture(PathResult.failure("放置方块功能开发中"));
    }

    @Override
    public void followEntity(LivingEntity target, float followDistance) {
        if (!initialized || entity == null || navigation == null) return;

        stopAll();

        this.followTarget = target;
        this.followDistance = followDistance;
        this.following = true;
        isActive.set(true);
        currentTaskType = TaskType.FOLLOW;

        LOGGER.info("[NavPathing] 开始跟随: {}", target.getName().getString());
    }

    @Override
    public CompletableFuture<PathResult> attackEntity(LivingEntity target) {
        if (!initialized || entity == null || world == null) {
            return CompletableFuture.completedFuture(PathResult.failure("寻路服务未初始化"));
        }

        stopAll();

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);
        currentTaskType = TaskType.ATTACK_ENTITY;
        attackTarget = target;
        attackCooldown = 0;

        LOGGER.info("[NavPathing] 开始攻击: {}", target.getName().getString());

        return future;
    }

    @Override
    public CompletableFuture<PathResult> mineBlock(String blockName, int amount, int searchRadius) {
        if (!initialized || entity == null || world == null) {
            return CompletableFuture.completedFuture(PathResult.failure("寻路服务未初始化"));
        }

        stopAll();

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        currentFuture.set(future);
        isActive.set(true);
        currentTaskType = TaskType.MINE_BLOCK;
        mineBlockName = blockName;
        mineAmount = amount;
        mineRadius = searchRadius;
        minedCount = 0;
        currentMineTarget = null;
        mineCooldown = 0;

        LOGGER.info("[NavPathing] 开始采集: {} x{}, 半径{}", blockName, amount, searchRadius);

        return future;
    }

    /**
     * 搜索附近的指定方块
     */
    private BlockPos findNearbyBlock(String blockName, int radius) {
        if (entity == null || world == null) return null;

        BlockPos entityPos = entity.getBlockPos();

        // 从近到远搜索
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;

                        BlockPos pos = entityPos.add(dx, dy, dz);

                        // 检查Y坐标是否合理
                        if (pos.getY() < -64 || pos.getY() > 320) continue;

                        String blockId = world.getBlockState(pos).getBlock().getTranslationKey();

                        // 简单匹配：包含方块名
                        if (blockId.contains(blockName) ||
                            blockId.contains(blockName.replace("_ore", "")) ||
                            blockId.contains(blockName.replace("_log", ""))) {
                            // 确保不是空气
                            if (!world.isAir(pos)) {
                                return pos;
                            }
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
        currentTaskType = TaskType.NONE;
        attackTarget = null;
        currentMineTarget = null;
        mineCooldown = 0;
        attackCooldown = 0;

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
     * Tick更新（必须在服务器线程中调用）
     */
    public void tick() {
        if (!initialized || !isActive.get() || isPaused.get()) return;

        try {
            switch (currentTaskType) {
                case MOVE_TO:
                    tickMoveTo();
                    break;
                case MINE_BLOCK:
                    tickMineBlock();
                    break;
                case ATTACK_ENTITY:
                    tickAttackEntity();
                    break;
                case FOLLOW:
                    tickFollow();
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            LOGGER.error("[NavPathing] tick异常: {}", e.getMessage());
            completeWithError("执行异常: " + e.getMessage());
        }
    }

    /**
     * 移动任务tick
     */
    private void tickMoveTo() {
        BlockPos target = currentTarget.get();
        if (target == null) return;

        // 检查是否到达目标
        double dist = entity.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (dist < 4.0) {
            LOGGER.info("[NavPathing] 到达目标: {}", target);
            completeSuccess(target);
        }

        // 检查导航是否结束
        if (navigation.isIdle()) {
            // 导航结束，检查是否到达
            if (dist < 4.0) {
                completeSuccess(target);
            } else {
                completeWithError("无法到达目标");
            }
        }
    }

    /**
     * 采集任务tick
     */
    private void tickMineBlock() {
        CompletableFuture<PathResult> future = currentFuture.get();
        if (future == null || future.isDone()) return;

        // 冷却中
        if (mineCooldown > 0) {
            mineCooldown--;
            return;
        }

        // 如果没有当前目标，搜索新目标
        if (currentMineTarget == null) {
            if (minedCount >= mineAmount) {
                // 挖够了
                LOGGER.info("[NavPathing] 采集完成: {} x{}", mineBlockName, minedCount);
                completeSuccessMined(entity.getBlockPos(), minedCount);
                return;
            }

            // 搜索新目标
            BlockPos target = findNearbyBlock(mineBlockName, mineRadius);
            if (target == null) {
                if (minedCount > 0) {
                    // 挖了一些，但是找不到更多了
                    completeSuccessMined(entity.getBlockPos(), minedCount);
                } else {
                    completeWithError("附近找不到" + mineBlockName);
                }
                return;
            }

            currentMineTarget = target;
            currentTarget.set(target);
            navigation.startMovingTo(target.getX(), target.getY(), target.getZ(), 1.0);
            LOGGER.info("[NavPathing] 找到目标: {}, 进度: {}/{}", target, minedCount, mineAmount);
            return;
        }

        // 有目标，检查是否到达
        double dist = entity.squaredDistanceTo(
            currentMineTarget.getX() + 0.5,
            currentMineTarget.getY(),
            currentMineTarget.getZ() + 0.5
        );

        if (dist < 4.0) {
            // 到达目标，破坏方块
            boolean broken = world.breakBlock(currentMineTarget, true, entity);
            if (broken) {
                minedCount++;
                mineCooldown = 20; // 20 tick 冷却
                LOGGER.info("[NavPathing] 已挖 {} 个 {}", minedCount, mineBlockName);
            } else {
                mineCooldown = 10;
            }
            currentMineTarget = null;
            currentTarget.set(null);
        } else if (navigation.isIdle()) {
            // 导航结束但没到达，重新找目标
            currentMineTarget = null;
            currentTarget.set(null);
            mineCooldown = 10;
        }
    }

    /**
     * 攻击任务tick
     */
    private void tickAttackEntity() {
        CompletableFuture<PathResult> future = currentFuture.get();
        if (future == null || future.isDone()) return;

        if (attackTarget == null || !attackTarget.isAlive()) {
            // 目标死亡或消失
            if (attackTarget != null && !attackTarget.isAlive()) {
                LOGGER.info("[NavPathing] 目标已击杀: {}", attackTarget.getName().getString());
                completeSuccess(attackTarget.getBlockPos());
            } else {
                completeWithError("目标消失了");
            }
            return;
        }

        // 冷却中
        if (attackCooldown > 0) {
            attackCooldown--;
            // 冷却时也跟着目标
            if (entity.squaredDistanceTo(attackTarget) > 2.0) {
                navigation.startMovingTo(attackTarget, 1.0);
            }
            return;
        }

        double dist = entity.squaredDistanceTo(attackTarget);

        if (dist < 2.5) {
            // 距离够近，攻击
            boolean attacked = entity.tryAttack(attackTarget);
            if (attacked) {
                attackCooldown = 20; // 20 tick 攻击冷却
                LOGGER.debug("[NavPathing] 攻击目标: {}", attackTarget.getName().getString());
            } else {
                attackCooldown = 10;
            }
        } else {
            // 距离太远，走过去
            navigation.startMovingTo(attackTarget, 1.0);
        }
    }

    /**
     * 跟随任务tick
     */
    private void tickFollow() {
        if (followTarget == null || !followTarget.isAlive()) {
            following = false;
            followTarget = null;
            isActive.set(false);
            currentTaskType = TaskType.NONE;
            return;
        }

        double dist = entity.squaredDistanceTo(followTarget);
        if (dist > followDistance * followDistance) {
            // 距离太远，走过去
            navigation.startMovingTo(followTarget, 1.0);
        } else {
            // 距离够近，停下
            navigation.stop();
        }
    }

    /**
     * 完成任务（成功）
     */
    private void completeSuccess(BlockPos finalPos) {
        CompletableFuture<PathResult> future = currentFuture.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.complete(PathResult.success(finalPos));
        }
        isActive.set(false);
        currentTaskType = TaskType.NONE;
        currentTarget.set(null);
    }

    /**
     * 完成任务（采集成功）
     */
    private void completeSuccessMined(BlockPos finalPos, int count) {
        CompletableFuture<PathResult> future = currentFuture.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.complete(PathResult.successMined(finalPos, count));
        }
        isActive.set(false);
        currentTaskType = TaskType.NONE;
        currentTarget.set(null);
        currentMineTarget = null;
    }

    /**
     * 完成任务（失败）
     */
    private void completeWithError(String message) {
        CompletableFuture<PathResult> future = currentFuture.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.complete(PathResult.failure(message));
        }
        isActive.set(false);
        currentTaskType = TaskType.NONE;
        currentTarget.set(null);
        currentMineTarget = null;
        attackTarget = null;
    }

    public boolean isFollowing() {
        return following;
    }

    public LivingEntity getFollowTarget() {
        return followTarget;
    }
}
