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
 * 皮肤设置界面
 *
 * 支持：
 * - Steve/Alex 默认皮肤切换
 * - 自定义皮肤PNG文件路径
 */
public class SkinSettingsScreen extends Screen {
    private final Screen parent;
    private final MainConfig mainConfig;

    private ButtonWidget skinTypeButton;
    private TextFieldWidget skinPathField;
    private TextWidget statusWidget;

    // 皮肤类型循环：Steve -> Alex -> 自定义 -> Steve
    private static final String[] SKIN_TYPES = {"Steve", "Alex", "CUSTOM"};
    private int currentSkinIndex = 0;

    public SkinSettingsScreen(Screen parent, MainConfig mainConfig) {
        super(Text.literal("外观设置"));
        this.parent = parent;
        this.mainConfig = mainConfig;

        // 初始化当前皮肤索引
        String skin = mainConfig.defaultSkin;
        if (skin.equalsIgnoreCase("alex")) {
            currentSkinIndex = 1;
        } else if (skin.equalsIgnoreCase("custom")) {
            currentSkinIndex = 2;
        } else {
            currentSkinIndex = 0;
        }
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 30;
        int fieldWidth = 260;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 60, y, 120, 20,
            Text.literal("外观设置").formatted(Formatting.BOLD, Formatting.AQUA),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 35;

        // 说明
        TextWidget intro = new TextWidget(
            centerX - fieldWidth / 2, y, fieldWidth, 15,
            Text.literal("点击按钮切换皮肤类型").formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(intro);
        y += 25;

        // 皮肤类型按钮
        skinTypeButton = ButtonWidget.builder(
            Text.literal("当前: " + getSkinTypeName()),
            button -> cycleSkinType()
        ).dimensions(centerX - fieldWidth / 2, y, fieldWidth, 25).build();
        this.addDrawableChild(skinTypeButton);
        y += 40;

        // 自定义皮肤路径（仅当选择自定义时显示）
        if (currentSkinIndex == 2) {
            TextWidget pathLabel = new TextWidget(
                centerX - fieldWidth / 2, y, fieldWidth, 15,
                Text.literal("皮肤PNG文件完整路径：").formatted(Formatting.YELLOW),
                this.textRenderer
            );
            this.addDrawableChild(pathLabel);
            y += 20;

            skinPathField = new TextFieldWidget(
                this.textRenderer,
                centerX - fieldWidth / 2, y,
                fieldWidth, 20,
                Text.literal("皮肤路径")
            );
            skinPathField.setText(mainConfig.customSkinPath);
            skinPathField.setMaxLength(500);
            this.addDrawableChild(skinPathField);
            y += 30;

            // 使用说明
            TextWidget tip1 = new TextWidget(
                centerX - fieldWidth / 2, y, fieldWidth, 12,
                Text.literal("使用方法：").formatted(Formatting.GOLD),
                this.textRenderer
            );
            this.addDrawableChild(tip1);
            y += 14;

            TextWidget tip2 = new TextWidget(
                centerX - fieldWidth / 2, y, fieldWidth, 12,
                Text.literal("1. 准备一张64x64或64x32的皮肤PNG文件"),
                this.textRenderer
            );
            this.addDrawableChild(tip2);
            y += 12;

            TextWidget tip3 = new TextWidget(
                centerX - fieldWidth / 2, y, fieldWidth, 12,
                Text.literal("2. 把文件放在电脑里，比如桌面"),
                this.textRenderer
            );
            this.addDrawableChild(tip3);
            y += 12;

            TextWidget tip4 = new TextWidget(
                centerX - fieldWidth / 2, y, fieldWidth, 12,
                Text.literal("3. 复制完整路径粘贴到上面的输入框"),
                this.textRenderer
            );
            this.addDrawableChild(tip4);
            y += 12;

            TextWidget tip5 = new TextWidget(
                centerX - fieldWidth / 2, y, fieldWidth, 12,
                Text.literal("4. 保存后重新召唤AI同伴即可生效"),
                this.textRenderer
            );
            this.addDrawableChild(tip5);
            y += 15;

            // 示例
            TextWidget example = new TextWidget(
                centerX - fieldWidth / 2, y, fieldWidth, 12,
                Text.literal("示例：C:\\Users\\你的用户名\\Desktop\\skin.png").formatted(Formatting.GRAY),
                this.textRenderer
            );
            this.addDrawableChild(example);
            y += 25;
        }

        // 状态提示
        statusWidget = new TextWidget(
            centerX - fieldWidth / 2, y, fieldWidth, 15,
            Text.literal(""),
            this.textRenderer
        );
        this.addDrawableChild(statusWidget);
        y += 20;

        // 保存返回按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("保存返回"),
            button -> {
                saveConfig();
                if (this.client != null) {
                    this.client.setScreen(parent);
                }
            }
        ).dimensions(centerX - 60, y, 120, 20).build());
    }

    private String getSkinTypeName() {
        switch (currentSkinIndex) {
            case 0: return "Steve（粗手臂）";
            case 1: return "Alex（细手臂）";
            case 2: return "自定义皮肤";
            default: return "Steve";
        }
    }

    private void cycleSkinType() {
        currentSkinIndex = (currentSkinIndex + 1) % SKIN_TYPES.length;
        // 重新创建界面以显示/隐藏路径输入框
        this.clearAndInit();
    }

    private void saveConfig() {
        mainConfig.defaultSkin = SKIN_TYPES[currentSkinIndex];

        if (skinPathField != null) {
            mainConfig.customSkinPath = skinPathField.getText().trim();
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
