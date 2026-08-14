package com.localaicompanion.llm;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.LLMConfig;
import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第4层：LLM网络客户端层
 *
 * 负责和本地Ollama/LM-Studio等大模型服务通讯
 * 全部网络请求在后台异步线程执行，不阻塞游戏主线程
 *
 * 核心特性：
 * - 异步请求，不阻塞游戏主线程
 * - 请求限流（闲聊/任务两套间隔）
 * - 游戏暂停时暂停LLM请求
 * - 同一时间最多1个正在运行的请求
 * - 完整的异常容错处理
 * - OOM识别与提示
 */
public class LLMClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-LLM");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService requestExecutor;
    private final ScheduledExecutorService retryScheduler;

    private final RateLimiter chatRateLimiter;
    private final RateLimiter taskRateLimiter;

    // 从ConfigManager动态获取最新配置，避免服务器重载配置后引用失效
    private LLMConfig getConfig() {
        return LocalAICompanion.getInstance().getConfigManager().getLLMConfig();
    }

    // 当前活跃请求数（始终<=1）
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    // 当前正在执行的请求（用于取消）
    private volatile Call currentCall;

    // OOM检测关键词
    private static final String[] OOM_KEYWORDS = {
        "out of memory", "OOM", "显存不足", "CUDA out of memory",
        "not enough memory", "insufficient memory", "内存不足"
    };

    public LLMClient(LLMConfig config) {
        this.objectMapper = new ObjectMapper();

        // 配置HTTP客户端
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(config.requestTimeout, TimeUnit.SECONDS)
            .readTimeout(config.requestTimeout, TimeUnit.SECONDS)
            .writeTimeout(config.requestTimeout, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // 手动控制重试
            .build();

        // 单线程请求执行器（保证同一时间只有一个请求）
        this.requestExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-request-thread");
            t.setDaemon(true);
            return t;
        });

        // 重试调度器
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-retry-thread");
            t.setDaemon(true);
            return t;
        });

        // 初始化限流器
        this.chatRateLimiter = new RateLimiter(config.chatRequestInterval);
        this.taskRateLimiter = new RateLimiter(config.taskRequestInterval);
    }

    /**
     * 发送聊天请求（异步）
     * @param userMessage 用户消息
     * @param context 对话上下文
     * @param callback 回调函数
     */
    public void sendChatRequest(String userMessage, String context, LLMCallback callback) {
        if (isPaused.get()) {
            callback.onError("游戏已暂停，LLM请求已暂停");
            return;
        }

        if (!chatRateLimiter.tryAcquire()) {
            callback.onError("请求过于频繁，请稍后再试");
            return;
        }

        if (activeRequests.get() >= getConfig().maxConcurrentRequests) {
            callback.onError("已有请求正在处理中，请稍候");
            return;
        }

        submitRequest(userMessage, context, RequestType.CHAT, callback);
    }

    /**
     * 发送任务请求（异步）
     * 任务请求使用独立的限流策略
     */
    public void sendTaskRequest(String userMessage, String context, LLMCallback callback) {
        if (isPaused.get()) {
            callback.onError("游戏已暂停，LLM请求已暂停");
            return;
        }

        if (!taskRateLimiter.tryAcquire()) {
            callback.onError("任务请求过于频繁");
            return;
        }

        if (activeRequests.get() >= getConfig().maxConcurrentRequests) {
            callback.onError("已有请求正在处理中，请稍候");
            return;
        }

        submitRequest(userMessage, context, RequestType.TASK, callback);
    }

    /**
     * 提交请求到后台线程
     */
    private void submitRequest(String userMessage, String context, RequestType type, LLMCallback callback) {
        activeRequests.incrementAndGet();

        requestExecutor.submit(() -> {
            try {
                LLMResponse response = executeRequestWithRetry(userMessage, context, type);
                if (response.isSuccess()) {
                    isConnected.set(true);
                    callback.onSuccess(response);
                } else {
                    callback.onError(response.getErrorMessage());
                }
            } catch (Exception e) {
                LOGGER.error("[LLM] 请求执行异常", e);
                callback.onError("请求异常: " + e.getMessage());
            } finally {
                activeRequests.decrementAndGet();
                currentCall = null;
            }
        });
    }

    /**
     * 执行请求（带重试）
     */
    private LLMResponse executeRequestWithRetry(String userMessage, String context, RequestType type) {
        LLMConfig config = getConfig();
        int retryCount = 0;
        LLMResponse lastResponse = null;

        while (retryCount <= config.maxRetries) {
            try {
                LLMResponse response = executeRequest(userMessage, context, type);

                if (response.isSuccess()) {
                    return response;
                }

                // 检查是否是OOM错误
                if (isOOMError(response.getErrorMessage())) {
                    response.setOomError(true);
                    return response; // OOM错误不重试
                }

                lastResponse = response;
                retryCount++;

                if (retryCount <= config.maxRetries) {
                    LOGGER.warn("[LLM] 请求失败，第{}次重试: {}", retryCount, response.getErrorMessage());
                    Thread.sleep(config.retryInterval);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LLMResponse.error("请求被中断");
            } catch (Exception e) {
                lastResponse = LLMResponse.error("网络异常: " + e.getMessage());
                retryCount++;

                if (retryCount <= config.maxRetries) {
                    try {
                        Thread.sleep(config.retryInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return LLMResponse.error("请求被中断");
                    }
                }
            }
        }

        isConnected.set(false);
        return lastResponse != null ? lastResponse : LLMResponse.error("未知错误");
    }

    /**
     * 执行单次HTTP请求
     */
    private LLMResponse executeRequest(String userMessage, String context, RequestType type) throws IOException {
        LLMConfig config = getConfig();
        String url = config.getFullApiUrl();
        String requestBody = buildRequestBody(userMessage, context, type);

        Request.Builder requestBuilder = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, requestBody));

        // 添加API密钥（如果有）
        if (config.apiKey != null && !config.apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + config.apiKey);
        }

        Request request = requestBuilder.build();
        currentCall = httpClient.newCall(request);

        try (Response response = currentCall.execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                return LLMResponse.error("HTTP " + response.code() + ": " + errorBody);
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            return parseResponse(responseBody, type);
        }
    }

    /**
     * 构建请求体（根据API类型）
     */
    private String buildRequestBody(String userMessage, String context, RequestType type) {
        try {
            LLMConfig config = getConfig();
            ObjectNode root = objectMapper.createObjectNode();
            LLMConfig.ApiType apiType = config.getApiTypeEnum();

            switch (apiType) {
                case OLLAMA:
                    root.put("model", config.modelName);
                    root.put("prompt", buildPrompt(userMessage, context, type));
                    root.put("system", config.systemPrompt);
                    root.put("temperature", config.temperature);
                    root.put("max_tokens", config.maxTokens);
                    root.put("top_p", config.topP);
                    root.put("stream", false);
                    break;

                case OPENAI_COMPATIBLE:
                case LM_STUDIO:
                    ArrayNode messagesArray = objectMapper.createArrayNode();

                    // 系统消息
                    ObjectNode systemMsg = objectMapper.createObjectNode();
                    systemMsg.put("role", "system");
                    systemMsg.put("content", config.systemPrompt);
                    messagesArray.add(systemMsg);

                    // 上下文消息
                    if (context != null && !context.isEmpty()) {
                        ObjectNode contextMsg = objectMapper.createObjectNode();
                        contextMsg.put("role", "user");
                        contextMsg.put("content", context);
                        messagesArray.add(contextMsg);
                    }

                    // 用户消息
                    ObjectNode userMsg = objectMapper.createObjectNode();
                    userMsg.put("role", "user");
                    userMsg.put("content", userMessage);
                    messagesArray.add(userMsg);

                    root.set("messages", messagesArray);
                    root.put("model", config.modelName);
                    root.put("temperature", config.temperature);
                    root.put("max_tokens", config.maxTokens);
                    root.put("top_p", config.topP);
                    root.put("frequency_penalty", config.frequencyPenalty);
                    root.put("presence_penalty", config.presencePenalty);
                    break;

                case LLAMA_CPP:
                    root.put("prompt", buildPrompt(userMessage, context, type));
                    root.put("temperature", config.temperature);
                    root.put("n_predict", config.maxTokens);
                    root.put("top_p", config.topP);
                    root.put("stream", false);
                    break;
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            LOGGER.error("[LLM] 构建请求体失败", e);
            return "{}";
        }
    }

    /**
     * 构建prompt（用于非chat格式的API）
     */
    private String buildPrompt(String userMessage, String context, RequestType type) {
        LLMConfig config = getConfig();
        StringBuilder sb = new StringBuilder();

        // 系统提示词
        if (config.systemPrompt != null && !config.systemPrompt.isEmpty()) {
            sb.append("系统: ").append(config.systemPrompt).append("\n\n");
        }

        // 上下文
        if (context != null && !context.isEmpty()) {
            sb.append(context).append("\n\n");
        }

        // 用户消息
        sb.append("玩家: ").append(userMessage).append("\n");
        sb.append("AI同伴: ");

        return sb.toString();
    }

    /**
     * 解析响应
     */
    private LLMResponse parseResponse(String responseBody, RequestType type) {
        try {
            LLMConfig config = getConfig();
            JsonNode root = objectMapper.readTree(responseBody);
            String content = "";
            LLMConfig.ApiType apiType = config.getApiTypeEnum();

            switch (apiType) {
                case OLLAMA:
                    content = root.path("response").asText("");
                    break;

                case OPENAI_COMPATIBLE:
                case LM_STUDIO:
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        content = choices.get(0).path("message").path("content").asText("");
                    }
                    break;

                case LLAMA_CPP:
                    content = root.path("content").asText("");
                    break;
            }

            if (content.isEmpty()) {
                return LLMResponse.error("响应内容为空");
            }

            return LLMResponse.success(content.trim());

        } catch (Exception e) {
            LOGGER.error("[LLM] 解析响应失败: {}", responseBody, e);
            return LLMResponse.error("响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 检测是否为OOM错误
     */
    private boolean isOOMError(String errorMessage) {
        if (errorMessage == null) return false;
        String lower = errorMessage.toLowerCase();
        for (String keyword : OOM_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 暂停LLM请求（游戏暂停时调用）
     */
    public void pause() {
        isPaused.set(true);
        if (currentCall != null) {
            currentCall.cancel();
        }
        LOGGER.info("[LLM] 请求已暂停");
    }

    /**
     * 恢复LLM请求
     */
    public void resume() {
        isPaused.set(false);
        LOGGER.info("[LLM] 请求已恢复");
    }

    /**
     * 取消当前请求
     */
    public void cancelCurrentRequest() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
    }

    /**
     * 测试连接（真测试：发一个最小的生成请求，验证服务+模型都可用）
     * @return CompletableFuture<String> - null表示成功，非null表示错误信息
     */
    public CompletableFuture<String> testConnection() {
        CompletableFuture<String> future = new CompletableFuture<>();

        requestExecutor.submit(() -> {
            try {
                LLMConfig config = getConfig();
                String url = config.getFullApiUrl();

                // 构建一个最小的测试请求（max_tokens=1，只测能不能通）
                String testBody;
                switch (config.getApiTypeEnum()) {
                    case OLLAMA:
                        testBody = String.format(
                            "{\"model\":\"%s\",\"prompt\":\"hi\",\"max_tokens\":1,\"stream\":false}",
                            config.modelName
                        );
                        break;
                    case OPENAI_COMPATIBLE:
                    case LM_STUDIO:
                        testBody = String.format(
                            "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}",
                            config.modelName
                        );
                        break;
                    case LLAMA_CPP:
                        testBody = "{\"prompt\":\"hi\",\"n_predict\":1,\"stream\":false}";
                        break;
                    default:
                        testBody = "{}";
                }

                Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, testBody));

                if (config.apiKey != null && !config.apiKey.isEmpty()) {
                    requestBuilder.header("Authorization", "Bearer " + config.apiKey);
                }

                Request request = requestBuilder.build();

                try (Response response = httpClient.newCall(request).execute()) {
                    boolean success = response.isSuccessful();
                    isConnected.set(success);
                    if (success) {
                        future.complete(null);
                    } else {
                        String body = response.body() != null ? response.body().string() : "";
                        future.complete("HTTP " + response.code() + ": " + body);
                    }
                }
            } catch (Exception e) {
                isConnected.set(false);
                future.complete(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        return future;
    }

    /**
     * 关闭客户端
     */
    public void shutdown() {
        cancelCurrentRequest();
        requestExecutor.shutdownNow();
        retryScheduler.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    /**
     * 请求类型枚举
     */
    public enum RequestType {
        CHAT,   // 闲聊请求
        TASK    // 任务请求
    }

    /**
     * 异步回调接口
     */
    public interface LLMCallback {
        void onSuccess(LLMResponse response);
        void onError(String errorMessage);
    }
}
