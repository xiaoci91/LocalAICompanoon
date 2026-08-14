package com.localaicompanion.gui.screen;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.MainConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * TTS语音设置页面
 *
 * 包含：
 * - TTS开关
 * - 服务地址
 * - 语音选择
 * - 语速
 */
public class TTSSettingsScreen extends Screen {
    private final Screen parent;
    private final MainConfig mainConfig;

    private ButtonWidget enableButton;
    private TextFieldWidget serverUrlField;
    private TextFieldWidget voiceField;
    private TextFieldWidget speedField;

    public TTSSettingsScreen(Screen parent, MainConfig mainConfig) {
        super(Text.literal("语音设置"));
        this.parent = parent;
        this.mainConfig = mainConfig;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 30;
        int fieldWidth = 200;
        int labelWidth = 80;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 60, y, 120, 20,
            Text.literal("语音设置").formatted(Formatting.BOLD, Formatting.AQUA),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 40;

        // TTS开关
        TextWidget enableLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("语音功能:"),
            this.textRenderer
        );
        this.addDrawableChild(enableLabel);

        enableButton = ButtonWidget.builder(
            Text.literal(mainConfig.enableTTS ? "§a已开启" : "§c已关闭"),
            button -> toggleTTS()
        ).dimensions(centerX - fieldWidth / 2 + labelWidth + 5, y, fieldWidth - labelWidth - 5, 20).build();
        this.addDrawableChild(enableButton);
        y += 30;

        // 服务地址
        TextWidget urlLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("服务地址:"),
            this.textRenderer
        );
        this.addDrawableChild(urlLabel);

        serverUrlField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y,
            fieldWidth - labelWidth - 5, 20,
            Text.literal("服务地址")
        );
        serverUrlField.setText(mainConfig.ttsServerUrl);
        serverUrlField.setMaxLength(200);
        this.addDrawableChild(serverUrlField);
        y += 30;

        // 语音名称
        TextWidget voiceLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("语音名称:"),
            this.textRenderer
        );
        this.addDrawableChild(voiceLabel);

        voiceField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y,
            fieldWidth - labelWidth - 5, 20,
            Text.literal("语音名称")
        );
        voiceField.setText(mainConfig.ttsVoice);
        voiceField.setMaxLength(50);
        this.addDrawableChild(voiceField);
        y += 30;

        // 语速
        TextWidget speedLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("语速:"),
            this.textRenderer
        );
        this.addDrawableChild(speedLabel);

        speedField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + labelWidth + 5, y,
            fieldWidth - labelWidth - 5, 20,
            Text.literal("语速")
        );
        speedField.setText(String.valueOf(mainConfig.ttsSpeed));
        speedField.setMaxLength(10);
        this.addDrawableChild(speedField);
        y += 35;

        // 提示
        TextWidget tip = new TextWidget(
            centerX - fieldWidth / 2, y, fieldWidth, 30,
            Text.literal("需要先启动 Edge TTS 语音服务").formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(tip);
        y += 40;

        // 测试按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("测试语音"),
            button -> testTTS()
        ).dimensions(centerX - 105, y, 100, 20).build());

        // 返回按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("保存返回"),
            button -> {
                saveConfig();
                if (this.client != null) {
                    this.client.setScreen(parent);
                }
            }
        ).dimensions(centerX + 5, y, 100, 20).build());
    }

    private void toggleTTS() {
        mainConfig.enableTTS = !mainConfig.enableTTS;
        enableButton.setMessage(Text.literal(mainConfig.enableTTS ? "§a已开启" : "§c已关闭"));
    }

    private void testTTS() {
        saveConfig();
        try {
            LocalAICompanion.getInstance().getTtsService().speak("你好，我是小艾，这是语音测试。");
        } catch (Exception e) {
            // 忽略错误
        }
    }

    private void saveConfig() {
        mainConfig.ttsServerUrl = serverUrlField.getText();
        mainConfig.ttsVoice = voiceField.getText();
        try {
            mainConfig.ttsSpeed = Float.parseFloat(speedField.getText());
        } catch (NumberFormatException e) {
            mainConfig.ttsSpeed = 1.0f;
        }

        try {
            LocalAICompanion.getInstance().getConfigManager().saveAll();
        } catch (Exception e) {
            // 忽略保存错误
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
