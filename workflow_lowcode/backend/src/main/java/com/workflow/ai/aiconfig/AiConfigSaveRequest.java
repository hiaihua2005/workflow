package com.workflow.ai.aiconfig;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 大模型配置保存请求（apiKey 留空 = 保持现有密钥不变）。
 */
public class AiConfigSaveRequest {

    private Boolean enabled = true;

    @NotBlank(message = "端点地址不能为空")
    @Pattern(regexp = "^https?://.+", message = "端点地址须以 http(s):// 开头")
    private String baseUrl;

    /** 留空/缺失 = 不修改已保存密钥；传 "-"（半角连字符）= 清空密钥。 */
    private String apiKey;

    @NotBlank(message = "模型名称不能为空")
    private String model;

    @Min(value = 0, message = "温度范围 0~2")
    @Max(value = 2, message = "温度范围 0~2")
    private Double temperature;

    @Min(value = 1, message = "maxTokens 至少为 1")
    @Max(value = 200000, message = "maxTokens 过大")
    private Integer maxTokens;

    @Min(value = 500, message = "连接超时不小于 500ms")
    @Max(value = 60000, message = "连接超时不大于 60s")
    private Integer connectTimeoutMs;

    @Min(value = 1000, message = "读超时不小于 1s")
    @Max(value = 600000, message = "读超时不大于 10 分钟")
    private Integer readTimeoutMs;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(Integer connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public Integer getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(Integer readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
