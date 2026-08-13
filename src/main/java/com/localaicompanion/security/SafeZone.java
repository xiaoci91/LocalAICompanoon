package com.localaicompanion.security;

import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * 安全区域
 * 每个区域可以单独设置是否允许破坏、是否允许放置方块
 */
public class SafeZone {
    public String id;
    public String name;
    public ZoneType type;

    // 矩形区域
    public BlockPos minPos;
    public BlockPos maxPos;

    // 圆形区域
    public BlockPos centerPos;
    public int radius;

    // 权限设置
    public boolean allowBreak;
    public boolean allowPlace;
    public boolean allowOpenContainers;

    // 显示颜色（RGB）
    public int color;

    // 是否可见
    public boolean visible;

    public SafeZone() {
        this.id = UUID.randomUUID().toString();
        this.name = "新安全区";
        this.type = ZoneType.RECTANGLE;
        this.allowBreak = false;
        this.allowPlace = false;
        this.allowOpenContainers = false;
        this.color = 0x00FF00; // 绿色
        this.visible = true;
    }

    /**
     * 检查位置是否在区域内
     */
    public boolean contains(BlockPos pos) {
        if (pos == null) return false;

        switch (type) {
            case RECTANGLE:
                return containsRect(pos);
            case CYLINDER:
                return containsCylinder(pos);
            case SPHERE:
                return containsSphere(pos);
            default:
                return false;
        }
    }

    private boolean containsRect(BlockPos pos) {
        if (minPos == null || maxPos == null) return false;
        return pos.getX() >= minPos.getX() && pos.getX() <= maxPos.getX()
            && pos.getY() >= minPos.getY() && pos.getY() <= maxPos.getY()
            && pos.getZ() >= minPos.getZ() && pos.getZ() <= maxPos.getZ();
    }

    private boolean containsCylinder(BlockPos pos) {
        if (centerPos == null) return false;
        double dx = pos.getX() - centerPos.getX();
        double dz = pos.getZ() - centerPos.getZ();
        double distSq = dx * dx + dz * dz;
        return distSq <= radius * radius
            && pos.getY() >= minPos.getY()
            && pos.getY() <= maxPos.getY();
    }

    private boolean containsSphere(BlockPos pos) {
        if (centerPos == null) return false;
        double dx = pos.getX() - centerPos.getX();
        double dy = pos.getY() - centerPos.getY();
        double dz = pos.getZ() - centerPos.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        return distSq <= radius * radius;
    }

    /**
     * 区域类型
     */
    public enum ZoneType {
        RECTANGLE("矩形"),
        CYLINDER("圆柱"),
        SPHERE("球形");

        public final String displayName;

        ZoneType(String displayName) {
            this.displayName = displayName;
        }
    }
}
