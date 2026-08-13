package com.localaicompanion.gui.screen;

import com.localaicompanion.config.HardwarePresetConfig;
import com.localaicompanion.config.LLMConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * LLM设置页面
 *
 * 包含：
 * - API地址填写
 * - 模型名称
 * - 温度等参数
 * - 系统提示词编辑器
 * - 硬件预设下拉菜单
 */
public class LLMSettingsScreen extends Screen {
    private final Screen parent;
    private final LLMConfig config;
    private final HardwarePresetConfig presetConfig;

    private TextFieldWidget apiUrlField;
    private TextFieldWidget modelField;
    private TextFieldWidget temperatureField;
    private TextFieldWidget maxTokensField;
    private TextFieldWidget contextWindowField;
    private TextFieldWidget systemPromptField;

    private String selectedPreset;
    private String[] presetNames;

    public LLMSettingsScreen(Screen parent, LLMConfig config, HardwarePresetConfig presetConfig) {
        super(Text.literal("LLM设置"));
        this.parent = parent;
        this.config = config;
        this.presetConfig = presetConfig;
        this.selectedPreset = config.currentPreset;
        this.presetNames = presetConfig.getPresetNames();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 20;
        int fieldWidth = 250;
        int labelWidth = 100;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 50, y, 100, 20,
            Text.literal("LLM设置").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 30;

        // 硬件预设选择
        TextWidget presetLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("硬件预设:"),
            this.textRenderer
        );
        this.addDrawableChild(presetLabel);

        // 预设下拉按钮（简化为循环切换按钮）
        ButtonWidget presetButton = ButtonWidget.builder(
            Text.literal(selectedPreset),
            button -> cyclePreset()
        ).dimensions(centerX - fieldWidth / 2 + labelWidth + 5, y, fieldWidth - labelWidth - 5, 20).build();
        this.addDrawableChild(presetButton);
        y += 25;

        // API地址
        TextWidget urlLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("API地址:"),
            this.textRenderer
        );
        this.addDrawableChild(urlLabel);

        apiUrlField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y, fieldWidth - labelWidth - 5, 20,
            Text.literal("")
        );
        apiUrlField.setText(config.apiBaseUrl);
        this.addDrawableChild(apiUrlField);
        y += 25;

        // 模型名称
        TextWidget modelLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("模型名称:"),
            this.textRenderer
        );
        this.addDrawableChild(modelLabel);

        modelField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y, fieldWidth - labelWidth - 5, 20,
            Text.literal("")
        );
        modelField.setText(config.modelName);
        this.addDrawableChild(modelField);
        y += 25;

        // 温度
        TextWidget tempLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("温度:"),
            this.textRenderer
        );
        this.addDrawableChild(tempLabel);

        temperatureField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y, 80, 20,
            Text.literal("")
        );
        temperatureField.setText(String.valueOf(config.temperature));
        this.addDrawableChild(temperatureField);
        y += 25;

        // 最大token
        TextWidget tokensLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("最大输出:"),
            this.textRenderer
        );
        this.addDrawableChild(tokensLabel);

        maxTokensField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y, 80, 20,
            Text.literal("")
        );
        maxTokensField.setText(String.valueOf(config.maxTokens));
        this.addDrawableChild(maxTokensField);
        y += 25;

        // 上下文窗口
        TextWidget ctxLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("上下文:"),
            this.textRenderer
        );
        this.addDrawableChild(ctxLabel);

        contextWindowField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y, 80, 20,
            Text.literal("")
        );
        contextWindowField.setText(String.valueOf(config.contextWindow));
        this.addDrawableChild(contextWindowField);
        y += 25;

        // 系统提示词
        TextWidget promptLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("系统提示词:"),
            this.textRenderer
        );
        this.addDrawableChild(promptLabel);
        y += 20;

        systemPromptField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2, y, fieldWidth, 60,
            Text.literal("")
        );
        systemPromptField.setText(config.systemPrompt);
        systemPromptField.setMaxLength(2000);
        this.addDrawableChild(systemPromptField);
        y += 70;

        // 测试连接按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("测试连接"),
            button -> testConnection()
        ).dimensions(centerX - 120, y, 100, 20).build());

        // 保存按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("保存设置"),
            button -> saveSettings()
        ).dimensions(centerX + 20, y, 100, 20).build());
    }

    private void cyclePreset() {
        int currentIndex = -1;
        for (int i = 0; i < presetNames.length; i++) {
            if (presetNames[i].equals(selectedPreset)) {
                currentIndex = i;
                break;
            }
        }

        int nextIndex = (currentIndex + 1) % presetNames.length;
        selectedPreset = presetNames[nextIndex];

        // 应用预设
        presetConfig.applyPreset(selectedPreset, config);

        // 更新输入框
        modelField.setText(config.modelName);
        temperatureField.setText(String.valueOf(config.temperature));
        maxTokensField.setText(String.valueOf(config.maxTokens));
        contextWindowField.setText(String.valueOf(config.contextWindow));

        this.clearAndInit();
    }

    private void testConnection() {
        // 测试连接逻辑
    }

    private void saveSettings() {
        config.apiBaseUrl = apiUrlField.getText();
        config.modelName = modelField.getText();
        config.currentPreset = selectedPreset;

        try {
            config.temperature = Float.parseFloat(temperatureField.getText());
        } catch (NumberFormatException ignored) {}

        try {
            config.maxTokens = Integer.parseInt(maxTokensField.getText());
        } catch (NumberFormatException ignored) {}

        try {
            config.contextWindow = Integer.parseInt(contextWindowField.getText());
        } catch (NumberFormatException ignored) {}

        config.systemPrompt = systemPromptField.getText();

        // 保存配置
        if (this.client != null && this.client.player != null) {
            this.client.player.sendMessage(Text.literal("设置已保存"), true);
        }

        close();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
