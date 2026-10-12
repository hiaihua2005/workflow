package com.workflow.ai.aiconfig;

import jakarta.validation.constraints.Pattern;

/**
 * 连通性测试请求：三个字段均可选——不传则用「当前生效配置」对应字段。
 * 允许在保存前先测试（含未保存的密钥明文，仅本次请求内存内使用）。
 */
public class AiConfigTestRequest {

    @Pattern(regexp = "^https?://.+", message = "端点地址须以 http(s):// 开头")
    private String baseUrl;

    /** 测试用密钥（可选）；留空且库中未配置时按空密钥测试。 */
    private String apiKey;

    private String model;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
