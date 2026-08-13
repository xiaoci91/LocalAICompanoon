package com.localaicompanion.llm;

import com.localaicompanion.config.LLMConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.*;

/**
 * LLM服务自动探测器
 * 自动扫描本机常用的本地LLM服务端口，自动填充API地址
 *
 * 支持探测的服务：
 * - Ollama (11434)
 * - LM Studio (1234)
 * - llama.cpp (8080)
 * - OpenAI兼容服务 (8000, 5000, 3000)
 * - Text Generation WebUI (7860, 5001)
 */
public class LLMServiceDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-Detector");

    // 待探测的端口列表
    private static final int[] COMMON_PORTS = {
        11434,  // Ollama 默认端口
        1234,   // LM Studio 默认端口
        8080,   // llama.cpp 默认端口
        8000,   // OpenAI兼容服务常见端口
        5000,   // 常见API端口
        3000,   // 常见API端口
        7860,   // Text Generation WebUI
        5001,   // Text Generation WebUI API
        8888,   // Jupyter/API常见端口
        9000,   // 常见API端口
    };

    private final OkHttpClient httpClient;
    private final ExecutorService detectionExecutor;

    private volatile boolean isDetecting = false;
    private volatile DetectionResult lastResult;

    public LLMServiceDetector() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build();

        this.detectionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-detector-thread");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 开始自动探测
     * @param config LLM配置（探测成功后会更新配置）
     */
    public void startAutoDetect(LLMConfig config) {
        if (isDetecting) {
            LOGGER.warn("[Detector] 已有探测任务在运行");
            return;
        }

        isDetecting = true;
        LOGGER.info("[Detector] 开始自动探测本地LLM服务...");

        detectionExecutor.submit(() -> {
            try {
                DetectionResult result = detectAll();
                lastResult = result;

                if (result != null && result.isFound()) {
                    LOGGER.info("[Detector] 探测到LLM服务: {} at {}", result.serviceType, result.baseUrl);

                    // 更新配置
                    config.apiBaseUrl = result.baseUrl;
                    config.apiType = result.serviceType.name();
                    config.apiEndpoint = getEndpointForType(result.serviceType);

                    LOGGER.info("[Detector] 已自动配置API地址: {}", config.getFullApiUrl());
                } else {
                    LOGGER.info("[Detector] 未探测到本地LLM服务，请手动配置API地址");
                }
            } catch (Exception e) {
                LOGGER.error("[Detector] 探测过程异常", e);
            } finally {
                isDetecting = false;
            }
        });
    }

    /**
     * 探测所有端口
     */
    private DetectionResult detectAll() {
        for (int port : COMMON_PORTS) {
            try {
                // 先快速检测端口是否开放
                if (!isPortOpen("localhost", port, 1000)) {
                    continue;
                }

                LOGGER.debug("[Detector] 端口 {} 开放，正在识别服务类型...", port);

                // 尝试识别服务类型
                String baseUrl = "http://localhost:" + port;
                ServiceType type = identifyService(baseUrl);

                if (type != null) {
                    DetectionResult result = new DetectionResult();
                    result.found = true;
                    result.baseUrl = baseUrl;
                    result.serviceType = type;
                    result.port = port;
                    return result;
                }

            } catch (Exception e) {
                LOGGER.debug("[Detector] 端口 {} 探测失败: {}", port, e.getMessage());
            }
        }

        return null;
    }

    /**
     * 检测端口是否开放
     */
    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 识别服务类型
     */
    private ServiceType identifyService(String baseUrl) {
        // 尝试 Ollama API
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/api/tags")
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (body.contains("models") || body.contains("name")) {
                        return ServiceType.OLLAMA;
                    }
                }
            }
        } catch (Exception e) {
            // 不是Ollama，继续尝试
        }

        // 尝试 OpenAI 兼容 API
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/v1/models")
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (body.contains("data") || body.contains("id")) {
                        // 进一步区分 LM Studio 和其他
                        String serverHeader = response.header("Server", "");
                        if (serverHeader.contains("LM Studio") || baseUrl.contains(":1234")) {
                            return ServiceType.LM_STUDIO;
                        }
                        return ServiceType.OPENAI_COMPATIBLE;
                    }
                }
            }
        } catch (Exception e) {
            // 继续尝试
        }

        // 尝试 llama.cpp
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/health")
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return ServiceType.LLAMA_CPP;
                }
            }
        } catch (Exception e) {
            // 继续尝试
        }

        // 尝试 llama.cpp completions
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/completion")
                .head()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() == 405 || response.isSuccessful()) {
                    return ServiceType.LLAMA_CPP;
                }
            }
        } catch (Exception e) {
            // 不是
        }

        return null;
    }

    /**
     * 获取对应服务类型的API端点
     */
    private String getEndpointForType(ServiceType type) {
        switch (type) {
            case OLLAMA:
                return "/api/generate";
            case OPENAI_COMPATIBLE:
            case LM_STUDIO:
                return "/v1/chat/completions";
            case LLAMA_CPP:
                return "/completion";
            default:
                return "/v1/chat/completions";
        }
    }

    public boolean isDetecting() {
        return isDetecting;
    }

    public DetectionResult getLastResult() {
        return lastResult;
    }

    public void shutdown() {
        detectionExecutor.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    /**
     * 服务类型枚举
     */
    public enum ServiceType {
        OLLAMA,
        LM_STUDIO,
        OPENAI_COMPATIBLE,
        LLAMA_CPP
    }

    /**
     * 探测结果
     */
    public static class DetectionResult {
        public boolean found;
        public String baseUrl;
        public ServiceType serviceType;
        public int port;

        public boolean isFound() {
            return found;
        }
    }
}
