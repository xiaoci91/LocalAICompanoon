package com.localaicompanion.gui.screen;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.PermissionConfig;
import com.localaicompanion.security.SecuritySandbox;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 安全权限页面
 *
 * 包含：
 * - 安全区绘制工具
 * - 全部权限开关
 * - 方块黑名单编辑
 */
public class SecurityScreen extends Screen {
    private final Screen parent;
    private final PermissionConfig permissionConfig;
    private final SecuritySandbox securitySandbox;

    public SecurityScreen(Screen parent, PermissionConfig permissionConfig, SecuritySandbox securitySandbox) {
        super(Text.literal("安全权限设置"));
        this.parent = parent;
        this.permissionConfig = permissionConfig;
        this.securitySandbox = securitySandbox;
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

        // 警告
        TextWidget warning = new TextWidget(
            centerX - 140, y, 280, 15,
            Text.literal("⚠ 高风险权限默认关闭，开启前请确认信任AI同伴")
                .formatted(Formatting.YELLOW),
            this.textRenderer
        );
        this.addDrawableChild(warning);
        y += 25;

        // ===== 方块操作权限 =====
        TextWidget section1 = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【方块操作】").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(section1);
        y += 20;

        // 破坏方块
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "允许破坏方块", permissionConfig.allowBreakBlocks,
            btn -> { permissionConfig.allowBreakBlocks = !permissionConfig.allowBreakBlocks; saveConfig(); clearAndInit(); });
        y += 22;

        // 放置方块
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "允许放置方块", permissionConfig.allowPlaceBlocks,
            btn -> { permissionConfig.allowPlaceBlocks = !permissionConfig.allowPlaceBlocks; saveConfig(); clearAndInit(); });
        y += 22;

        // 采集矿石
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "允许采集矿石", permissionConfig.allowMiningOres,
            btn -> { permissionConfig.allowMiningOres = !permissionConfig.allowMiningOres; saveConfig(); clearAndInit(); });
        y += 25;

        // ===== 物品权限 =====
        TextWidget section2 = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【物品交互】").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(section2);
        y += 20;

        // 开启箱子
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "允许开启箱子/容器", permissionConfig.allowOpenContainers,
            btn -> { permissionConfig.allowOpenContainers = !permissionConfig.allowOpenContainers; saveConfig(); clearAndInit(); });
        y += 22;

        // 拾取物品
        addToggleButton(centerX - listWidth / 2, y, listWidth,
            "允许拾取物品", permissionConfig.allowPickupItems,
            btn -> { permissionConfig.allowPickupItems = !permissionConfig.allowPickupItems; saveConfig(); clearAndInit(); });
        y += 25;

        // ===== 战斗权限 =====
        TextWidget section3 = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【战斗权限】").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(section3);
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
        TextWidget section4 = new TextWidget(
            centerX - listWidth / 2, y, listWidth, 15,
            Text.literal("【危险环境检测】").formatted(Formatting.BOLD),
            this.textRenderer
        );
        this.addDrawableChild(section4);
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

        // 方块黑名单编辑按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("方块黑名单管理"),
            button -> openBlacklistScreen()
        ).dimensions(centerX - 75, y, 150, 20).build());
        y += 25;

        // 安全区域管理按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("安全区域管理"),
            button -> openSafeZoneScreen()
        ).dimensions(centerX - 75, y, 150, 20).build());
    }

    private void addToggleButton(int x, int y, int width, String label, boolean enabled,
                                  ButtonWidget.PressAction action) {
        String statusText = enabled ? "§a✓ 开启" : "§c✗ 关闭";
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal(label + ": " + statusText),
            action
        ).dimensions(x, y, width, 20).build());
    }

    private void openBlacklistScreen() {
        if (this.client != null) {
            this.client.setScreen(new BlacklistScreen(this, permissionConfig));
        }
    }

    private void openSafeZoneScreen() {
        if (this.client != null) {
            this.client.setScreen(new SafeZoneScreen(this, permissionConfig));
        }
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
