package com.localaicompanion.entity.ai;

import com.localaicompanion.LocalAICompanion;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * AI同伴实体注册
 * 注册所有自定义实体类型
 */
public class AICompanionEntities {
    // AI同伴实体类型
    public static final EntityType<AICompanionEntity> AI_COMPANION = FabricEntityTypeBuilder
        .create(SpawnGroup.CREATURE, AICompanionEntity::new)
        .dimensions(EntityDimensions.fixed(0.6f, 1.8f)) // 和玩家一样大小
        .trackRangeChunks(10)
        .build();

    public static void registerAll() {
        // 注册实体类型
        Registry.register(
            Registries.ENTITY_TYPE,
            new Identifier(LocalAICompanion.MOD_ID, "ai_companion"),
            AI_COMPANION
        );

        // 注册默认属性
        FabricDefaultAttributeRegistry.register(
            AI_COMPANION,
            AICompanionEntity.createCompanionAttributes()
        );

        LocalAICompanion.LOGGER.info("[Entities] AI同伴实体已注册");
    }
}
