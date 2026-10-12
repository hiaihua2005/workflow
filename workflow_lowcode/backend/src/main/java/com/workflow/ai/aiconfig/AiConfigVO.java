package com.workflow.ai.aiconfig;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型配置 VO（GET 返回；apiKey 一律脱敏，不回传明文）。
 */
public class AiConfigVO {

    private Boolean enabled;
    private String baseUrl;
    /** 脱敏后的密钥展示（如 sk-ab****yz），仅供确认已配置。 */
    private String apiKeyMasked;
    private Boolean hasApiKey;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    /** 生效来源："数据库配置" 或 "环境与默认配置"（尚未在页面保存过）。 */
    private String source;
    private LocalDateTime updatedAt;

    /** 静态层附加信息（env/yml/沙箱桥接），帮助管理员理解默认值来自哪里。 */
    private Map<String, String> staticDefaults = new LinkedHashMap<>();

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }
    public Boolean getHasApiKey() { return hasApiKey; }
    public void setHasApiKey(Boolean hasApiKey) { this.hasApiKey = hasApiKey; }
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
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, String> getStaticDefaults() { return staticDefaults; }
    public void setStaticDefaults(Map<String, String> staticDefaults) { this.staticDefaults = staticDefaults; }
}
