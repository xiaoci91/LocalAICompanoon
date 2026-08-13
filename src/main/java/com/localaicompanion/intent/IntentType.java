package com.localaicompanion.intent;

/**
 * 意图类型枚举
 * 所有大模型输出的任务意图必须映射到这些标准类型
 * 大模型不能直接下发底层操作指令，只能输出这些高层意图
 */
public enum IntentType {
    // ===== 聊天类（不触发游戏行为）=====
    CHAT("chat", "普通聊天", false, false),

    // ===== 移动类 =====
    FOLLOW("follow", "跟随玩家", false, false),
    GOTO("goto", "前往指定位置", false, false),
    STAY("stay", "原地待命", false, false),
    COME_HERE("come_here", "到我这里来", false, false),
    EXPLORE("explore", "探索周围", false, false),

    // ===== 采集类（需要破坏方块权限）=====
    COLLECT("collect", "采集物品", true, false),
    MINE("mine", "挖掘方块", true, false),
    CHOP_WOOD("chop_wood", "砍树", true, false),
    DIG("dig", "挖土", true, false),

    // ===== 建造类（需要放置方块权限）=====
    BUILD("build", "建造结构", false, true),
    PLACE("place", "放置方块", false, true),
    TORCH("torch", "插火把", false, true),

    // ===== 战斗类 =====
    ATTACK_MOB("attack_mob", "攻击怪物", false, false),
    DEFEND("defend", "防御/自卫", false, false),
    HUNT("hunt", "狩猎动物", false, false),

    // ===== 物品交互类 =====
    PICKUP("pickup", "拾取物品", false, false),
    OPEN_CONTAINER("open_container", "开启容器", false, false),
    CRAFT("craft", "合成物品", false, false),
    SMELT("smelt", "烧炼物品", false, false),

    // ===== 状态类 =====
    STATUS("status", "报告状态", false, false),
    INVENTORY("inventory", "查看背包", false, false),
    HEALTH("health", "报告血量", false, false),

    // ===== 系统类 =====
    STOP("stop", "停止当前任务", false, false),
    CANCEL("cancel", "取消任务", false, false),
    RETURN("return", "返回玩家身边", false, false),
    HELP("help", "帮助信息", false, false),

    // ===== 未知/无法识别 =====
    UNKNOWN("unknown", "无法识别", false, false);

    private final String code;
    private final String displayName;
    private final boolean requiresBreakPermission;
    private final boolean requiresPlacePermission;

    IntentType(String code, String displayName, boolean requiresBreakPermission, boolean requiresPlacePermission) {
        this.code = code;
        this.displayName = displayName;
        this.requiresBreakPermission = requiresBreakPermission;
        this.requiresPlacePermission = requiresPlacePermission;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean requiresBreakPermission() {
        return requiresBreakPermission;
    }

    public boolean requiresPlacePermission() {
        return requiresPlacePermission;
    }

    /**
     * 根据code获取意图类型
     */
    public static IntentType fromCode(String code) {
        if (code == null) return UNKNOWN;
        for (IntentType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * 判断是否为聊天类意图（不触发游戏行为）
     */
    public boolean isChatOnly() {
        return this == CHAT || this == STATUS || this == INVENTORY || this == HEALTH || this == HELP;
    }

    /**
     * 判断是否为世界修改类意图
     */
    public boolean isWorldModifying() {
        return requiresBreakPermission || requiresPlacePermission;
    }
}
