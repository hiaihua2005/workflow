package com.workflow.ai.aiconfig;

import java.util.Map;

/**
 * 一次解析完成的「生效大模型配置」快照。
 *
 * @param enabled          是否启用
 * @param baseUrl          OpenAI 兼容端点
 * @param apiKey           Bearer 密钥
 * @param model            模型名
 * @param temperature      默认温度（null = 引擎内置默认）
 * @param maxTokens        默认最大输出 token（null = 引擎内置默认）
 * @param connectTimeoutMs 连接超时
 * @param readTimeoutMs    读超时
 * @param extraHeaders     附加请求头（沙箱桥接的 X-Token 等）
 * @param source           来源描述（"数据库配置" / "环境与默认配置"）
 */
public record EffectiveLlmConfig(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens,
        int connectTimeoutMs,
        int readTimeoutMs,
        Map<String, String> extraHeaders,
        String source) {

    /** 指纹：用于判断动态代理是否需要重建底层客户端。 */
    public String fingerprint() {
        return enabled + "|" + baseUrl + "|" + apiKey + "|" + model + "|"
                + connectTimeoutMs + "|" + readTimeoutMs + "|" + extraHeaders;
    }
}
