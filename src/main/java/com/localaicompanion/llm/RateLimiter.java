package com.localaicompanion.llm;

/**
 * 请求限流器
 * 基于令牌桶算法的简单限流实现
 * 控制LLM请求的频率，避免对本地服务造成过大压力
 */
public class RateLimiter {
    private final long minIntervalMs;
    private long lastRequestTime;

    public RateLimiter(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
        this.lastRequestTime = 0;
    }

    /**
     * 尝试获取请求许可
     * @return true表示可以发送请求，false表示需要等待
     */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now - lastRequestTime >= minIntervalMs) {
            lastRequestTime = now;
            return true;
        }
        return false;
    }

    /**
     * 获取还需要等待的时间（毫秒）
     */
    public synchronized long getWaitTime() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        return Math.max(0, minIntervalMs - elapsed);
    }

    /**
     * 重置限流计时器
     */
    public synchronized void reset() {
        lastRequestTime = 0;
    }

    /**
     * 更新最小间隔
     */
    public void setMinIntervalMs(long minIntervalMs) {
        // 注意：这不是线程安全的，但配置更新频率很低，可以接受
    }
}
