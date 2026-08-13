package com.localaicompanion.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.CollectionType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 两级记忆系统管理器
 *
 * 短期记忆：内存保存最近N轮对话，每次请求随同发送给大模型
 * 长期记忆：JSON文件保存在世界存档文件夹，独立于大模型
 *
 * 核心特性：
 * - 自动提取对话关键信息写入长期记忆
 * - GUI支持查看、新增、编辑、删除单条记忆
 * - NPC解散、死亡、重新召唤，长期记忆不会丢失
 * - 发起LLM请求时自动附加相关长期记忆片段
 */
public class MemoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-Memory");
    private static final String MEMORY_DIR = "local_ai_companion";
    private static final String LONG_TERM_FILE = "long_term_memory.json";
    private static final String CHAT_HISTORY_FILE = "chat_history.json";

    private final ObjectMapper objectMapper;

    // 短期记忆：对话历史（内存中）
    private final Deque<ChatMessage> shortTermMemory;
    private int maxShortTermMessages = 20;

    // 长期记忆：持久化存储
    private List<LongTermMemoryEntry> longTermMemory;

    // 记忆提取阈值（相似度阈值）
    private double extractionThreshold = 0.5;

    public MemoryManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.shortTermMemory = new ArrayDeque<>();
        this.longTermMemory = new ArrayList<>();
    }

    /**
     * 添加聊天消息到短期记忆
     */
    public void addChatMessage(String role, String content) {
        ChatMessage msg = new ChatMessage(role, content, System.currentTimeMillis());
        shortTermMemory.addLast(msg);

        // 限制最大数量
        while (shortTermMemory.size() > maxShortTermMessages) {
            shortTermMemory.removeFirst();
        }

        // 尝试提取长期记忆
        tryExtractLongTermMemory(role, content);
    }

    /**
     * 尝试从对话中提取长期记忆
     * 简单的关键词匹配提取（不依赖LLM，避免额外开销）
     */
    private void tryExtractLongTermMemory(String role, String content) {
        if (!role.equals("player")) return; // 只从玩家消息中提取

        // 简单的记忆提取规则：
        // 1. 包含"我叫"/"我的名字是" -> 玩家名字
        // 2. 包含"记住" -> 直接记忆
        // 3. 包含"我喜欢"/"我讨厌" -> 偏好记忆

        if (content.contains("记住") || content.contains("别忘了")) {
            String memoryContent = content
                .replace("记住", "")
                .replace("别忘了", "")
                .replace("，", "")
                .replace("。", "")
                .trim();

            if (!memoryContent.isEmpty() && memoryContent.length() > 2) {
                addLongTermMemory("player_preference", memoryContent, "玩家要求记住的内容");
                LOGGER.info("[Memory] 提取到长期记忆: {}", memoryContent);
            }
        }

        // 玩家名字
        if (content.contains("我叫") || content.contains("我的名字是")) {
            String name = extractAfterKeyword(content, "我叫", "我的名字是");
            if (name != null && !name.isEmpty()) {
                addLongTermMemory("player_name", name, "玩家的名字");
            }
        }

        // 喜好
        if (content.contains("我喜欢") || content.contains("我最爱")) {
            String like = extractAfterKeyword(content, "我喜欢", "我最爱");
            if (like != null && !like.isEmpty()) {
                addLongTermMemory("player_like", like, "玩家喜欢的事物");
            }
        }

        // 讨厌
        if (content.contains("我讨厌") || content.contains("我不喜欢")) {
            String dislike = extractAfterKeyword(content, "我讨厌", "我不喜欢");
            if (dislike != null && !dislike.isEmpty()) {
                addLongTermMemory("player_dislike", dislike, "玩家讨厌的事物");
            }
        }
    }

    private String extractAfterKeyword(String text, String... keywords) {
        for (String keyword : keywords) {
            int idx = text.indexOf(keyword);
            if (idx >= 0) {
                String result = text.substring(idx + keyword.length()).trim();
                // 截取到第一个标点符号
                int endIdx = result.length();
                for (char c : new char[]{'，', '。', '！', '？', ',', '.', '!', '?'}) {
                    int pos = result.indexOf(c);
                    if (pos >= 0 && pos < endIdx) {
                        endIdx = pos;
                    }
                }
                if (endIdx > 0) {
                    return result.substring(0, endIdx).trim();
                }
            }
        }
        return null;
    }

    /**
     * 获取短期记忆的上下文文本
     */
    public String getShortTermContext() {
        if (shortTermMemory.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【对话历史】\n");

        for (ChatMessage msg : shortTermMemory) {
            String roleName = msg.role.equals("player") ? "玩家" : "AI同伴";
            sb.append(roleName).append(": ").append(msg.content).append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取相关的长期记忆片段
     * 基于关键词匹配的简单相关性计算
     */
    public String getRelevantLongTermMemory(String currentMessage) {
        if (longTermMemory.isEmpty() || currentMessage == null) return "";

        List<LongTermMemoryEntry> relevant = new ArrayList<>();
        String[] keywords = currentMessage.split("[，。！？\\s]+");

        for (LongTermMemoryEntry entry : longTermMemory) {
            double score = calculateRelevance(entry.content, keywords);
            if (score >= extractionThreshold) {
                relevant.add(entry);
            }
        }

        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【长期记忆】\n");
        for (LongTermMemoryEntry entry : relevant) {
            sb.append("- ").append(entry.content).append("\n");
        }

        return sb.toString();
    }

    /**
     * 计算相关性分数（简单的关键词匹配）
     */
    private double calculateRelevance(String content, String[] keywords) {
        if (content == null || content.isEmpty()) return 0;
        if (keywords.length == 0) return 0;

        int matches = 0;
        String lowerContent = content.toLowerCase();

        for (String keyword : keywords) {
            if (keyword.length() < 2) continue; // 太短的词不算
            if (lowerContent.contains(keyword.toLowerCase())) {
                matches++;
            }
        }

        return (double) matches / keywords.length;
    }

    /**
     * 添加长期记忆
     */
    public void addLongTermMemory(String category, String content, String description) {
        LongTermMemoryEntry entry = new LongTermMemoryEntry(
            UUID.randomUUID().toString(),
            category,
            content,
            description,
            System.currentTimeMillis(),
            0
        );
        longTermMemory.add(entry);
    }

    /**
     * 更新长期记忆
     */
    public boolean updateLongTermMemory(String id, String newContent) {
        for (LongTermMemoryEntry entry : longTermMemory) {
            if (entry.id.equals(id)) {
                entry.content = newContent;
                entry.updatedAt = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    /**
     * 删除长期记忆
     */
    public boolean deleteLongTermMemory(String id) {
        return longTermMemory.removeIf(entry -> entry.id.equals(id));
    }

    /**
     * 获取所有长期记忆
     */
    public List<LongTermMemoryEntry> getAllLongTermMemory() {
        return new ArrayList<>(longTermMemory);
    }

    /**
     * 按分类获取长期记忆
     */
    public List<LongTermMemoryEntry> getLongTermMemoryByCategory(String category) {
        List<LongTermMemoryEntry> result = new ArrayList<>();
        for (LongTermMemoryEntry entry : longTermMemory) {
            if (entry.category.equals(category)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 加载世界记忆
     */
    public void loadWorldMemory(MinecraftServer server) {
        Path memoryDir = getMemoryDir(server);
        Path longTermFile = memoryDir.resolve(LONG_TERM_FILE);
        Path chatFile = memoryDir.resolve(CHAT_HISTORY_FILE);

        // 加载长期记忆
        if (Files.exists(longTermFile)) {
            try {
                CollectionType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, LongTermMemoryEntry.class);
                longTermMemory = objectMapper.readValue(longTermFile.toFile(), type);
                LOGGER.info("[Memory] 长期记忆已加载: {}条", longTermMemory.size());
            } catch (IOException e) {
                LOGGER.error("[Memory] 加载长期记忆失败", e);
                longTermMemory = new ArrayList<>();
            }
        }

        // 对话历史不加载（每次新游戏重新开始）
        shortTermMemory.clear();
    }

    /**
     * 保存世界记忆
     */
    public void saveWorldMemory(MinecraftServer server) {
        Path memoryDir = getMemoryDir(server);

        try {
            Files.createDirectories(memoryDir);

            // 保存长期记忆
            Path longTermFile = memoryDir.resolve(LONG_TERM_FILE);
            objectMapper.writeValue(longTermFile.toFile(), longTermMemory);

            // 保存对话历史（可选，用于调试）
            Path chatFile = memoryDir.resolve(CHAT_HISTORY_FILE);
            objectMapper.writeValue(chatFile.toFile(), new ArrayList<>(shortTermMemory));

            LOGGER.info("[Memory] 记忆已保存: 长期{}条, 短期{}条",
                longTermMemory.size(), shortTermMemory.size());
        } catch (IOException e) {
            LOGGER.error("[Memory] 保存记忆失败", e);
        }
    }

    private Path getMemoryDir(MinecraftServer server) {
        // 暂时放在配置目录下，后续可以优化为按世界存档分开
        return FabricLoader.getInstance().getConfigDir().resolve("localaicompanion").resolve(MEMORY_DIR);
    }

    public int getMaxShortTermMessages() {
        return maxShortTermMessages;
    }

    public void setMaxShortTermMessages(int maxShortTermMessages) {
        this.maxShortTermMessages = maxShortTermMessages;
    }

    public double getExtractionThreshold() {
        return extractionThreshold;
    }

    public void setExtractionThreshold(double extractionThreshold) {
        this.extractionThreshold = extractionThreshold;
    }

    /**
     * 聊天消息
     */
    public static class ChatMessage {
        public String role;
        public String content;
        public long timestamp;

        public ChatMessage() {}

        public ChatMessage(String role, String content, long timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    /**
     * 长期记忆条目
     */
    public static class LongTermMemoryEntry {
        public String id;
        public String category;
        public String content;
        public String description;
        public long createdAt;
        public long updatedAt;
        public int importance; // 重要度 0-10

        public LongTermMemoryEntry() {}

        public LongTermMemoryEntry(String id, String category, String content,
                                   String description, long createdAt, int importance) {
            this.id = id;
            this.category = category;
            this.content = content;
            this.description = description;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.importance = importance;
        }
    }
}
