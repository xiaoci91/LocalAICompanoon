package com.localaicompanion.entity.ai;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.MainConfig;
import com.localaicompanion.config.PermissionConfig;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * AI同伴实体类
 *
 * 功能：
 * - 聊天对话
 * - 自动战斗（生存模式）
 * - 危险检测自动撤离（生存模式）
 * - 背包存储（生存模式，像箱子一样）
 * - 跟随主人
 * - 主动对话
 */
public class AICompanionEntity extends AnimalEntity implements NamedScreenHandlerFactory {

    // 背包大小（27格 = 3行）
    private static final int INVENTORY_SIZE = 27;

    // 物品栏
    private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);

    // 数据追踪器：NPC名称
    private static final TrackedData<String> COMPANION_NAME = DataTracker.registerData(
        AICompanionEntity.class, TrackedDataHandlerRegistry.STRING
    );

    // 数据追踪器：NPC状态（idle/following/fighting/retreating）
    private static final TrackedData<String> COMPANION_STATE = DataTracker.registerData(
        AICompanionEntity.class, TrackedDataHandlerRegistry.STRING
    );

    // 主人UUID
    private UUID ownerUuid;

    // 主人的名字（用于绑定玩家）
    private String ownerName;

    // 是否已驯服
    private boolean tamed = true;

    // NPC是否已初始化
    private boolean initialized = false;

    // 主动对话计时器
    private int nextChatDelay = 0;
    private int chatTimer = 0;

    // 游荡相关
    private int wanderTimer = 0;

    // 战斗相关
    private LivingEntity attackTarget = null;
    private int attackCooldown = 0;
    private boolean retreating = false;
    private int retreatTimer = 0;

    // 危险检测相关
    private int dangerCheckCooldown = 0;
    private boolean inDanger = false;
    private String dangerType = "";

    // 警告冷却（避免频繁发消息）
    private int warningCooldown = 0;

    public AICompanionEntity(EntityType<? extends AnimalEntity> entityType, World world) {
        super(entityType, world);
    }

    /**
     * 创建默认属性
     */
    public static DefaultAttributeContainer.Builder createCompanionAttributes() {
        return MobEntity.createMobAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3.0)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0)
            .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.5);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(COMPANION_NAME, "小艾");
        this.dataTracker.startTracking(COMPANION_STATE, "idle");
    }

    /**
     * 初始化AI同伴
     */
    public void initializeCompanion(PlayerEntity owner) {
        if (initialized) return;

        this.ownerUuid = owner.getUuid();
        this.ownerName = owner.getName().getString();

        // 设置名字
        this.setCustomName(Text.literal("小艾"));
        this.dataTracker.set(COMPANION_NAME, "小艾");

        // 设置状态
        this.dataTracker.set(COMPANION_STATE, "idle");

        initialized = true;

        // 设置主动对话初始延迟（15-30秒后开始第一次主动说话）
        nextChatDelay = 300 + this.random.nextInt(300);
        chatTimer = 0;

        // 设置出生点为玩家位置
        this.refreshPositionAndAngles(owner.getX(), owner.getY(), owner.getZ(), owner.getYaw(), owner.getPitch());
        LocalAICompanion.LOGGER.info("[AICompanion] AI同伴已召唤: 主人={}", ownerName);
    }

    @Override
    public void tick() {
        super.tick();
        if (!initialized || getWorld().isClient) return;

        // 检查是否离主人太远，需要传送
        checkTeleportToOwner();

        // 更新状态显示
        updateStateDisplay();

        // 获取配置
        MainConfig.RunMode mode = getRunMode();
        PermissionConfig permConfig = getPermissionConfig();

        // 生存模式才有战斗和危险检测
        if (mode == MainConfig.RunMode.SURVIVAL_TEAMMATE) {
            // 危险检测
            tickDangerDetection(permConfig);

            // 如果在撤退中，不战斗
            if (!retreating) {
                // 自动战斗
                tickAutoAttack(permConfig);
            } else {
                tickRetreat();
            }

            // 物品自动拾取
            if (permConfig.allowPickupItems) {
                if (this.age % 10 == 0) {
                    pickupNearbyItems();
                }
            }
        }

        // 主动对话计时器
        chatTimer++;
        if (chatTimer >= nextChatDelay && nextChatDelay > 0) {
            triggerProactiveChat();
            chatTimer = 0;
            // 随机设置下一次说话的间隔（60-120秒，即1200-2400 tick）
            nextChatDelay = 1200 + this.random.nextInt(1200);
        }

        // 游荡计时器（简单实现：偶尔随机走动）
        wanderTimer++;
        if (wanderTimer >= 100 && isIdle() && mode == MainConfig.RunMode.SURVIVAL_TEAMMATE) {
            tryWander();
            wanderTimer = 0;
        }

        // 冷却计时
        if (attackCooldown > 0) attackCooldown--;
        if (dangerCheckCooldown > 0) dangerCheckCooldown--;
        if (warningCooldown > 0) warningCooldown--;
    }

    /**
     * 获取运行模式
     */
    private MainConfig.RunMode getRunMode() {
        try {
            return LocalAICompanion.getInstance().getConfigManager().getMainConfig().getRunModeEnum();
        } catch (Exception e) {
            return MainConfig.RunMode.ROLEPLAY_CHAT;
        }
    }

    /**
     * 获取权限配置
     */
    private PermissionConfig getPermissionConfig() {
        try {
            return LocalAICompanion.getInstance().getConfigManager().getPermissionConfig();
        } catch (Exception e) {
            return new PermissionConfig();
        }
    }

    /**
     * 危险检测
     */
    private void tickDangerDetection(PermissionConfig permConfig) {
        if (dangerCheckCooldown > 0) return;
        dangerCheckCooldown = 20; // 每秒检查一次

        boolean hasDanger = false;
        String danger = "";

        // 岩浆检测
        if (permConfig.emergencyOnLava) {
            if (isNearLava()) {
                hasDanger = true;
                danger = "岩浆";
            }
        }

        // 火焰检测
        if (permConfig.emergencyOnFire) {
            if (isOnFire() || isNearFire()) {
                hasDanger = true;
                danger = "火焰";
            }
        }

        // 虚空检测
        if (permConfig.emergencyOnVoid) {
            if (getY() < -50) {
                hasDanger = true;
                danger = "虚空";
            }
        }

        if (hasDanger && !inDanger) {
            inDanger = true;
            dangerType = danger;
            retreating = true;
            retreatTimer = 100; // 撤退5秒

            // 发出警告
            if (warningCooldown <= 0) {
                sendWarningToOwner("小心！附近有" + danger + "，我先撤了！");
                warningCooldown = 200; // 10秒内不重复警告
            }

            LocalAICompanion.LOGGER.debug("[AICompanion] 检测到危险: {}, 开始撤退", danger);
        }

        if (!hasDanger && inDanger) {
            inDanger = false;
            dangerType = "";
            // 危险解除，继续跟随
            if (retreating && retreatTimer <= 0) {
                retreating = false;
            }
        }
    }

    /**
     * 撤退逻辑
     */
    private void tickRetreat() {
        if (retreatTimer > 0) {
            retreatTimer--;
        }

        // 找主人的方向，往主人那边跑
        PlayerEntity owner = getOwner();
        if (owner != null) {
            // 往主人方向跑
            double dist = squaredDistanceTo(owner);
            if (dist > 4.0) {
                getNavigation().startMovingTo(owner, 1.2);
            } else {
                getNavigation().stop();
            }
        }

        // 撤退时间到了，检查危险是否解除
        if (retreatTimer <= 0 && !inDanger) {
            retreating = false;
            attackTarget = null;
            LocalAICompanion.LOGGER.debug("[AICompanion] 撤退结束，恢复正常");
        }
    }

    /**
     * 检查是否在岩浆附近
     */
    private boolean isNearLava() {
        BlockPos pos = getBlockPos();
        int radius = 2;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos checkPos = pos.add(dx, dy, dz);
                    String blockId = getWorld().getBlockState(checkPos).getBlock().getTranslationKey();
                    if (blockId.contains("lava")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查是否在火附近
     */
    private boolean isNearFire() {
        BlockPos pos = getBlockPos();
        int radius = 2;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos checkPos = pos.add(dx, dy, dz);
                    String blockId = getWorld().getBlockState(checkPos).getBlock().getTranslationKey();
                    if (blockId.contains("fire") || blockId.contains("soul_fire")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 自动战斗
     */
    private void tickAutoAttack(PermissionConfig permConfig) {
        // 如果没有开启攻击怪物，直接返回
        if (!permConfig.allowAttackMobs && !permConfig.allowAttackAnimals) return;

        // 如果有目标但目标死了，清空目标
        if (attackTarget != null && !attackTarget.isAlive()) {
            attackTarget = null;
            updateState("idle");
        }

        // 如果没有目标，找最近的目标
        if (attackTarget == null) {
            attackTarget = findNearestTarget(permConfig);
            if (attackTarget != null) {
                LocalAICompanion.LOGGER.debug("[AICompanion] 发现目标: {}", attackTarget.getName().getString());
                updateState("fighting");
            }
        }

        // 有目标，攻击
        if (attackTarget != null && attackTarget.isAlive()) {
            double dist = squaredDistanceTo(attackTarget);

            if (dist < 2.5) {
                // 距离够近，攻击
                if (attackCooldown <= 0) {
                    boolean attacked = tryAttack(attackTarget);
                    if (attacked) {
                        attackCooldown = 25; // 25 tick 攻击冷却
                    }
                }
            } else {
                // 距离太远，走过去
                getNavigation().startMovingTo(attackTarget, 1.0);
            }
        }
    }

    /**
     * 找最近的攻击目标
     */
    private LivingEntity findNearestTarget(PermissionConfig permConfig) {
        double range = 16.0;
        List<LivingEntity> nearby = getWorld().getEntitiesByClass(
            LivingEntity.class,
            getBoundingBox().expand(range),
            e -> true
        );

        LivingEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (LivingEntity entity : nearby) {
            // 不打主人
            if (entity.getUuid().equals(ownerUuid)) continue;

            // 不打其他玩家
            if (entity instanceof PlayerEntity) continue;

            // 判断是不是怪物
            boolean isMob = entity instanceof HostileEntity;
            boolean isAnimal = entity instanceof AnimalEntity;

            // 检查权限
            if (isMob && !permConfig.allowAttackMobs) continue;
            if (isAnimal && !permConfig.allowAttackAnimals) continue;
            if (!isMob && !isAnimal) continue;

            double dist = squaredDistanceTo(entity);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = entity;
            }
        }

        return nearest;
    }

    /**
     * 拾取附近的掉落物
     */
    private void pickupNearbyItems() {
        if (getWorld().isClient) return;

        // 搜索半径1.5格内的物品
        Box searchBox = this.getBoundingBox().expand(1.5);
        List<ItemEntity> items = getWorld().getEntitiesByClass(ItemEntity.class, searchBox, e -> true);

        for (ItemEntity itemEntity : items) {
            if (!itemEntity.isAlive()) continue;

            ItemStack stack = itemEntity.getStack();
            if (stack.isEmpty()) continue;

            // 尝试放进背包
            ItemStack remaining = inventory.addStack(stack);

            if (remaining.isEmpty()) {
                // 全部放进去了，销毁物品实体
                itemEntity.discard();
            } else {
                // 没放完，更新物品实体的数量
                itemEntity.setStack(remaining);
            }
        }
    }

    /**
     * 检查是否处于空闲状态
     */
    private boolean isIdle() {
        String state = dataTracker.get(COMPANION_STATE);
        return "idle".equals(state);
    }

    /**
     * 更新状态
     */
    private void updateState(String state) {
        dataTracker.set(COMPANION_STATE, state);
    }

    /**
     * 检查是否离主人太远，需要传送
     */
    private void checkTeleportToOwner() {
        PlayerEntity owner = getOwner();
        if (owner == null) return;

        double distance = this.squaredDistanceTo(owner);
        if (distance > 256) { // 16格以上
            // 传送到主人身边
            this.teleport(owner.getX(), owner.getY(), owner.getZ());
            LocalAICompanion.LOGGER.debug("[AICompanion] 传送回主人身边");
        }
    }

    /**
     * 获取主人玩家
     */
    @Nullable
    public PlayerEntity getOwner() {
        if (ownerUuid == null) return null;
        return getWorld().getPlayerByUuid(ownerUuid);
    }

    /**
     * 更新状态显示
     */
    private void updateStateDisplay() {
        // 状态已经存在dataTracker里了，客户端可以读取
    }

    /**
     * 尝试游荡
     */
    private void tryWander() {
        if (getNavigation().isFollowingPath()) return;

        // 随机找一个附近的位置走过去
        double x = getX() + (random.nextDouble() - 0.5) * 10;
        double z = getZ() + (random.nextDouble() - 0.5) * 10;
        double y = getY();

        getNavigation().startMovingTo(x, y, z, 0.3);
    }

    /**
     * 触发主动对话
     */
    private void triggerProactiveChat() {
        PlayerEntity owner = getOwner();
        if (owner == null) return;

        // 只有生存/冒险模式才主动说话
        if (owner.isCreative()) return;

        // 有任务在忙的时候不主动说话
        if (!isIdle()) return;

        // 调用LLM生成主动对话
        // 简化处理：直接用预设句子
        String[] fallbackMessages = {
            "嘿，你在干嘛呢？",
            "今天天气真不错啊。",
            "小心点，周围可能有怪物。",
            "我们接下来要做什么？",
            "我有点无聊了，找点事做吧。",
            "你饿不饿？要不要找点吃的？",
            "天快黑了，我们找个地方过夜吧。",
            "这里的风景还挺好的。",
            "需要我帮忙吗？",
            "我们去探索一下吧？"
        };

        String message = fallbackMessages[random.nextInt(fallbackMessages.length)];
        sendChatMessage(owner, message);
    }

    /**
     * 给主人发警告消息
     */
    private void sendWarningToOwner(String message) {
        PlayerEntity owner = getOwner();
        if (owner instanceof ServerPlayerEntity) {
            Text text = Text.literal("§c[小艾] §r" + message);
            owner.sendMessage(text, false);
        }
    }

    /**
     * 发送聊天消息给玩家
     */
    private void sendChatMessage(PlayerEntity player, String message) {
        if (player instanceof ServerPlayerEntity) {
            Text text = Text.literal("§b[小艾] §r" + message);
            player.sendMessage(text, false);
        }
    }

    // ========== 背包相关 ==========

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        // 只有主人才能打开背包
        if (!player.getUuid().equals(ownerUuid)) {
            player.sendMessage(Text.literal("这不是你的AI同伴~"), true);
            return ActionResult.FAIL;
        }

        // 只有生存模式才能打开背包
        MainConfig.RunMode mode = getRunMode();
        if (mode != MainConfig.RunMode.SURVIVAL_TEAMMATE) {
            player.sendMessage(Text.literal("聊天模式下不能打开背包哦~"), true);
            return ActionResult.FAIL;
        }

        // 打开背包
        if (!getWorld().isClient) {
            player.openHandledScreen(this);
        }

        return ActionResult.SUCCESS;
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("小艾的背包");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, inventory);
    }

    // ========== 保存和加载 ==========

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);

        // 保存主人信息
        if (ownerUuid != null) {
            nbt.putUuid("Owner", ownerUuid);
        }
        if (ownerName != null) {
            nbt.putString("OwnerName", ownerName);
        }

        nbt.putBoolean("Tamed", tamed);
        nbt.putBoolean("Initialized", initialized);

        // 保存背包
        Inventories.writeNbt(nbt, inventory.stacks);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);

        // 读取主人信息
        if (nbt.containsUuid("Owner")) {
            ownerUuid = nbt.getUuid("Owner");
        }
        if (nbt.contains("OwnerName")) {
            ownerName = nbt.getString("OwnerName");
        }

        tamed = nbt.getBoolean("Tamed");
        initialized = nbt.getBoolean("Initialized");

        // 读取背包
        Inventories.readNbt(nbt, inventory.stacks);
    }

    // ========== 其他 ==========

    public boolean isTamed() {
        return tamed;
    }

    /**
     * 检查是否是主人
     */
    public boolean isOwner(PlayerEntity player) {
        if (ownerUuid == null) return false;
        return ownerUuid.equals(player.getUuid());
    }

    public String getCompanionName() {
        return dataTracker.get(COMPANION_NAME);
    }

    public String getCompanionState() {
        return dataTracker.get(COMPANION_STATE);
    }

    public SimpleInventory getInventory() {
        return inventory;
    }

    @Nullable
    @Override
    public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
        return null; // 不繁殖
    }

    /**
     * 死亡时掉落背包物品
     * 由事件监听器调用
     */
    public void dropInventoryItems() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                dropStack(stack);
            }
        }
    }
}
