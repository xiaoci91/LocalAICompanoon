package com.localaicompanion.entity.render;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.entity.ai.AICompanionEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

/**
 * AI同伴实体渲染器
 * 使用玩家模型渲染，支持自定义皮肤
 */
public class AICompanionEntityRenderer extends LivingEntityRenderer<AICompanionEntity, PlayerEntityModel<AICompanionEntity>> {
    // 默认皮肤纹理
    private static final Identifier DEFAULT_SKIN = new Identifier("textures/entity/player/wide/steve.png");

    public AICompanionEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public Identifier getTexture(AICompanionEntity entity) {
        // 可以根据配置加载不同的皮肤
        // 目前使用默认Steve皮肤
        return DEFAULT_SKIN;
    }

    @Override
    protected boolean hasLabel(AICompanionEntity entity) {
        return true; // 始终显示名字标签
    }
}
