package com.workflow.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.ai.aiconfig.AiConfigService;
import com.workflow.ai.aiconfig.DynamicChatModel;
import com.workflow.ai.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模块自动装配。
 *
 * <p>装配平台唯一 {@link ChatModel} bean：{@link DynamicChatModel} 动态代理。
 * 生效配置 = DB 动态配置（系统管理 → 模型配置，保存即生效）优先，回退静态
 * {@code workflow.ai.*}（yml / {@code AI_BASE_URL}/{@code AI_API_KEY}/{@code AI_MODEL}
 * 环境变量 / 沙箱桥接）。未配置密钥时 bean 依然创建，调用时给出明确的
 * 「请在模型配置中填写 API Key」提示——外部部署可在页面完成接入，无需重启。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "workflow.ai", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ChatModel chatModel(AiProperties properties, AiConfigService aiConfigService,
                               ObjectMapper objectMapper) {
        // 单后端部署形态：默认内部网关（NestJS）不存在时，桥接沙箱内置模型配置
        SandboxAiConfigBridge.apply(properties);
        return new DynamicChatModel(properties, aiConfigService, objectMapper);
    }
}
