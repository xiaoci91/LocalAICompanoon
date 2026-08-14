package com.localaicompanion.gui.screen;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.HardwarePresetConfig;
import com.localaicompanion.config.LLMConfig;
import com.localaicompanion.config.MainConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 主状态面板
 *
 * 显示：
 * - LLM连接状态
 * - 当前运行模式
 * - NPC状态
 * - 硬件预设选择入口
 *
 * 首页醒目提示：本模组需要用户自行安装Ollama或者其他本地大模型服务
 */
public class MainPanelScreen extends Screen {
    private final Screen parent;
    private final MainConfig mainConfig;
    private final LLMConfig llmConfig;
    private final HardwarePresetConfig presetConfig;

    // 连接状态
    private boolean connected = false;
    private String connectionStatus = "检测中...";

    public MainPanelScreen(Screen parent, MainConfig mainConfig, LLMConfig llmConfig,
                           HardwarePresetConfig presetConfig) {
        super(Text.literal("AI同伴 - 主面板"));
        this.parent = parent;
        this.mainConfig = mainConfig;
        this.llmConfig = llmConfig;
        this.presetConfig = presetConfig;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 30;

        // 从LLM客户端获取真实连接状态
        try {
            this.connected = LocalAICompanion.getInstance().getLLMClient().isConnected();
        } catch (Exception e) {
            this.connected = false;
        }

        // 标题
        TextWidget title = new TextWidget(
            centerX - 100, y, 200, 20,
            Text.literal("Local AI Companion").formatted(Formatting.BOLD, Formatting.AQUA),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 30;

        // 重要提示
        TextWidget warning = new TextWidget(
            centerX - 150, y, 300, 30,
            Text.literal("⚠ 本模组需要自行安装Ollama或其他本地大模型服务")
                .formatted(Formatting.YELLOW),
            this.textRenderer
        );
        this.addDrawableChild(warning);
        y += 40;

        // 连接状态
        Text statusText = connected
            ? Text.literal("● 已连接").formatted(Formatting.GREEN)
            : Text.literal("● 未连接").formatted(Formatting.RED);
        TextWidget statusWidget = new TextWidget(
            centerX - 100, y, 200, 15,
            Text.literal("LLM状态: ").append(statusText),
            this.textRenderer
        );
        this.addDrawableChild(statusWidget);
        y += 20;

        // 运行模式
        TextWidget modeWidget = new TextWidget(
            centerX - 100, y, 200, 15,
            Text.literal("运行模式: " + mainConfig.runMode),
            this.textRenderer
        );
        this.addDrawableChild(modeWidget);
        y += 20;

        // 当前预设
        TextWidget presetWidget = new TextWidget(
            centerX - 100, y, 200, 15,
            Text.literal("硬件预设: " + llmConfig.currentPreset),
            this.textRenderer
        );
        this.addDrawableChild(presetWidget);
        y += 30;

        // 按钮区域
        int buttonWidth = 150;
        int buttonHeight = 20;
        int buttonX = centerX - buttonWidth / 2;

        // LLM设置
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("LLM设置"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(new LLMSettingsScreen(this, llmConfig, presetConfig));
                }
            }
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 25;

        // 任务管理
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("任务管理"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(new TaskManagerScreen(this, LocalAICompanion.getInstance().getTaskScheduler()));
                }
            }
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 25;

        // 记忆管理
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("记忆管理"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(new MemoryManagerScreen(this, LocalAICompanion.getInstance().getMemoryManager()));
                }
            }
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 25;

        // 安全权限
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("安全权限"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(new SecurityScreen(
                        this,
                        LocalAICompanion.getInstance().getConfigManager().getPermissionConfig(),
                        LocalAICompanion.getInstance().getSecuritySandbox()
                    ));
                }
            }
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 25;

        // 外观设置（皮肤等）
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("外观设置"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(new SkinSettingsScreen(this, mainConfig));
                }
            }
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 25;

        // 语音设置
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("语音设置"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(new TTSSettingsScreen(this, mainConfig));
                }
            }
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 25;

        // 其他设置（暂时打开LLM设置，后续可扩展）
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("其他设置"),
            button -> {
                if (this.client != null) {
                    this.client.setScreen(new LLMSettingsScreen(this, llmConfig, presetConfig));
                }
            }
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 35;

        // 切换模式按钮
        String modeButtonText = mainConfig.getRunModeEnum() == MainConfig.RunMode.SURVIVAL_TEAMMATE
            ? "切换到聊天模式" : "切换到生存模式";
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal(modeButtonText),
            button -> toggleRunMode()
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
        y += 25;

        // 召唤/解散按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("召唤AI同伴"),
            button -> summonCompanion()
        ).dimensions(buttonX, y, buttonWidth, buttonHeight).build());
    }

    private void toggleRunMode() {
        if (mainConfig.getRunModeEnum() == MainConfig.RunMode.SURVIVAL_TEAMMATE) {
            mainConfig.runMode = "ROLEPLAY_CHAT";
        } else {
            mainConfig.runMode = "SURVIVAL_TEAMMATE";
        }
        this.clearAndInit();
    }

    private void summonCompanion() {
        // 发送召唤命令
        if (this.client != null && this.client.player != null) {
            this.client.player.networkHandler.sendChatCommand("companion summon");
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }
}
