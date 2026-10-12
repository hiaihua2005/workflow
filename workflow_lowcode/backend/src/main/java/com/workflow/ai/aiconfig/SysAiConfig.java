package com.workflow.ai.aiconfig;

import com.workflow.common.domain.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 大模型（LLM）服务动态配置（单行表，系统管理 → 模型配置 维护）。
 *
 * <p>平台可能部署在任意环境，模型服务不能写死：本表保存 OpenAI 兼容端点的
 * 运行时可变配置，<b>保存即生效（无需重启）</b>。表为空时回退到静态
 * {@code workflow.ai.*}（yml / {@code AI_BASE_URL}/{@code AI_API_KEY}/{@code AI_MODEL}
 * 环境变量 / 沙箱桥接），保证开箱即用语义不变。
 */
@Entity
@Table(name = "sys_ai_config")
public class SysAiConfig extends BaseEntity {

    /** 是否启用大模型能力（停用后所有 AI 调用快速失败并给出明确提示）。 */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** OpenAI 兼容端点基础地址（不含 /chat/completions）。 */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** API 密钥（OpenAI 兼容 Bearer）。 */
    @Column(name = "api_key", length = 512)
    private String apiKey;

    /** 模型名称（如 glm-4-plus / deepseek-chat / qwen-max）。 */
    @Column(nullable = false, length = 128)
    private String model;

    /** 默认温度 0~2（可空 = 用引擎内置默认）。 */
    @Column
    private Double temperature;

    /** 默认最大输出 token 数（可空）。 */
    @Column(name = "max_tokens")
    private Integer maxTokens;

    /** 连接超时（毫秒）。 */
    @Column(name = "connect_timeout_ms", nullable = false)
    private Integer connectTimeoutMs = 5000;

    /** 读超时（毫秒）。 */
    @Column(name = "read_timeout_ms", nullable = false)
    private Integer readTimeoutMs = 120000;

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
