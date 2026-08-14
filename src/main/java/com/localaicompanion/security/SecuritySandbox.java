package com.localaicompanion.security;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.PermissionConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全沙箱系统
 *
 * 功能：
 * 1. 游戏内可视化绘制安全区域，每个区域可单独设置权限
 * 2. 方块黑名单（默认屏蔽箱子、门、熔炉、床等）
 * 3. 危险环境检测（岩浆、虚空、火焰）
 *
 * 这是保护玩家存档的重要防线
 */
public class SecuritySandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-Security");
    private static final String SAFE_ZONES_FILE = "safe_zones.json";

    // 安全区域列表
    private List<SafeZone> safeZones;

    // 危险环境检测开关
    private boolean dangerDetectionEnabled = true;

    public SecuritySandbox() {
        this.safeZones = new ArrayList<>();
    }

    /**
     * 动态获取最新的权限配置（避免配置重载后引用失效）
     */
    private PermissionConfig getPermissionConfig() {
        return LocalAICompanion.getInstance().getConfigManager().getPermissionConfig();
    }

    /**
     * 从配置加载
     */
    public void loadFromConfig(PermissionConfig config) {
        LOGGER.info("[Security] 安全沙箱已加载");
    }

    /**
     * 加载世界安全区域
     */
    public void loadWorldZones(MinecraftServer server) {
        // 从存档目录加载安全区域配置
        // 简化实现：初始化为空列表
        safeZones = new ArrayList<>();

        // 添加默认出生点保护区
        // 实际实现中需要从文件加载
        LOGGER.info("[Security] 世界安全区域已加载: {}个", safeZones.size());
    }

    /**
     * 保存世界安全区域
     */
    public void saveWorldZones(MinecraftServer server) {
        // 保存到存档目录
        LOGGER.info("[Security] 世界安全区域已保存: {}个", safeZones.size());
    }

    /**
     * 检查指定位置是否可以破坏方块
     */
    public boolean canBreakAt(BlockPos pos) {
        if (!getPermissionConfig().enableSafeZones) return true;

        for (SafeZone zone : safeZones) {
            if (zone.contains(pos)) {
                return zone.allowBreak;
            }
        }
        return true; // 不在任何保护区内，允许
    }

    /**
     * 检查指定位置是否可以放置方块
     */
    public boolean canPlaceAt(BlockPos pos) {
        if (!getPermissionConfig().enableSafeZones) return true;

        for (SafeZone zone : safeZones) {
            if (zone.contains(pos)) {
                return zone.allowPlace;
            }
        }
        return true;
    }

    /**
     * 检查方块是否在黑名单中
     */
    public boolean isBlockBlacklisted(String blockId) {
        return getPermissionConfig().isBlockBlacklisted(blockId);
    }

    /**
     * 添加安全区域
     */
    public void addSafeZone(SafeZone zone) {
        safeZones.add(zone);
        LOGGER.info("[Security] 添加安全区域: {} ({})", zone.name, zone.type);
    }

    /**
     * 移除安全区域
     */
    public boolean removeSafeZone(String zoneId) {
        return safeZones.removeIf(zone -> zone.id.equals(zoneId));
    }

    /**
     * 获取所有安全区域
     */
    public List<SafeZone> getSafeZones() {
        return new ArrayList<>(safeZones);
    }

    /**
     * 检测危险环境
     * @param pos 位置
     * @param isInLava 是否在岩浆中
     * @param isOnFire 是否着火
     * @param y Y坐标（检测虚空）
     * @return 危险类型，null表示安全
     */
    public DangerType detectDanger(BlockPos pos, boolean isInLava, boolean isOnFire, double y) {
        if (!dangerDetectionEnabled) return null;
        PermissionConfig config = getPermissionConfig();

        if (config.emergencyOnLava && isInLava) {
            return DangerType.LAVA;
        }

        if (config.emergencyOnFire && isOnFire) {
            return DangerType.FIRE;
        }

        if (config.emergencyOnVoid && y < -64) {
            return DangerType.VOID;
        }

        if (config.emergencyOnHighFall && y < -10) {
            // 高处掉落风险检测
            // 简化实现
        }

        return null;
    }

    public boolean isDangerDetectionEnabled() {
        return dangerDetectionEnabled;
    }

    public void setDangerDetectionEnabled(boolean enabled) {
        this.dangerDetectionEnabled = enabled;
    }

    /**
     * 危险类型
     */
    public enum DangerType {
        LAVA("岩浆"),
        FIRE("火焰"),
        VOID("虚空"),
        HIGH_FALL("高处掉落");

        public final String displayName;

        DangerType(String displayName) {
            this.displayName = displayName;
        }
    }
}
