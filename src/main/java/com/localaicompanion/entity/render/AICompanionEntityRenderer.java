package com.localaicompanion.entity.render;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.MainConfig;
import com.localaicompanion.entity.ai.AICompanionEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * AI同伴实体渲染器
 * 使用玩家模型渲染，支持自定义皮肤
 */
public class AICompanionEntityRenderer extends LivingEntityRenderer<AICompanionEntity, PlayerEntityModel<AICompanionEntity>> {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-Render");

    // 默认皮肤纹理
    private static final Identifier STEVE_SKIN = new Identifier("textures/entity/player/wide/steve.png");
    private static final Identifier ALEX_SKIN = new Identifier("textures/entity/player/slim/alex.png");

    // 自定义皮肤缓存
    private static final Map<String, Identifier> customSkinCache = new HashMap<>();
    private static final Map<String, Long> skinFileTimestamps = new HashMap<>();

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

            if (skin.equals("custom")) {
                // 自定义皮肤
                return getCustomSkin(config.customSkinPath);
            } else if (skin.equals("alex") || skin.equals("slim")) {
                return ALEX_SKIN;
            }
            // 默认Steve皮肤
            return STEVE_SKIN;
        } catch (Exception e) {
            LOGGER.warn("[Render] 获取皮肤失败，使用默认: {}", e.getMessage());
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
            // Steve 和自定义皮肤都用 wide 模型
            return wideModel;
        } catch (Exception e) {
            return wideModel;
        }
    }

    /**
     * 获取自定义皮肤纹理
     */
    private Identifier getCustomSkin(String path) {
        if (path == null || path.isEmpty()) {
            return STEVE_SKIN;
        }

        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            LOGGER.warn("[Render] 皮肤文件不存在: {}", path);
            return STEVE_SKIN;
        }

        long lastModified = file.lastModified();
        String cacheKey = path;

        // 检查缓存是否有效（文件修改时间没变就用缓存）
        if (customSkinCache.containsKey(cacheKey)) {
            Long cachedTime = skinFileTimestamps.get(cacheKey);
            if (cachedTime != null && cachedTime == lastModified) {
                return customSkinCache.get(cacheKey);
            }
        }

        // 加载皮肤
        try {
            Identifier textureId = loadSkinFromFile(file);
            if (textureId != null) {
                customSkinCache.put(cacheKey, textureId);
                skinFileTimestamps.put(cacheKey, lastModified);
                return textureId;
            }
        } catch (Exception e) {
            LOGGER.error("[Render] 加载皮肤失败: {}", e.getMessage());
        }

        return STEVE_SKIN;
    }

    /**
     * 从文件加载皮肤纹理
     */
    private Identifier loadSkinFromFile(File file) throws Exception {
        InputStream inputStream = new FileInputStream(file);
        try {
            NativeImage image = NativeImage.read(inputStream);

            // 验证皮肤尺寸（必须是 64x64 或 64x32）
            int width = image.getWidth();
            int height = image.getHeight();
            if (width != 64 || (height != 64 && height != 32)) {
                LOGGER.warn("[Render] 皮肤尺寸不正确: {}x{} (需要 64x64 或 64x32)", width, height);
                image.close();
                return null;
            }

            // 注册纹理
            NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
            String textureName = "localaicompanion/skin_" + Math.abs(file.getPath().hashCode());
            Identifier id = new Identifier("localaicompanion", textureName);

            MinecraftClient client = MinecraftClient.getInstance();
            client.getTextureManager().registerTexture(id, texture);

            LOGGER.info("[Render] 加载自定义皮肤成功: {}", file.getPath());
            return id;
        } finally {
            inputStream.close();
        }
    }

    @Override
    protected boolean hasLabel(AICompanionEntity entity) {
        return true; // 始终显示名字标签
    }
}
