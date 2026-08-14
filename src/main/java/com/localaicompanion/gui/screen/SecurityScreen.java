package com.localaicompanion.gui.screen;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.PermissionConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 安全权限页面 - 简化版
 *
 * 只包含：
 * - 战斗权限
 * - 危险环境检测
 */
public class SecurityScreen extends Screen {
    private final Screen parent;
    private final PermissionConfig permissionConfig;

    public SecurityScreen(Screen parent, PermissionConfig permissionConfig) {
        super(Text.literal("安全权限设置"));
        this.parent = parent;
        this.permissionConfig = permissionConfig;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 20;
        int listWidth = 300;

        // 标题
        TextWidget title = new TextWidget(
            centerX - 60, y, 120, 20,
            Text.literal("安全权限设置").formatted(Formatting.BOLD, Formatting.RED),
            this.textRenderer
        );
        this.addDrawableChild(title);
        y += 30;

        // 说明
        TextWidget desc = new TextWidget(
            centerX - 140, y, 280, 15,
            Text.literal("仅生存模式生效，聊天模式下全部功能禁用")
                .formatted(Formatting.GRAY),
            this.textRenderer
        );
        this.addDrawableChild(desc);
        y += 25;

        // ===== 战斗权限 =====
        TextWidget section1 = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【战斗权限】").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(section1);
        y += 20;

        // 攻击怪物
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "允许攻击怪物", permissionConfig.allowAttackMobs,
            btn -> { permissionConfig.allowAttackMobs = !permissionConfig.allowAttackMobs; saveConfig(); clearAndInit(); });
        y += 22;

        // 攻击动物
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "允许攻击动物", permissionConfig.allowAttackAnimals,
            btn -> { permissionConfig.allowAttackAnimals = !permissionConfig.allowAttackAnimals; saveConfig(); clearAndInit(); });
        y += 25;

        // ===== 危险环境检测 =====
        TextWidget section2 = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【危险环境检测】").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(section2);
        y += 20;

        // 岩浆检测
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "岩浆紧急撤离", permissionConfig.emergencyOnLava,
            btn -> { permissionConfig.emergencyOnLava = !permissionConfig.emergencyOnLava; saveConfig(); clearAndInit(); });
        y += 22;

        // 火焰检测
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "火焰紧急撤离", permissionConfig.emergencyOnFire,
            btn -> { permissionConfig.emergencyOnFire = !permissionConfig.emergencyOnFire; saveConfig(); clearAndInit(); });
        y += 22;

        // 虚空检测
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "虚空紧急撤离", permissionConfig.emergencyOnVoid,
            btn -> { permissionConfig.emergencyOnVoid = !permissionConfig.emergencyOnVoid; saveConfig(); clearAndInit(); });
        y += 30;

        // 返回按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("返回"),
            button -> close()
        ).dimensions(centerX - 50, y, 100, 20).build());
    }

    private void addToggleButton(int x, int y, int width, String label, boolean enabled,
                                  ButtonWidget.PressAction action) {
        String statusText = enabled ? "§a✓ 开启" : "§c✗ 关闭";
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal(label + ": " + statusText),
            action
        ).dimensions(x, y, width, 20).build());
    }

    /**
     * 保存配置到文件
     */
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
