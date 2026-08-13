package com.localaicompanion.entity.ai;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.task.TaskScheduler;
import com.localaicompanion.task.pathing.BaritonePathingAdapter;
import com.localaicompanion.task.pathing.PathingService;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * AI同伴实体类
 * 第1层：游戏实体表现层的核心
 *
 * 继承AnimalEntity，自己实现驯服逻辑
 * 避免TameableEntity的抽象方法问题
 */
public class AICompanionEntity extends AnimalEntity {

    // 数据追踪器：NPC名称
    private static final TrackedData<String> COMPANION_NAME = DataTracker.registerData(
        AICompanionEntity.class, TrackedDataHandlerRegistry.STRING
    );

    // 数据追踪器：NPC状态（idle/following/working/fighting）
    private static final TrackedData<String> COMPANION_STATE = DataTracker.registerData(
        AICompanionEntity.class, TrackedDataHandlerRegistry.STRING
    );

    // 数据追踪器：当前任务描述
    private static final TrackedData<String> CURRENT_TASK = DataTracker.registerData(
        AICompanionEntity.class, TrackedDataHandlerRegistry.STRING
    );

    // 寻路服务
    private PathingService pathingService;

    // 任务调度器引用
    private TaskScheduler taskScheduler;

    // 主人UUID
    private UUID ownerUuid;

    // 主人的名字（用于绑定玩家）
    private String ownerName;

    // 是否已驯服
    private boolean tamed = true;

    // NPC是否已初始化
    private boolean initialized = false;

    public AICompanionEntity(EntityType<? extends AnimalEntity> entityType, World world) {
        super(entityType, world);
    }

