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
 * 皮肤设置页面
 *
 * 包含：
 * - 皮肤类型选择（Steve/Alex/玩家名）
 * - 玩家名输入框
 */
public class SkinSettingsScreen extends Screen {
    private final Screen parent;
    private final MainConfig mainConfig;

    private TextFieldWidget playerNameField;
    private ButtonWidget skinTypeButton;

    private static final String[] SKIN_TYPES = {"Steve", "Alex"};
    private int selectedSkinIndex = 0;

    public SkinSettingsScreen(Screen parent, MainConfig mainConfig) {
        super(Text.literal("皮肤设置"));
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
            Text.literal("皮肤设置").formatted(Formatting.BOLD, Formatting.AQUA),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 40;

        // 皮肤类型
        TextWidget skinTypeLabel = new TextWidget(
            centerX - fieldWidth / 2, y, labelWidth, 20,
            Text.literal("皮肤类型:"),
            this.textRenderer
        );
        this.addDrawableChild(skinTypeLabel);

        skinTypeButton = ButtonWidget.builder(
            Text.literal(getCurrentSkinName()),
            button -> cycleSkinType()
        ).dimensions(centerX - fieldWidth / 2 + labelWidth + 5, y, fieldWidth - labelWidth - 5, 20).build();
        this.addDrawableChild(skinTypeButton);
        y += 35;

        // 提示
        TextWidget tip = new TextWidget(
            centerX - fieldWidth / 2, y, fieldWidth, 30,
            Text.literal("点击按钮切换 Steve/Alex 皮肤").formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(tip);
        y += 40;

        // 返回按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("返回"),
            button -> {
                saveConfig();
                if (this.client != null) {
                    this.client.setScreen(parent);
                }
            }
        ).dimensions(centerX - 50, y, 100, 20).build());
    }

    private String getCurrentSkinName() {
        String skin = mainConfig.defaultSkin.toLowerCase();
        if (skin.equals("alex") || skin.equals("slim")) {
            return "Alex (细手臂)";
        }
        return "Steve (粗手臂)";
    }

    private void cycleSkinType() {
        selectedSkinIndex = (selectedSkinIndex + 1) % SKIN_TYPES.length;
        String selected = SKIN_TYPES[selectedSkinIndex];
        mainConfig.defaultSkin = selected;
        skinTypeButton.setMessage(Text.literal(getCurrentSkinName()));
    }

    private void saveConfig() {
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
