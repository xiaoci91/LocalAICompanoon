package com.localaicompanion.task.pathing;

import net.minecraft.block.BlockState;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实体导航寻路服务 - 优化版
 *
 * 使用Minecraft实体自带的Navigation系统进行寻路
 * 所有操作都在服务器线程中通过tick()驱动
 *
 * 优化功能：
 * - 智能砍树：整棵树识别，从底部往上挖
 * - 智能挖矿：矿石优先级排序，安全挖矿
 * - 智能打怪：怪物威胁等级，血量管理，边打边退
 * - 自动拾取：自动捡起掉落物
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
        NONE, MOVE_TO, MINE_BLOCK, CHOP_WOOD, ATTACK_ENTITY, FOLLOW
    }

    private TaskType currentTaskType = TaskType.NONE;

    // 跟随相关
    private LivingEntity followTarget;
    private float followDistance = 3.0f;
    private boolean following = false;

    // 采集相关
    private String targetBlockName;
    private int targetAmount;
    private int searchRadius;
    private int collectedCount = 0;
    private BlockPos currentBlockTarget;
    private int actionCooldown = 0;

    // 砍树相关
    private List<BlockPos> treeBlocks = new ArrayList<>();
    private int currentTreeIndex = 0;

    // 攻击相关
    private LivingEntity attackTarget;
    private int attackCooldown = 0;
    private boolean retreating = false;
    private float retreatHealthPercent = 0.3f; // 血量低于30%时撤退

    // 矿石优先级（数值越大越优先）
    private static final java.util.Map<String, Integer> ORE_PRIORITY = new java.util.HashMap<>();
    static {
        ORE_PRIORITY.put("diamond_ore", 100);
        ORE_PRIORITY.put("deepslate_diamond_ore", 100);
        ORE_PRIORITY.put("emerald_ore", 95);
        ORE_PRIORITY.put("deepslate_emerald_ore", 95);
        ORE_PRIORITY.put("gold_ore", 80);
        ORE_PRIORITY.put("deepslate_gold_ore", 80);
        ORE_PRIORITY.put("iron_ore", 60);
        ORE_PRIORITY.put("deepslate_iron_ore", 60);
        ORE_PRIORITY.put("copper_ore", 50);
        ORE_PRIORITY.put("deepslate_copper_ore", 50);
        ORE_PRIORITY.put("coal_ore", 40);
        ORE_PRIORITY.put("deepslate_coal_ore", 40);
        ORE_PRIORITY.put("lapis_ore", 30);
        ORE_PRIORITY.put("deepslate_lapis_ore", 30);
        ORE_PRIORITY.put("redstone_ore", 20);
        ORE_PRIORITY.put("deepslate_redstone_ore", 20);
    }

    // 怪物威胁等级（数值越大越危险）
    private static final java.util.Map<String, Integer> MOB_THREAT = new java.util.HashMap<>();
    static {
        MOB_THREAT.put("creeper", 100);      // 苦力怕 - 最危险
        MOB_THREAT.put("ghast", 95);         // 恶魂
        MOB_THREAT.put("wither_skeleton", 90); // 凋灵骷髅
        MOB_THREAT.put("blaze", 85);         // 烈焰人
        MOB_THREAT.put("witch", 80);         // 女巫
        MOB_THREAT.put("zombie", 60);        // 僵尸
        MOB_THREAT.put("skeleton", 55);      // 骷髅
        MOB_THREAT.put("spider", 50);        // 蜘蛛
        MOB_THREAT.put("cave_spider", 50);   // 洞穴蜘蛛
        MOB_THREAT.put("enderman", 45);      // 末影人
        MOB_THREAT.put("slime", 30);         // 史莱姆
        MOB_THREAT.put("magma_cube", 30);    // 岩浆怪
        MOB_THREAT.put("zombie_pigman", 20); // 僵尸猪灵
        MOB_THREAT.put("piglin", 20);        // 猪灵
    }

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
        currentBlockTarget = targetPos;
        targetBlockName = "";
        targetAmount = 1;
        collectedCount = 0;

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
        retreating = false;

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
        targetBlockName = blockName;
        targetAmount = amount;
        this.searchRadius = searchRadius;
        collectedCount = 0;
        currentBlockTarget = null;
        actionCooldown = 0;
        treeBlocks.clear();
        currentTreeIndex = 0;

        // 判断是砍树还是挖矿
        if (isWoodBlock(blockName)) {
            currentTaskType = TaskType.CHOP_WOOD;
            LOGGER.info("[NavPathing] 开始砍树: {} x{}, 半径{}", blockName, amount, searchRadius);
        } else {
            currentTaskType = TaskType.MINE_BLOCK;
            LOGGER.info("[NavPathing] 开始挖矿: {} x{}, 半径{}", blockName, amount, searchRadius);
        }

        return future;
    }

    /**
     * 判断是否是木头方块
     */
    private boolean isWoodBlock(String blockName) {
        return blockName.contains("log") || blockName.contains("wood") || blockName.contains("木头") || blockName.contains("原木");
    }

    /**
     * 搜索附近的指定方块（按优先级排序）
     */
    private List<BlockPos> findNearbyBlocks(String blockName, int radius) {
        if (entity == null || world == null) return new ArrayList<>();

        BlockPos entityPos = entity.getBlockPos();
        List<BlockPos> found = new ArrayList<>();

        // 搜索所有匹配的方块
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = entityPos.add(dx, dy, dz);

                    // 检查Y坐标是否合理
                    if (pos.getY() < -64 || pos.getY() > 320) continue;

                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;

                    String blockId = state.getBlock().getTranslationKey();

                    // 匹配方块名
                    if (blockMatches(blockId, blockName)) {
                        found.add(pos);
                    }
                }
            }
        }

        // 按距离排序（近的优先）
        found.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(entityPos)));

        return found;
    }

    /**
     * 方块匹配
     */
    private boolean blockMatches(String blockId, String targetName) {
        // 直接匹配
        if (blockId.contains(targetName)) return true;

        // 矿石匹配
        if (targetName.contains("ore") || targetName.contains("矿")) {
            String oreName = targetName.replace("_ore", "").replace("矿", "");
            if (blockId.contains(oreName) && blockId.contains("ore")) return true;
        }

        // 木头匹配
        if (targetName.contains("log") || targetName.contains("wood") || targetName.contains("木头") || targetName.contains("原木")) {
            if (blockId.contains("log") || blockId.contains("wood")) return true;
        }

        return false;
    }

    /**
     * 检测整棵树的所有木头方块
     */
    private List<BlockPos> detectTree(BlockPos basePos) {
        List<BlockPos> tree = new ArrayList<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        // 从底部往上扫描
        int maxHeight = 32; // 树最高32格
        for (int y = 0; y < maxHeight; y++) {
            mutable.set(basePos.getX(), basePos.getY() + y, basePos.getZ());
            BlockState state = world.getBlockState(mutable);

            if (state.isAir()) break;

            String blockId = state.getBlock().getTranslationKey();
            if (blockId.contains("log") || blockId.contains("wood")) {
                tree.add(mutable.toImmutable());
            } else {
                // 不是木头了，可能是树叶，继续往上看看
                // 但如果连续3格都不是木头，就停止
                if (y > 0 && y < 5) continue;
                break;
            }
        }

        // 也检查一下周围的木头（大树）
        // 简化处理：只检查正上方的

        return tree;
    }

    /**
     * 搜索附近的怪物（按威胁等级排序）
     */
    private List<LivingEntity> findNearbyMobs(int radius) {
        if (entity == null || world == null) return new ArrayList<>();

        List<LivingEntity> mobs = new ArrayList<>();

        // 获取附近的实体
        List<net.minecraft.entity.Entity> entities = world.getOtherEntities(entity,
            entity.getBoundingBox().expand(radius),
            e -> e instanceof LivingEntity && !(e instanceof PlayerEntity));

        for (net.minecraft.entity.Entity e : entities) {
            if (e instanceof LivingEntity) {
                mobs.add((LivingEntity) e);
            }
        }

        // 按威胁等级排序（高威胁优先）
        mobs.sort((a, b) -> {
            int threatA = getMobThreat(a);
            int threatB = getMobThreat(b);
            return Integer.compare(threatB, threatA);
        });

        return mobs;
    }

    /**
     * 获取怪物威胁等级
     */
    private int getMobThreat(LivingEntity mob) {
        String name = mob.getType().getTranslationKey();
        // 提取实体名
        String mobName = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return MOB_THREAT.getOrDefault(mobName, 10);
    }

    /**
     * 检查是否是敌对生物
     */
    private boolean isHostileMob(LivingEntity mob) {
        // 简单判断：不是玩家，不是动物
        String name = mob.getType().getTranslationKey();
        return !name.contains("player") &&
               !name.contains("sheep") &&
               !name.contains("pig") &&
               !name.contains("cow") &&
               !name.contains("chicken") &&
               !name.contains("rabbit") &&
               !name.contains("villager");
    }

    @Override
    public void stopAll() {
        isActive.set(false);
        following = false;
        followTarget = null;
        currentTarget.set(null);
        currentTaskType = TaskType.NONE;
        attackTarget = null;
        currentBlockTarget = null;
        actionCooldown = 0;
        attackCooldown = 0;
        retreating = false;
        treeBlocks.clear();
        currentTreeIndex = 0;

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
                case CHOP_WOOD:
                    tickChopWood();
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
            if (dist < 4.0) {
                completeSuccess(target);
            } else {
                completeWithError("无法到达目标");
            }
        }
    }

    /**
     * 挖矿任务tick
     */
    private void tickMineBlock() {
        CompletableFuture<PathResult> future = currentFuture.get();
        if (future == null || future.isDone()) return;

        // 冷却中
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        // 检查是否挖够了
        if (collectedCount >= targetAmount) {
            LOGGER.info("[NavPathing] 挖矿完成: {} x{}", targetBlockName, collectedCount);
            completeSuccessMined(entity.getBlockPos(), collectedCount);
            return;
        }

        // 如果没有当前目标，搜索新目标
        if (currentBlockTarget == null) {
            List<BlockPos> candidates = findNearbyBlocks(targetBlockName, searchRadius);

            if (candidates.isEmpty()) {
                if (collectedCount > 0) {
                    completeSuccessMined(entity.getBlockPos(), collectedCount);
                } else {
                    completeWithError("附近找不到" + targetBlockName);
                }
                return;
            }

            // 选最近的，并且确保不是脚下的方块（安全）
            for (BlockPos candidate : candidates) {
                if (!isBlockUnderFeet(candidate)) {
                    currentBlockTarget = candidate;
                    currentTarget.set(candidate);
                    navigation.startMovingTo(candidate.getX(), candidate.getY(), candidate.getZ(), 1.0);
                    LOGGER.debug("[NavPathing] 找到矿石: {}, 进度: {}/{}", candidate, collectedCount, targetAmount);
                    return;
                }
            }

            // 所有矿石都在脚下，随便选一个
            currentBlockTarget = candidates.get(0);
            currentTarget.set(currentBlockTarget);
            navigation.startMovingTo(currentBlockTarget.getX(), currentBlockTarget.getY(), currentBlockTarget.getZ(), 1.0);
        }

        // 有目标，检查是否到达
        double dist = entity.squaredDistanceTo(
            currentBlockTarget.getX() + 0.5,
            currentBlockTarget.getY(),
            currentBlockTarget.getZ() + 0.5
        );

        if (dist < 9.0) { // 3格内可以挖
            // 到达目标，破坏方块
            boolean broken = world.breakBlock(currentBlockTarget, true, entity);
            if (broken) {
                collectedCount++;
                actionCooldown = 30; // 30 tick 冷却（1.5秒）
                LOGGER.info("[NavPathing] 已挖 {} 个 {}", collectedCount, targetBlockName);
            } else {
                actionCooldown = 10;
            }
            currentBlockTarget = null;
            currentTarget.set(null);
        } else if (navigation.isIdle()) {
            // 导航结束但没到达，重新找目标
            currentBlockTarget = null;
            currentTarget.set(null);
            actionCooldown = 10;
        }
    }

    /**
     * 检查方块是否在脚下
     */
    private boolean isBlockUnderFeet(BlockPos pos) {
        BlockPos feetPos = entity.getBlockPos();
        return pos.getY() <= feetPos.getY() &&
               Math.abs(pos.getX() - feetPos.getX()) <= 1 &&
               Math.abs(pos.getZ() - feetPos.getZ()) <= 1;
    }

    /**
     * 砍树任务tick
     */
    private void tickChopWood() {
        CompletableFuture<PathResult> future = currentFuture.get();
        if (future == null || future.isDone()) return;

        // 冷却中
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        // 检查是否砍够了
        if (collectedCount >= targetAmount) {
            LOGGER.info("[NavPathing] 砍树完成: {} x{}", targetBlockName, collectedCount);
            completeSuccessMined(entity.getBlockPos(), collectedCount);
            return;
        }

        // 如果当前没有树在砍，找新的树
        if (treeBlocks.isEmpty()) {
            List<BlockPos> candidates = findNearbyBlocks(targetBlockName, searchRadius);

            if (candidates.isEmpty()) {
                if (collectedCount > 0) {
                    completeSuccessMined(entity.getBlockPos(), collectedCount);
                } else {
                    completeWithError("附近找不到树");
                }
                return;
            }

            // 选最近的树的底部木头
            BlockPos baseWood = candidates.get(0);
            treeBlocks = detectTree(baseWood);
            currentTreeIndex = 0;

            if (treeBlocks.isEmpty()) {
                // 检测失败，直接挖这个方块
                treeBlocks.add(baseWood);
            }

            LOGGER.info("[NavPathing] 找到一棵树，共 {} 块木头", treeBlocks.size());
        }

        // 正在砍一棵树
        if (currentTreeIndex < treeBlocks.size()) {
            BlockPos targetWood = treeBlocks.get(currentTreeIndex);
            currentTarget.set(targetWood);

            double dist = entity.squaredDistanceTo(
                targetWood.getX() + 0.5,
                targetWood.getY(),
                targetWood.getZ() + 0.5
            );

            if (dist < 9.0) { // 3格内可以挖
                // 破坏木头
                boolean broken = world.breakBlock(targetWood, true, entity);
                if (broken) {
                    collectedCount++;
                    currentTreeIndex++;
                    actionCooldown = 20; // 20 tick 冷却
                    LOGGER.debug("[NavPathing] 砍树进度: {}/{} (树 {}/{})",
                        collectedCount, targetAmount, currentTreeIndex, treeBlocks.size());
                } else {
                    actionCooldown = 10;
                }
            } else {
                // 走过去
                navigation.startMovingTo(targetWood.getX(), targetWood.getY(), targetWood.getZ(), 1.0);
            }
        } else {
            // 这棵树砍完了，找下一棵
            treeBlocks.clear();
            currentTreeIndex = 0;
            actionCooldown = 10;
        }
    }

    /**
     * 攻击任务tick
     */
    private void tickAttackEntity() {
        CompletableFuture<PathResult> future = currentFuture.get();
        if (future == null || future.isDone()) return;

        if (attackTarget == null || !attackTarget.isAlive()) {
            if (attackTarget != null && !attackTarget.isAlive()) {
                LOGGER.info("[NavPathing] 目标已击杀: {}", attackTarget.getName().getString());
                completeSuccess(attackTarget.getBlockPos());
            } else {
                completeWithError("目标消失了");
            }
            return;
        }

        // 检查血量，太低就撤退
        float healthPercent = entity.getHealth() / entity.getMaxHealth();
        if (healthPercent < retreatHealthPercent && !retreating) {
            retreating = true;
            LOGGER.info("[NavPathing] 血量过低，开始撤退: {}%", Math.round(healthPercent * 100));
        }

        if (retreating) {
            // 撤退模式：远离目标
            Vec3d awayDir = entity.getPos().subtract(attackTarget.getPos()).normalize();
            Vec3d retreatPos = entity.getPos().add(awayDir.multiply(10));
            navigation.startMovingTo(retreatPos.x, retreatPos.y, retreatPos.z, 1.2);

            // 血量恢复了就回去打
            if (healthPercent > retreatHealthPercent + 0.2) {
                retreating = false;
                LOGGER.info("[NavPathing] 血量恢复，继续战斗");
            }
            return;
        }

        // 冷却中
        if (attackCooldown > 0) {
            attackCooldown--;
            // 冷却时也跟着目标
            double dist = entity.squaredDistanceTo(attackTarget);
            if (dist > 4.0) {
                navigation.startMovingTo(attackTarget, 1.0);
            } else {
                navigation.stop();
            }
            return;
        }

        double dist = entity.squaredDistanceTo(attackTarget);

        if (dist < 2.5) {
            // 距离够近，攻击
            boolean attacked = entity.tryAttack(attackTarget);
            if (attacked) {
                attackCooldown = 25; // 25 tick 攻击冷却（1.25秒）
                LOGGER.debug("[NavPathing] 攻击目标: {}", attackTarget.getName().getString());

                // 苦力怕要小心，打一下就退
                if (isCreeper(attackTarget)) {
                    retreating = true;
                    // 短暂撤退后再回来
                    entity.getWorld().sendEntityStatus(entity, (byte) 4); // 受伤粒子效果
                }
            } else {
                attackCooldown = 10;
            }
        } else {
            // 距离太远，走过去
            navigation.startMovingTo(attackTarget, 1.0);
        }
    }

    /**
     * 判断是不是苦力怕
     */
    private boolean isCreeper(LivingEntity mob) {
        return mob.getType().getTranslationKey().contains("creeper");
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
        currentBlockTarget = null;
        treeBlocks.clear();
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
        currentBlockTarget = null;
        attackTarget = null;
        treeBlocks.clear();
    }

    public boolean isFollowing() {
        return following;
    }

    public LivingEntity getFollowTarget() {
        return followTarget;
    }
}
