package com.localaicompanion.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.ThreadExecutor;

/**
 * 线程工具类
 * 提供线程安全的操作辅助方法
 */
public class ThreadUtils {
    /**
     * 在服务器主线程执行任务
     * 确保游戏世界操作都在主线程执行
     */
    public static void runOnServerThread(MinecraftServer server, Runnable task) {
        if (server == null) return;

        if (server.isOnThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    /**
     * 检查当前是否在服务器主线程
     */
    public static boolean isServerThread(MinecraftServer server) {
        return server != null && server.isOnThread();
    }

    /**
     * 安全的睡眠（忽略中断）
     */
    public static void safeSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 创建守护线程
     */
    public static Thread createDaemonThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
}
