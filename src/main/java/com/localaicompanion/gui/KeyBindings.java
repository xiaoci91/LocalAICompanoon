package com.localaicompanion.gui;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.gui.screen.MainPanelScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 按键绑定
 */
public class KeyBindings {
    // 打开AI同伴面板
    public static KeyBinding openCompanionPanel;

    // 快速召唤/解散
    public static KeyBinding toggleCompanion;

    // 快速说话
    public static KeyBinding quickTalk;

    public static void register() {
        openCompanionPanel = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.localaicompanion.open_panel",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R, // 默认R键
            "category.localaicompanion"
        ));

        toggleCompanion = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.localaicompanion.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, // 默认未绑定
            "category.localaicompanion"
        ));

        quickTalk = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.localaicompanion.quick_talk",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, // 默认未绑定
            "category.localaicompanion"
        ));
    }

    public static void tick(net.minecraft.client.MinecraftClient client) {
        while (openCompanionPanel.wasPressed()) {
            // 打开同伴面板GUI
            if (client.world != null) {
                client.setScreen(new MainPanelScreen(
                    null,
                    LocalAICompanion.getInstance().getConfigManager().getMainConfig(),
                    LocalAICompanion.getInstance().getConfigManager().getLLMConfig(),
                    LocalAICompanion.getInstance().getConfigManager().getHardwarePresetConfig()
                ));
            }
        }

        while (toggleCompanion.wasPressed()) {
            // 切换同伴召唤状态
            if (client.player != null) {
                client.player.networkHandler.sendChatCommand("companion summon");
            }
        }

        while (quickTalk.wasPressed()) {
            // 打开快速聊天输入框（暂未实现）
        }
    }
}
