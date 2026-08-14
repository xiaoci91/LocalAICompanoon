package com.localaicompanion.tts;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.MainConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Edge TTS 语音服务客户端
 *
 * 对接本地 Edge TTS Python 服务
 * 服务默认端口 8880
 */
public class EdgeTTSService {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-TTS");

    private final OkHttpClient httpClient;
    private Clip currentClip;
    private volatile boolean playing = false;

    public EdgeTTSService() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 播放语音（异步，不阻塞游戏线程）
     */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;

        MainConfig config = LocalAICompanion.getInstance().getConfigManager().getMainConfig();
        if (!config.enableTTS) return;

        // 异步执行，不阻塞游戏线程
        new Thread(() -> {
            try {
                speakSync(text);
            } catch (Exception e) {
                LOGGER.warn("[TTS] 语音播放失败: {}", e.getMessage());
            }
        }, "TTS-Playback").start();
    }

    /**
     * 同步播放语音（内部方法）
     */
    private void speakSync(String text) throws Exception {
        MainConfig config = LocalAICompanion.getInstance().getConfigManager().getMainConfig();

        // 停止当前播放
        stop();

        // 构建请求
        String url = config.ttsServerUrl + "/v1/audio/speech";

        String json = "{" +
            "\"input\":\"" + escapeJson(text) + "\"," +
            "\"voice\":\"" + escapeJson(config.ttsVoice) + "\"," +
            "\"speed\":" + config.ttsSpeed + "," +
            "\"response_format\":\"wav\"" +
            "}";

        RequestBody body = RequestBody.create(
            MediaType.parse("application/json"),
            json
        );

        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();

        // 发送请求
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("响应体为空");
            }

            byte[] audioData = responseBody.bytes();
            playWav(audioData);
        }
    }

    /**
     * 播放 WAV 音频数据
     */
    private void playWav(byte[] audioData) throws Exception {
        InputStream inputStream = new ByteArrayInputStream(audioData);
        AudioInputStream audioStream = AudioSystem.getAudioInputStream(inputStream);

        currentClip = AudioSystem.getClip();
        currentClip.open(audioStream);
        playing = true;

        currentClip.addLineListener(event -> {
            if (event.getType() == LineEvent.Type.STOP) {
                playing = false;
                try {
                    currentClip.close();
                } catch (Exception e) {
                    // ignore
                }
                currentClip = null;
            }
        });

        currentClip.start();

        // 等待播放完成（最多等30秒）
        long startTime = System.currentTimeMillis();
        while (playing && System.currentTimeMillis() - startTime < 30000) {
            Thread.sleep(100);
        }
    }

    /**
     * 停止当前播放
     */
    public void stop() {
        playing = false;
        if (currentClip != null) {
            try {
                if (currentClip.isRunning()) {
                    currentClip.stop();
                }
                currentClip.close();
            } catch (Exception e) {
                // ignore
            }
            currentClip = null;
        }
    }

    /**
     * 测试连接（异步返回结果）
     */
    public CompletableFuture<String> testConnection() {
        CompletableFuture<String> future = new CompletableFuture<>();

        new Thread(() -> {
            try {
                MainConfig config = LocalAICompanion.getInstance().getConfigManager().getMainConfig();
                String url = config.ttsServerUrl + "/health";

                Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        future.complete(null); // null 表示成功
                    } else {
                        future.complete("HTTP " + response.code());
                    }
                }
            } catch (Exception e) {
                future.complete(e.getMessage());
            }
        }, "TTS-Test").start();

        return future;
    }

    /**
     * 检查是否正在播放
     */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        stop();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
