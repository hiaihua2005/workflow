package com.workflow.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱内置模型配置桥。
 *
 * <p>默认 {@code workflow.ai.base-url} 指向平台 NestJS 后端的内部 LLM 网关
 * （{@code http://127.0.0.1:8080/api/internal/llm/v1}）；当部署形态为
 * 「Java 单后端」（该端口由 Java 自身占用，无内部网关）时，若宿主机存在
 * 沙箱模型配置文件 {@code /etc/.z-ai-config}（z-ai-web-dev-sdk 同款格式：
 * baseUrl/apiKey/token/chatId/userId），则自动桥接到文件内的真实 OpenAI
 * 兼容网关，保持「零外部配置」的开箱即用语义。
 *
 * <p>桥接只填补<b>仍处于默认值</b>的字段：任何显式覆盖（环境变量
 * {@code AI_BASE_URL}、{@code AI_API_KEY}、{@code AI_MODEL} 或 yml）优先级更高，
 * 沙箱配置文件中不含密钥明文日志。可通过系统属性
 * {@code zai.config.file} 指定其他配置文件路径，设为 {@code none} 禁用桥接。
 */
public final class SandboxAiConfigBridge {

    private static final Logger log = LoggerFactory.getLogger(SandboxAiConfigBridge.class);

    /** 与 {@link AiProperties} 字段默认值保持一致的「未显式配置」判定基准。 */
    static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080/api/internal/llm/v1";
    static final String DEFAULT_API_KEY = "internal-llm";

    private SandboxAiConfigBridge() {
    }

    /** 就地修补 properties；文件缺失/解析失败时静默跳过（保留原默认值）。 */
    public static void apply(AiProperties properties) {
        String path = System.getProperty("zai.config.file", "/etc/.z-ai-config");
        if ("none".equalsIgnoreCase(path.trim())) {
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            return;
        }
        try {
            JsonNode cfg = new ObjectMapper().readTree(file);
            String baseUrl = cfg.path("baseUrl").asText("");
            String apiKey = cfg.path("apiKey").asText("");
            String token = cfg.path("token").asText("");
            String chatId = cfg.path("chatId").asText("");
            String userId = cfg.path("userId").asText("");
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                log.info("沙箱模型配置文件缺少 baseUrl/apiKey，跳过桥接: {}", path);
                return;
            }

            boolean bridged = false;
            if (isDefault(properties.getBaseUrl(), DEFAULT_BASE_URL)) {
                properties.setBaseUrl(baseUrl);
                bridged = true;
            }
            if (isDefault(properties.getApiKey(), DEFAULT_API_KEY)) {
                properties.setApiKey(apiKey);
                bridged = true;
            }
            if (bridged) {
                Map<String, String> headers = new LinkedHashMap<>(properties.getExtraHeaders());
                if (!token.isBlank()) {
                    headers.put("X-Token", token);
                }
                if (!chatId.isBlank()) {
                    headers.put("X-Chat-Id", chatId);
                }
                if (!userId.isBlank()) {
                    headers.put("X-User-Id", userId);
                }
                headers.put("X-Z-AI-From", "Z");
                properties.setExtraHeaders(headers);
                log.info("沙箱模型桥接生效: base-url={}, extra-headers={}（密钥不落日志）",
                        properties.getBaseUrl(), headers.keySet());
            }
        } catch (Exception e) {
            log.warn("沙箱模型配置文件解析失败，使用原 AI 配置: {}", e.getMessage());
        }
    }

    /** 空白或与默认值相同 → 视为未显式配置。 */
    private static boolean isDefault(String current, String dflt) {
        return current == null || current.isBlank() || current.equals(dflt);
    }
}
