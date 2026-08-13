package com.localaicompanion.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图解析器
 * 负责将大模型返回的文本解析为标准化任务对象
 *
 * 解析规则：
 * 1. 如果返回内容是纯JSON，尝试解析为任务意图
 * 2. 如果返回内容是普通文本，视为聊天消息
 * 3. 解析失败的JSON视为普通聊天，不触发游戏行为
 * 4. 最大解析重试次数限制
 *
 * 这是大模型和游戏世界之间的第一道隔离墙
 * 大模型的输出必须经过这里才能转化为游戏操作
 */
public class IntentParser {
    private static final Logger LOGGER = LoggerFactory.getLogger("LocalAICompanion-IntentParser");

    private final ObjectMapper objectMapper;
    private final int maxParseRetries;

    // JSON提取正则 - 从文本中提取JSON部分
    private static final Pattern JSON_PATTERN = Pattern.compile(
        "\\{[^{}]*\\}", Pattern.DOTALL
    );

    // 嵌套JSON提取正则（支持简单嵌套）
    private static final Pattern NESTED_JSON_PATTERN = Pattern.compile(
        "\\{(?:[^{}]|\\{[^{}]*\\})*\\}", Pattern.DOTALL
    );

    public IntentParser() {
        this.objectMapper = new ObjectMapper();
        this.maxParseRetries = 2;
    }

