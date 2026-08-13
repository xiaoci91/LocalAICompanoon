package com.localaicompanion;

import com.localaicompanion.entity.render.AICompanionEntityRenderer;
import com.localaicompanion.entity.ai.AICompanionEntities;
import com.localaicompanion.gui.KeyBindings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端侧初始化类
 * 负责注册渲染器、GUI、按键绑定等客户端专属内容
 */
public class LocalAICompanionClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("localaicompanion-client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[LocalAICompanion-Client] 客户端初始化...");

        // 注册实体渲染器
        EntityRendererRegistry.register(
            AICompanionEntities.AI_COMPANION,
            AICompanionEntityRenderer::new
        );

        // 注册按键绑定
        KeyBindings.register();

        // 注册客户端Tick事件（按键检测）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KeyBindings.tick(client);
        });

        LOGGER.info("[LocalAICompanion-Client] 客户端初始化完成");
    }
}
