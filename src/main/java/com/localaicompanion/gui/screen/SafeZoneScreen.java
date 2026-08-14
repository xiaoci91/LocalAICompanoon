package com.localaicompanion.gui.screen;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.PermissionConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 安全区域管理界面
 */
public class SafeZoneScreen extends Screen {
    private final Screen parent;
    private final PermissionConfig permissionConfig;

    private ButtonWidget enableButton;
    private TextFieldWidget radiusField;

    public SafeZoneScreen(Screen parent, PermissionConfig permissionConfig) {
        super(Text.literal("安全区域管理"));
        this.parent = parent;
        this.permissionConfig = permissionConfig;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 30;
        int fieldWidth = 240;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 70, y, 140, 20,
            Text.literal("安全区域管理").formatted(Formatting.BOLD, Formatting.GREEN),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 40;

        // 启用开关
        TextWidget enableLabel = new TextWidget(
            centerX - fieldWidth / 2, y, 100, 20,
            Text.literal("安全区域保护:"),
            this.textRenderer
        );
        this.addDrawableChild(enableLabel);

        enableButton = ButtonWidget.builder(
            Text.literal(permissionConfig.enableSafeZones ? "§a已开启" : "§c已关闭"),
            button -> toggleEnable()
        ).dimensions(centerX - fieldWidth / 2 + 105, y, fieldWidth - 105, 20).build();
        this.addDrawableChild(enableButton);
        y += 35;

        // 出生点保护半径
        TextWidget radiusLabel = new TextWidget(
            centerX - fieldWidth / 2, y, 120, 20,
            Text.literal("出生点保护半径:"),
            this.textRenderer
        );
        this.addDrawableChild(radiusLabel);

        radiusField = new TextFieldWidget(
            this.textRenderer,
            centerX - fieldWidth / 2 + 125, y,
            fieldWidth - 125, 20,
            Text.literal("半径")
        );
        radiusField.setText(String.valueOf(permissionConfig.spawnProtectionRadius));
        radiusField.setMaxLength(10);
        this.addDrawableChild(radiusField);
        y += 30;

        // 提示
        TextWidget tip1 = new TextWidget(
            centerX - fieldWidth / 2, y, fieldWidth, 15,
            Text.literal("安全区域内的方块不会被AI破坏").formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(tip1);
        y += 20;

        TextWidget tip2 = new TextWidget(
            centerX - fieldWidth / 2, y, fieldWidth, 15,
            Text.literal("更多安全区域功能开发中...").formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(tip2);
        y += 40;

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

    private void toggleEnable() {
        permissionConfig.enableSafeZones = !permissionConfig.enableSafeZones;
        enableButton.setMessage(Text.literal(permissionConfig.enableSafeZones ? "§a已开启" : "§c已关闭"));
    }

    private void saveConfig() {
        try {
            permissionConfig.spawnProtectionRadius = Integer.parseInt(radiusField.getText());
        } catch (NumberFormatException e) {
            permissionConfig.spawnProtectionRadius = 16;
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
