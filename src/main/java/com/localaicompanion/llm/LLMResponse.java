package com.localaicompanion.llm;

/**
 * LLM响应封装类
 * 统一处理成功/失败/OOM等各种响应状态
 */
public class LLMResponse {
    private final boolean success;
    private final String content;
    private final String errorMessage;
    private boolean isOomError;
    private long responseTimeMs;
    private int tokenUsage;

    private LLMResponse(boolean success, String content, String errorMessage) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
        this.isOomError = false;
        this.responseTimeMs = 0;
        this.tokenUsage = 0;
    }

    public static LLMResponse success(String content) {
        return new LLMResponse(true, content, null);
    }

    public static LLMResponse error(String errorMessage) {
        return new LLMResponse(false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isOomError() {
        return isOomError;
    }

    public void setOomError(boolean oomError) {
        isOomError = oomError;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    public int getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(int tokenUsage) {
        this.tokenUsage = tokenUsage;
    }
}
