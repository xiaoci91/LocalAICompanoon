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

    // 主动对话计时器
    private int nextChatDelay = 0;
    private int chatTimer = 0;

    // 游荡相关
    private int wanderTimer = 0;

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
        if (wanderTimer >= 100 && isIdle()) {
            tryWander();
            wanderTimer = 0;
        }
    }

    /**
     * 检查是否处于空闲状态
     */
    private boolean isIdle() {
        if (taskScheduler != null) {
            var currentTask = taskScheduler.getCurrentTask();
            if (currentTask != null && currentTask.getState().isActive()) {
                return false;
            }
        }
        if (pathingService != null && pathingService.isActive()) {
            return false;
        }
        return true;
    }

    /**
     * 简单游荡：随机朝一个方向走几步
     */
    private void tryWander() {
        try {
            PlayerEntity owner = getOwnerPlayer();
            if (owner == null) return;

            // 离主人太近就不走了
            if (this.squaredDistanceTo(owner) < 4.0) return;

            // 随机生成一个目标点
            double angle = this.random.nextDouble() * Math.PI * 2;
            double distance = 2 + this.random.nextDouble() * 3;
            double targetX = this.getX() + Math.cos(angle) * distance;
            double targetZ = this.getZ() + Math.sin(angle) * distance;
            double targetY = this.getY();

            // 设置移动目标（速度和玩家差不多）
            this.getNavigation().startMovingTo(targetX, targetY, targetZ, 1.0);
        } catch (Exception e) {
            // 忽略游荡错误
        }
    }

    /**
     * 触发主动对话
     */
    private void triggerProactiveChat() {
        try {
            PlayerEntity owner = getOwnerPlayer();
            if (owner == null) return;

            // 只有主人生存/冒险模式才主动说话
            if (owner.isCreative() || owner.isSpectator()) return;

            // 收集环境信息
            String timeOfDay = getTimeOfDay();
            String weather = getWeather();
            String playerStatus = getPlayerStatus(owner);
            String surroundings = getSurroundingsInfo();

            // 构建上下文
            String context = "当前游戏状态：\n" +
                "- 时间：" + timeOfDay + "\n" +
                "- 天气：" + weather + "\n" +
                "- 玩家状态：" + playerStatus + "\n" +
                "- 周围环境：" + surroundings + "\n";

            // 构建prompt，让AI根据环境主动说话
            String userMessage = "你是小艾，玩家的AI生存同伴。现在请你根据当前的游戏环境，主动和玩家说一句话。" +
                "你可以：评论周围的环境、问玩家在做什么、提醒玩家注意危险、分享你的想法、或者建议玩家做什么。" +
                "只用说一句话，不要太长，要自然，像真人一样。不要用列表格式，直接说内容。";

            LocalAICompanion.getInstance().getLLMClient().sendChatRequest(
                userMessage,
                context,
                new com.localaicompanion.llm.LLMClient.LLMCallback() {
                    @Override
                    public void onSuccess(com.localaicompanion.llm.LLMResponse response) {
                        String message = response.getContent().trim();
                        // 去掉可能的引号
                        message = message.replaceAll("^[\"']|[\"']$", "");
                        if (!message.isEmpty()) {
                            sendChatMessage(owner, message);
                        } else {
                            // LLM返回空，用预设句子
                            sendFallbackChat(owner);
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        // LLM调用失败，用预设句子
                        sendFallbackChat(owner);
                    }
                }
            );
        } catch (Exception e) {
            // 出错了也用预设句子
            sendFallbackChat(getOwnerPlayer());
        }
    }

    /**
     * 预设的主动对话句子（fallback）
     */
    private void sendFallbackChat(PlayerEntity owner) {
        if (owner == null) return;

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

        String message = fallbackMessages[this.random.nextInt(fallbackMessages.length)];
        sendChatMessage(owner, message);
    }

    /**
     * 获取当前时间描述
     */
    private String getTimeOfDay() {
        long time = getWorld().getTimeOfDay() % 24000;
        if (time < 1000) return "黎明";
        if (time < 6000) return "上午";
        if (time < 11000) return "中午";
        if (time < 13000) return "下午";
        if (time < 18000) return "傍晚";
        if (time < 22000) return "晚上";
        return "深夜";
    }

    /**
     * 获取天气描述
     */
    private String getWeather() {
        if (getWorld().isThundering()) return "雷暴";
        if (getWorld().isRaining()) return "下雨";
        return "晴朗";
    }

    /**
     * 获取玩家状态描述
     */
    private String getPlayerStatus(PlayerEntity player) {
        float health = player.getHealth();
        int hunger = player.getHungerManager().getFoodLevel();

        String status = "";
        if (health < 10) status += "生命值较低，";
        else if (health < 15) status += "生命值一般，";
        else status += "生命值健康，";

        if (hunger < 10) status += "很饿";
        else if (hunger < 18) status += "有点饿";
        else status += "饱食度充足";

        return status;
    }

    /**
     * 获取周围环境简单描述
     */
    private String getSurroundingsInfo() {
        // 简单判断：在地下还是地上
        if (this.getY() < 50) return "在地下/洞穴中";
        if (this.getY() > 100) return "在高处/山上";
        return "在地面";
    }

    /**
     * 发送聊天消息给主人
     */
    private void sendChatMessage(PlayerEntity player, String message) {
        if (player == null || message == null) return;

        String npcName = getCompanionName();
        Text text = Text.literal("[" + npcName + "] ").append(message);
        player.sendMessage(text, false);

        // TTS语音播放
        try {
            LocalAICompanion.getInstance().getTtsService().speak(message);
        } catch (Exception e) {
            // 忽略TTS错误
        }
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