    /**
     * 解析大模型返回内容
     * @param rawResponse 大模型原始返回文本
     * @return 解析结果（可能是任务或纯聊天）
     */
    public ParseResult parse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return ParseResult.chat("（空响应）");
        }

        String trimmed = rawResponse.trim();

        // 第一步：尝试直接解析为JSON
        ParseResult result = tryParseJson(trimmed);
        if (result.isTask()) {
            return result;
        }

        // 第二步：从文本中提取JSON部分
        result = tryExtractJson(trimmed);
        if (result.isTask()) {
            return result;
        }

        // 第三步：尝试关键词匹配（作为fallback，但优先级低）
        // 注意：关键词匹配只用于明确的简单指令，复杂任务必须走JSON
        result = tryKeywordMatch(trimmed);
        if (result.isTask()) {
            LOGGER.debug("[IntentParser] 通过关键词匹配识别意图: {}", result.getTask().getIntentType());
            return result;
        }

        // 都失败了，视为纯聊天
        return ParseResult.chat(trimmed);
    }

    /**
     * 尝试直接解析JSON
     */
    private ParseResult tryParseJson(String text) {
        // 检查是否看起来像JSON
        if (!text.startsWith("{")) {
            return ParseResult.chat(text);
        }

        try {
            JsonNode root = objectMapper.readTree(text);
            return parseJsonNode(root, text);
        } catch (Exception e) {
            LOGGER.debug("[IntentParser] 直接JSON解析失败: {}", e.getMessage());
            return ParseResult.chat(text);
        }
    }

    /**
     * 从文本中提取JSON部分
     */
    private ParseResult tryExtractJson(String text) {
        // 先尝试嵌套JSON匹配
        Matcher matcher = NESTED_JSON_PATTERN.matcher(text);

        while (matcher.find()) {
            String jsonStr = matcher.group();
            try {
                JsonNode root = objectMapper.readTree(jsonStr);
                // 验证是否包含intent字段
                if (root.has("intent")) {
                    ParseResult result = parseJsonNode(root, text);
                    if (result.isTask()) {
                        return result;
                    }
                }
            } catch (Exception e) {
                // 继续尝试下一个匹配
            }
        }

        return ParseResult.chat(text);
    }

    /**
     * 解析JSON节点为标准任务
     */
    private ParseResult parseJsonNode(JsonNode root, String originalText) {
        // 必须包含intent字段
        if (!root.has("intent")) {
            return ParseResult.chat(originalText);
        }

        String intentStr = root.path("intent").asText("").toLowerCase();
        IntentType intentType = IntentType.fromCode(intentStr);

        if (intentType == IntentType.UNKNOWN) {
            LOGGER.warn("[IntentParser] 未知意图类型: {}", intentStr);
            // 未知意图不执行，只返回聊天
            String comment = root.path("comment").asText(originalText);
            return ParseResult.chat(comment);
        }

        // 提取目标
        String target = root.path("target").asText(null);

        // 提取数量
        int amount = root.path("amount").asInt(1);
        if (amount <= 0) amount = 1;
        if (amount > 256) amount = 256; // 限制最大数量

        // 提取评论/NPC说话内容
        String comment = root.path("comment").asText("好的");

        // 构建标准任务
        StandardTask task = new StandardTask(intentType, target, amount, comment);

        // 提取附加参数
        if (root.has("parameters") && root.path("parameters").isObject()) {
            JsonNode params = root.path("parameters");
            params.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    task.addParameter(entry.getKey(), value.asText());
                } else if (value.isInt()) {
                    task.addParameter(entry.getKey(), value.asInt());
                } else if (value.isBoolean()) {
                    task.addParameter(entry.getKey(), value.asBoolean());
                } else if (value.isDouble()) {
                    task.addParameter(entry.getKey(), value.asDouble());
                }
            });
        }

        // 提取坐标（如果有）
        if (root.has("x") && root.has("y") && root.has("z")) {
            task.addParameter("x", root.path("x").asDouble());
            task.addParameter("y", root.path("y").asDouble());
            task.addParameter("z", root.path("z").asDouble());
        }

        LOGGER.info("[IntentParser] 解析成功: {} -> {}", intentStr, intentType);
        return ParseResult.task(task);
    }

    /**
     * 关键词匹配（fallback，只处理简单明确的指令）
     * 注意：这只是为了提高简单指令的响应速度，复杂任务必须走JSON
     */
    private ParseResult tryKeywordMatch(String text) {
        String lower = text.toLowerCase();

        // 停止/取消
        if (lower.contains("停下") || lower.contains("停止") || lower.contains("取消") ||
            lower.contains("stop") || lower.contains("cancel")) {
            StandardTask task = StandardTask.createStop("好的，我停下了");
            return ParseResult.task(task);
        }

        // 过来/到我这里
        if (lower.contains("过来") || lower.contains("到我这") || lower.contains("跟我来") ||
            lower.contains("come here") || lower.contains("follow me")) {
            StandardTask task = new StandardTask(IntentType.COME_HERE, null, 0, "来了！");
            return ParseResult.task(task);
        }

        // 跟着我
        if (lower.contains("跟着我") || lower.contains("跟随") || lower.contains("follow")) {
            StandardTask task = new StandardTask(IntentType.FOLLOW, null, 0, "好的，我跟着你");
            return ParseResult.task(task);
        }

        // 待在这里/别动
        if (lower.contains("待在") || lower.contains("别动") || lower.contains("原地") ||
            lower.contains("stay") || lower.contains("wait")) {
            StandardTask task = new StandardTask(IntentType.STAY, null, 0, "好的，我在这里等你");
            return ParseResult.task(task);
        }

        // 回去/返回
        if (lower.contains("回去") || lower.contains("返回") || lower.contains("return")) {
            StandardTask task = StandardTask.createReturn("好的，我回去了");
            return ParseResult.task(task);
        }

        return ParseResult.chat(text);
    }

    public int getMaxParseRetries() {
        return maxParseRetries;
    }

    /**
     * 解析结果封装
     */
    public static class ParseResult {
        private final boolean isTask;
        private final StandardTask task;
        private final String chatMessage;

        private ParseResult(boolean isTask, StandardTask task, String chatMessage) {
            this.isTask = isTask;
            this.task = task;
            this.chatMessage = chatMessage;
        }

        public static ParseResult task(StandardTask task) {
            return new ParseResult(true, task, task.getComment());
        }

        public static ParseResult chat(String message) {
            return new ParseResult(false, null, message);
        }

        public boolean isTask() {
            return isTask;
        }

        public StandardTask getTask() {
            return task;
        }

        public String getChatMessage() {
            return chatMessage;
        }
    }
}
