package com.localaicompanion.tts;

import com.localaicompanion.LocalAICompanion;
import com.localaicompanion.config.MainConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Edge TTS 语音服务客户端
 * 对接本地 Edge TTS Python 服务
 */
public class EdgeTTSService {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-TTS");

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ExecutorService audioExecutor;

    // 当前正在播放的音频（用于停止）
    private Clip currentClip;

    public EdgeTTSService() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

        // 单线程音频播放执行器
        this.audioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tts-audio-thread");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 获取最新配置
     */
    private MainConfig getConfig() {
        return LocalAICompanion.getInstance().getConfigManager().getMainConfig();
    }

    /**
     * 检查 TTS 是否启用
     */
    public boolean isEnabled() {
        return getConfig().enableTTS;
    }

    /**
     * 文本转语音并播放（异步）
     */
    public void speak(String text) {
        if (!isEnabled() || text == null || text.isEmpty()) {
            return;
        }

        audioExecutor.submit(() -> {
            try {
                byte[] audioData = synthesize(text);
                if (audioData != null) {
                    playAudio(audioData);
                }
            } catch (Exception e) {
                LOGGER.error("[TTS] 语音播放失败", e);
            }
        });
    }

    /**
     * 文本转语音，返回 WAV 音频数据
     */
    public byte[] synthesize(String text) throws IOException {
        MainConfig config = getConfig();

        String url = config.ttsServerUrl + "/v1/audio/speech";

        // 构建请求体
        String json = String.format(
            "{\"input\":\"%s\",\"voice\":\"%s\",\"speed\":%.2f,\"response_format\":\"wav\"}",
            escapeJson(text),
            config.ttsVoice,
            config.ttsSpeed
        );

        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, json))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                LOGGER.error("[TTS] 合成失败: HTTP {} - {}", response.code(), errorBody);
                return null;
            }

            return response.body() != null ? response.body().bytes() : null;
        }
    }

    /**
     * 播放 WAV 音频数据
     */
    private void playAudio(byte[] audioData) {
        try {
            // 停止当前播放的音频
            stopCurrentAudio();

            ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
            AudioInputStream ais = AudioSystem.getAudioInputStream(bais);

            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();

            currentClip = clip;

            // 等待播放完成
            while (clip.isRunning()) {
                Thread.sleep(100);
            }

            clip.close();
            if (currentClip == clip) {
                currentClip = null;
            }

        } catch (Exception e) {
            LOGGER.error("[TTS] 音频播放失败", e);
        }
    }

    /**
     * 停止当前播放的音频
     */
    public void stopCurrentAudio() {
        if (currentClip != null && currentClip.isRunning()) {
            try {
                currentClip.stop();
                currentClip.close();
            } catch (Exception e) {
                // 忽略
            }
            currentClip = null;
        }
    }

    /**
     * 检查 TTS 服务是否可用
     */
    public boolean checkService() {
        try {
            MainConfig config = getConfig();
            String url = config.ttsServerUrl + "/health";

            Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 转义 JSON 字符串
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
     * 关闭服务，释放资源
     */
    public void shutdown() {
        stopCurrentAudio();
        audioExecutor.shutdown();
    }
}
