package com.localaicompanion.task.pathing;

import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 寻路服务抽象接口
 *
 * 这是Baritone和模组之间的隔离层
 * 所有寻路移动请求全部走这层接口，底层库被隔离在内层
 *
 * 完全自主编写的抽象层，不抄任何现有项目的调用逻辑
 * 未来可以替换为其他寻路库而不影响上层代码
 */
public interface PathingService {

    /**
     * 初始化寻路服务
     * @param entity 寻路的实体
     */
    void initialize(LivingEntity entity);

    /**
     * 移动到指定位置
     * @param targetPos 目标位置
     * @return 异步结果，完成时返回true
     */
    CompletableFuture<PathResult> moveTo(BlockPos targetPos);

    /**
     * 移动到指定位置并破坏目标方块（采集）
     * @param targetPos 目标方块位置
     * @return 异步结果
     */
    CompletableFuture<PathResult> moveToAndBreak(BlockPos targetPos);

    /**
     * 移动到指定位置并放置方块
     * @param targetPos 放置位置
     * @param blockName 方块名称
     * @return 异步结果
     */
    CompletableFuture<PathResult> moveToAndPlace(BlockPos targetPos, String blockName);

    /**
     * 跟随目标实体
     * @param target 目标实体
     * @param followDistance 跟随距离
     */
    void followEntity(LivingEntity target, float followDistance);

    /**
     * 攻击目标实体
     * @param target 目标实体
     * @return 异步结果
     */
    CompletableFuture<PathResult> attackEntity(LivingEntity target);

    /**
     * 搜索并采集指定类型的方块
     * @param blockName 方块名称（如 iron_ore）
     * @param amount 数量
     * @param searchRadius 搜索半径
     * @return 异步结果
     */
    CompletableFuture<PathResult> mineBlock(String blockName, int amount, int searchRadius);

    /**
     * 停止当前所有寻路活动
     */
    void stopAll();

    /**
     * 暂停寻路
     */
    void pause();

    /**
     * 恢复寻路
     */
    void resume();

    /**
     * 是否正在执行寻路任务
     */
    boolean isActive();

    /**
     * 获取当前目标位置
     */
    BlockPos getCurrentTarget();

    /**
     * 获取当前进度 (0.0 - 1.0)
     */
    float getProgress();

    /**
     * 清理资源
     */
    void cleanup();

    /**
     * 寻路结果
     */
    class PathResult {
        public final boolean success;
        public final String message;
        public final BlockPos finalPos;
        public final int blocksMined;
        public final long timeTakenMs;

        public PathResult(boolean success, String message, BlockPos finalPos, int blocksMined, long timeTakenMs) {
            this.success = success;
            this.message = message;
            this.finalPos = finalPos;
            this.blocksMined = blocksMined;
            this.timeTakenMs = timeTakenMs;
        }

        public static PathResult success(BlockPos finalPos) {
            return new PathResult(true, "到达目标", finalPos, 0, 0);
        }

        public static PathResult successMined(BlockPos finalPos, int count) {
            return new PathResult(true, "采集完成", finalPos, count, 0);
        }

        public static PathResult failure(String message) {
            return new PathResult(false, message, null, 0, 0);
        }

        public static PathResult cancelled() {
            return new PathResult(false, "已取消", null, 0, 0);
        }
    }
}
