package com.localaicompanion.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.gui.screen.MainPanelScreen;

/**
 * ModMenu集成
 * 让模组在ModMenu中显示配置入口
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new MainPanelScreen(
            parent,
            LocalAICompanion.getInstance().getConfigManager().getMainConfig(),
            LocalAICompanion.getInstance().getConfigManager().getLLMConfig(),
            LocalAICompanion.getInstance().getConfigManager().getHardwarePresetConfig()
        );
    }
}