    /**
     * 创建默认属性
     */
    public static DefaultAttributeContainer.Builder createCompanionAttributes() {
        return LivingEntity.createLivingAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3D)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0D)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0D)
            .add(EntityAttributes.GENERIC_ARMOR, 2.0D)
            .add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.5D);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(COMPANION_NAME, "小艾");
        this.dataTracker.startTracking(COMPANION_STATE, "idle");
        this.dataTracker.startTracking(CURRENT_TASK, "");
    }

    /**
     * 初始化AI同伴（召唤时调用）
     */
    public void initializeCompanion(PlayerEntity owner) {
        if (initialized) return;

        this.ownerUuid = owner.getUuid();
        this.ownerName = owner.getName().getString();
        this.setCustomName(Text.literal(getCompanionName()));
        this.setCustomNameVisible(true);

        // 初始化寻路服务
        this.pathingService = new BaritonePathingAdapter();
        this.pathingService.initialize(this);

        // 初始化任务调度器
        this.taskScheduler = LocalAICompanion.getInstance().getTaskScheduler();
        this.taskScheduler.initialize(this, pathingService,
            LocalAICompanion.getInstance().getConfigManager().getMainConfig());

        initialized = true;

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
    }

    /**
     * 检查是否需要传送到主人身边
     */
    private void checkTeleportToOwner() {
        PlayerEntity owner = getOwnerPlayer();
        if (owner == null) return;

        double distance = this.squaredDistanceTo(owner);
        double teleportDist = LocalAICompanion.getInstance().getConfigManager()
            .getMainConfig().teleportDistance;

        if (distance > teleportDist * teleportDist) {
            this.teleport(owner.getX(), owner.getY(), owner.getZ());
            LocalAICompanion.LOGGER.debug("[AICompanion] 距离过远，传送到主人身边");
        }
    }

    /**
     * 获取主人玩家
     */
    @Nullable
    public PlayerEntity getOwnerPlayer() {
        if (ownerUuid == null) return null;
        if (getWorld().isClient) return null;
        var server = getServer();
        if (server == null) return null;
        return server.getPlayerManager().getPlayer(ownerUuid);
    }

    /**
     * 更新状态显示
     */
    private void updateStateDisplay() {
        if (taskScheduler == null) return;

        var currentTask = taskScheduler.getCurrentTask();
        if (currentTask != null && currentTask.getState().isActive()) {
            setCompanionState("working");
            setCurrentTask(currentTask.getStandardTask().getIntentType().getDisplayName());
        } else if (pathingService != null && pathingService.isActive()) {
            setCompanionState("moving");
        } else {
            setCompanionState("idle");
            setCurrentTask("");
        }
    }

    /**
     * 玩家右键交互
     */
    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (player.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }

        // 检查是否是主人
        if (!isOwner(player)) {
            player.sendMessage(Text.literal("这不是你的AI同伴~"), true);
            return ActionResult.FAIL;
        }

        ItemStack stack = player.getStackInHand(hand);

        // 如果拿着命名牌，改名字
        if (stack.hasCustomName()) {
            setCompanionName(stack.getName().getString());
            setCustomName(Text.literal(getCompanionName()));
            if (!player.getAbilities().creativeMode) {
                stack.decrement(1);
            }
            return ActionResult.SUCCESS;
        }

        // 右键打开GUI
        openCompanionGUI(player);
        return ActionResult.SUCCESS;
    }

    /**
     * 打开同伴GUI
     */
    private void openCompanionGUI(PlayerEntity player) {
        player.sendMessage(Text.literal("打开AI同伴面板..."), true);
    }

    /**
     * 处理玩家聊天消息
     */
    public void onPlayerChat(PlayerEntity player, String message) {
        if (!isOwner(player)) return;

        // 提交给意图安全层处理
        LocalAICompanion.getInstance().getIntentSecurityLayer()
            .processPlayerMessage((ServerPlayerEntity) player, message, false);
    }

    /**
     * 死亡处理
     */
    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        LocalAICompanion.LOGGER.info("[AICompanion] AI同伴死亡: {}", source.getName());

        // 通知任务调度器
        if (taskScheduler != null) {
            taskScheduler.onCompanionDeath();
        }

        // 掉落背包物品
        if (LocalAICompanion.getInstance().getConfigManager().getMainConfig().dropInventoryOnDeath) {
            dropInventory();
        }
    }

    /**
     * 掉落背包物品
     */
    protected void dropInventory() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = getEquippedStack(slot);
            if (!stack.isEmpty()) {
                dropStack(stack);
                equipStack(slot, ItemStack.EMPTY);
            }
        }
    }

    /**
     * 检查是否是主人
     */
    public boolean isOwner(PlayerEntity player) {
        if (player == null || ownerUuid == null) return false;
        return ownerUuid.equals(player.getUuid());
    }

    /**
     * 繁殖（AI同伴不能繁殖）
     */
    @Nullable
    @Override
    public PassiveEntity createChild(ServerWorld world, PassiveEntity other) {
        return null;
    }

    // ===== NBT 持久化 =====

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("CompanionName", getCompanionName());
        nbt.putString("OwnerName", ownerName != null ? ownerName : "");
        if (ownerUuid != null) {
            nbt.putUuid("OwnerUuid", ownerUuid);
        }
        nbt.putBoolean("Initialized", initialized);
        nbt.putBoolean("Tamed", tamed);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        setCompanionName(nbt.getString("CompanionName"));
        ownerName = nbt.getString("OwnerName");
        if (nbt.containsUuid("OwnerUuid")) {
            ownerUuid = nbt.getUuid("OwnerUuid");
        }
        initialized = nbt.getBoolean("Initialized");
        tamed = nbt.getBoolean("Tamed");
    }

    // ===== Getters / Setters =====

    public String getCompanionName() {
        return this.dataTracker.get(COMPANION_NAME);
    }

    public void setCompanionName(String name) {
        this.dataTracker.set(COMPANION_NAME, name);
    }

    public String getCompanionState() {
        return this.dataTracker.get(COMPANION_STATE);
    }

    public void setCompanionState(String state) {
        this.dataTracker.set(COMPANION_STATE, state);
    }

    public String getCurrentTask() {
        return this.dataTracker.get(CURRENT_TASK);
    }

    public void setCurrentTask(String task) {
        this.dataTracker.set(CURRENT_TASK, task);
    }

    public PathingService getPathingService() {
        return pathingService;
    }

    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public boolean isTamed() {
        return tamed;
    }

    public void setTamed(boolean tamed) {
        this.tamed = tamed;
    }
}
