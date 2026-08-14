package com.localaicompanion.entity.render;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.MainConfig;
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
    private static final Identifier STEVE_SKIN = new Identifier("textures/entity/player/wide/steve.png");
    private static final Identifier ALEX_SKIN = new Identifier("textures/entity/player/slim/alex.png");

    private final PlayerEntityModel<AICompanionEntity> wideModel;
    private final PlayerEntityModel<AICompanionEntity> slimModel;

    public AICompanionEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        this.wideModel = this.model;
        this.slimModel = new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_SLIM), true);
    }

    @Override
    public Identifier getTexture(AICompanionEntity entity) {
        try {
            MainConfig config = LocalAICompanion.getInstance().getConfigManager().getMainConfig();
            String skin = config.defaultSkin.toLowerCase();

            if (skin.equals("alex") || skin.equals("slim")) {
                return ALEX_SKIN;
            }
            // 默认Steve皮肤
            return STEVE_SKIN;
        } catch (Exception e) {
            return STEVE_SKIN;
        }
    }

    @Override
    public PlayerEntityModel<AICompanionEntity> getModel() {
        try {
            MainConfig config = LocalAICompanion.getInstance().getConfigManager().getMainConfig();
            String skin = config.defaultSkin.toLowerCase();

            if (skin.equals("alex") || skin.equals("slim")) {
                return slimModel;
            }
            return wideModel;
        } catch (Exception e) {
            return wideModel;
        }
    }

    @Override
    protected boolean hasLabel(AICompanionEntity entity) {
        return true; // 始终显示名字标签
    }
}
