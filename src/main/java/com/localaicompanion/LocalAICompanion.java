package com.localaicompanion;

import com.localaicompanion.config.ModConfigManager;
import com.localaicompanion.config.PermissionConfig;
import com.localaicompanion.entity.ai.AICompanionEntities;
import com.localaicompanion.command.CompanionCommand;
import com.localaicompanion.event.EventListeners;
import com.localaicompanion.intent.IntentSecurityLayer;
import com.localaicompanion.llm.LLMClient;
import com.localaicompanion.memory.MemoryManager;
import com.localaicompanion.security.SecuritySandbox;
import com.localaicompanion.task.TaskScheduler;
import com.localaicompanion.llm.LLMServiceDetector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LocalAICompanion - 本地离线大模型驱动AI生存同伴模组
 * 主入口类，负责初始化所有子系统
 *
 * 四层架构：
 * 1. 游戏实体表现层 (entity/gui/event)
 * 2. 意图解析安全校验层 (intent)
 * 3. 任务调度管理层 (task)
 * 4. LLM网络客户端层 (llm)
 */
public class LocalAICompanion implements ModInitializer {
    public static final String MOD_ID = "localaicompanion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static LocalAICompanion instance;
    private MinecraftServer server;

    // 核心子系统
    private ModConfigManager configManager;
    private MemoryManager memoryManager;
    private SecuritySandbox securitySandbox;
    private TaskScheduler taskScheduler;
    private LLMServiceDetector serviceDetector;
    private LLMClient llmClient;
    private IntentSecurityLayer intentSecurityLayer;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("[LocalAICompanion] 正在初始化本地AI同伴模组...");

        // 第1步：加载配置（最先加载，其他系统依赖）
        this.configManager = new ModConfigManager();
        this.configManager.loadOrCreateDefault();

        // 第2步：初始化安全沙箱（权限校验基础）
        this.securitySandbox = new SecuritySandbox();
        this.securitySandbox.loadFromConfig(configManager.getPermissionConfig());

        // 第3步：初始化记忆系统
        this.memoryManager = new MemoryManager();

        // 第4步：初始化任务调度器
        this.taskScheduler = new TaskScheduler();

        // 第5步：初始化LLM客户端
        this.llmClient = new LLMClient(configManager.getLLMConfig());

        // 第6步：初始化意图安全校验层（核心隔离层）
        this.intentSecurityLayer = new IntentSecurityLayer(
            configManager.getPermissionConfig(),
            configManager.getMainConfig(),
            securitySandbox,
            llmClient,
            memoryManager,
            taskScheduler
        );

        // 第7步：初始化LLM服务探测器
        this.serviceDetector = new LLMServiceDetector();

        // 第8步：注册实体
        AICompanionEntities.registerAll();

        // 第9步：注册命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            CompanionCommand.register(dispatcher)
        );

        // 第10步：注册事件监听器
        EventListeners.registerAll();

        // 第8步：注册服务器生命周期事件
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            this.server = server;
            onServerStarting(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            onServerStopping(server);
        });

        // 第9步：注册服务器Tick事件（任务调度驱动）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (taskScheduler != null) {
                taskScheduler.tick(server);
            }
        });

        LOGGER.info("[LocalAICompanion] 模组初始化完成！");
        LOGGER.info("[LocalAICompanion] 提示：请确保已安装Ollama或其他本地大模型服务");
    }

    private void onServerStarting(MinecraftServer server) {
        LOGGER.info("[LocalAICompanion] 服务器启动，加载世界数据...");

        // 加载世界专属的长期记忆
        memoryManager.loadWorldMemory(server);

        // 加载世界专属的安全区域配置
        securitySandbox.loadWorldZones(server);

        // 自动探测本地LLM服务
        if (configManager.getMainConfig().autoDetectLLM) {
            serviceDetector.startAutoDetect(configManager.getLLMConfig());
        }
    }

    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("[LocalAICompanion] 服务器关闭，保存数据...");

        // 保存长期记忆
        memoryManager.saveWorldMemory(server);

        // 保存安全区域配置
        securitySandbox.saveWorldZones(server);

        // 关闭任务调度器
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }

        // 保存配置
        configManager.saveAll();
    }

    public static LocalAICompanion getInstance() {
        return instance;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public ModConfigManager getConfigManager() {
        return configManager;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public SecuritySandbox getSecuritySandbox() {
        return securitySandbox;
    }

    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    public LLMClient getLLMClient() {
        return llmClient;
    }

    public IntentSecurityLayer getIntentSecurityLayer() {
        return intentSecurityLayer;
    }

    public LLMServiceDetector getServiceDetector() {
        return serviceDetector;
    }
}
