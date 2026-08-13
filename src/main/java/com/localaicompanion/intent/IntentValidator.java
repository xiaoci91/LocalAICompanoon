package com.localaicompanion.intent;

import com.localaicompanion.config.PermissionConfig;
import com.localaicompanion.config.MainConfig;
import com.localaicompanion.security.SecuritySandbox;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 意图安全校验器
 * 负责对解析后的任务进行安全校验
 *
 * 校验顺序：
 * 1. 运行模式校验（角色扮演模式下禁止所有世界修改）
 * 2. 权限开关校验（玩家开启的权限）
 * 3. 方块黑名单校验
 * 4. 安全区域校验
 * 5. 服务器类型校验（非本地服务器限制功能）
 *
 * 任何一项不通过，任务直接拦截，只返回聊天消息
 * 这是大模型和游戏世界之间的第二道隔离墙
 */
public class IntentValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-IntentValidator");

    private final PermissionConfig permissionConfig;
    private final MainConfig mainConfig;
    private final SecuritySandbox securitySandbox;

    // 服务器是否为本地/局域网
    private boolean isLocalServer = true;

    public IntentValidator(PermissionConfig permissionConfig, MainConfig mainConfig,
                           SecuritySandbox securitySandbox) {
        this.permissionConfig = permissionConfig;
        this.mainConfig = mainConfig;
        this.securitySandbox = securitySandbox;
    }

    /**
     * 校验任务是否可以执行
     * @param task 待校验的任务
     * @param player 发起任务的玩家（用于位置判断）
     * @param targetPos 目标位置（如果有）
     * @return 校验结果
     */
    public ValidationResult validate(StandardTask task, ServerPlayerEntity player, BlockPos targetPos) {
        // 第1关：运行模式校验
        ValidationResult modeCheck = checkRunMode(task);
        if (!modeCheck.isAllowed()) {
            return modeCheck;
        }

        // 第2关：服务器类型校验
        ValidationResult serverCheck = checkServerType(task);
        if (!serverCheck.isAllowed()) {
            return serverCheck;
        }

        // 第3关：权限开关校验
        ValidationResult permissionCheck = checkPermissions(task);
        if (!permissionCheck.isAllowed()) {
            return permissionCheck;
        }

        // 第4关：方块黑名单校验（如果涉及特定方块）
        ValidationResult blacklistCheck = checkBlockBlacklist(task);
        if (!blacklistCheck.isAllowed()) {
            return blacklistCheck;
        }

        // 第5关：安全区域校验（如果有目标位置）
        if (targetPos != null) {
            ValidationResult zoneCheck = checkSafeZones(task, targetPos);
            if (!zoneCheck.isAllowed()) {
                return zoneCheck;
            }
        }

        // 全部通过
        return ValidationResult.allowed();
    }

    /**
     * 运行模式校验
     * 角色扮演聊天模式下，全部修改世界的功能强制锁死
     */
    private ValidationResult checkRunMode(StandardTask task) {
        MainConfig.RunMode mode = mainConfig.getRunModeEnum();

        if (mode == MainConfig.RunMode.ROLEPLAY_CHAT) {
            // 角色扮演模式：只允许聊天、跟随、表情
            IntentType type = task.getIntentType();
            if (type.isWorldModifying() ||
                type == IntentType.COLLECT ||
                type == IntentType.MINE ||
                type == IntentType.CHOP_WOOD ||
                type == IntentType.DIG ||
                type == IntentType.BUILD ||
                type == IntentType.PLACE ||
                type == IntentType.ATTACK_MOB ||
                type == IntentType.HUNT ||
                type == IntentType.OPEN_CONTAINER ||
                type == IntentType.CRAFT ||
                type == IntentType.SMELT) {

                LOGGER.info("[Validator] 角色扮演模式，拦截世界修改任务: {}", type);
                return ValidationResult.denied(
                    "当前是聊天模式，我只能和你聊天和跟着你，不能做其他事情哦~",
                    "角色扮演模式限制"
                );
            }
        }

        return ValidationResult.allowed();
    }

    /**
     * 服务器类型校验
     * 非本地局域网服务器自动禁用世界修改功能
     */
    private ValidationResult checkServerType(StandardTask task) {
        if (!isLocalServer && permissionConfig.autoRestrictOnRemoteServer) {
            if (task.isWorldModifying() ||
                task.getIntentType() == IntentType.COLLECT ||
                task.getIntentType() == IntentType.MINE ||
                task.getIntentType() == IntentType.CHOP_WOOD ||
                task.getIntentType() == IntentType.ATTACK_MOB) {

                LOGGER.info("[Validator] 非本地服务器，拦截世界修改任务: {}", task.getIntentType());
                return ValidationResult.denied(
                    "在多人服务器上，我只能跟着你聊天哦~",
                    "服务器类型限制"
                );
            }
        }

        return ValidationResult.allowed();
    }

    /**
     * 权限开关校验
     */
    private ValidationResult checkPermissions(StandardTask task) {
        IntentType type = task.getIntentType();

        // 破坏方块权限
        if (type.requiresBreakPermission() && !permissionConfig.allowBreakBlocks) {
            // 特殊：采矿权限单独控制
            if (type == IntentType.COLLECT || type == IntentType.MINE) {
                if (!permissionConfig.allowMiningOres) {
                    return ValidationResult.denied(
                        "我没有被允许采集方块呢，主人可以在设置里打开权限~",
                        "缺少破坏方块权限"
                    );
                }
            } else {
                return ValidationResult.denied(
                    "我没有被允许破坏方块呢，主人可以在设置里打开权限~",
                    "缺少破坏方块权限"
                );
            }
        }

        // 放置方块权限
        if (type.requiresPlacePermission() && !permissionConfig.allowPlaceBlocks) {
            return ValidationResult.denied(
                "我没有被允许放置方块呢，主人可以在设置里打开权限~",
                "缺少放置方块权限"
            );
        }

        // 开启容器权限
        if (type == IntentType.OPEN_CONTAINER && !permissionConfig.allowOpenContainers) {
            return ValidationResult.denied(
                "我没有被允许开箱子呢，主人可以在设置里打开权限~",
                "缺少开启容器权限"
            );
        }

        // 攻击怪物权限
        if (type == IntentType.ATTACK_MOB && !permissionConfig.allowAttackMobs) {
            return ValidationResult.denied(
                "我不能攻击怪物呢，主人可以在设置里打开权限~",
                "缺少攻击怪物权限"
            );
        }

        // 攻击动物权限
        if (type == IntentType.HUNT && !permissionConfig.allowAttackAnimals) {
            return ValidationResult.denied(
                "我不能攻击动物呢，主人可以在设置里打开权限~",
                "缺少攻击动物权限"
            );
        }

        // 攻击玩家权限（绝对禁止，不显示开启选项）
        if (type == IntentType.ATTACK_MOB && task.getTarget() != null &&
            task.getTarget().contains("player")) {
            return ValidationResult.denied(
                "我不能攻击玩家！",
                "绝对禁止：攻击玩家"
            );
        }

        return ValidationResult.allowed();
    }

    /**
     * 方块黑名单校验
     */
    private ValidationResult checkBlockBlacklist(StandardTask task) {
        String target = task.getTarget();
        if (target == null) {
            return ValidationResult.allowed();
        }

        // 标准化方块ID
        String blockId = target.contains(":") ? target : "minecraft:" + target;

        if (permissionConfig.isBlockBlacklisted(blockId)) {
            LOGGER.info("[Validator] 方块在黑名单中: {}", blockId);
            return ValidationResult.denied(
                "这个方块我不能碰呢~",
                "方块黑名单: " + blockId
            );
        }

        return ValidationResult.allowed();
    }

    /**
     * 安全区域校验
     */
    private ValidationResult checkSafeZones(StandardTask task, BlockPos pos) {
        if (!permissionConfig.enableSafeZones) {
            return ValidationResult.allowed();
        }

        if (task.getIntentType().requiresBreakPermission()) {
            if (!securitySandbox.canBreakAt(pos)) {
                return ValidationResult.denied(
                    "这个地方是保护区，我不能破坏方块~",
                    "安全区域保护"
                );
            }
        }

        if (task.getIntentType().requiresPlacePermission()) {
            if (!securitySandbox.canPlaceAt(pos)) {
                return ValidationResult.denied(
                    "这个地方是保护区，我不能放方块~",
                    "安全区域保护"
                );
            }
        }

        return ValidationResult.allowed();
    }

    public void setLocalServer(boolean localServer) {
        isLocalServer = localServer;
    }

    public boolean isLocalServer() {
        return isLocalServer;
    }

    /**
     * 校验结果
     */
    public static class ValidationResult {
        private final boolean allowed;
        private final String denyMessage;
        private final String denyReason;

        private ValidationResult(boolean allowed, String denyMessage, String denyReason) {
            this.allowed = allowed;
            this.denyMessage = denyMessage;
            this.denyReason = denyReason;
        }

        public static ValidationResult allowed() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult denied(String message, String reason) {
            return new ValidationResult(false, message, reason);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getDenyMessage() {
            return denyMessage;
        }

        public String getDenyReason() {
            return denyReason;
        }
    }
}
