package com.workflow.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI 可调用工具。
 */
public interface AiTool {

    /** 工具名（function name）。 */
    String name();

    /** 工具用途描述（供模型判断何时调用）。 */
    String description();

    /** 参数 JSON Schema。 */
    JsonNode parametersSchema();

    /**
     * 执行工具。
     *
     * @param arguments 参数（可能为 null 或缺字段）
     * @param context   执行上下文（页面白名单等，可能为空）
     * @return 结果 JSON 文本
     */
    String execute(JsonNode arguments, AiToolContext context);

    /**
     * 是否由前端执行（页面操作/浏览器原语类工具）。
     *
     * <p>客户端执行工具不走服务端 {@link #execute}：agent 循环遇到时暂停，
     * 经 SSE {@code client_tool} 事件下发前端，前端执行后经
     * {@code POST /api/v1/ai/chat/continue} 回传结果续跑。
     */
    default boolean clientExecuted() {
        return false;
    }
}
