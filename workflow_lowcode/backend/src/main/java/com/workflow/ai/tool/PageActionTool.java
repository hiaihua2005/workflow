package com.workflow.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 工具：page_action —— 结构化页面操作（前端执行）。
 *
 * <p>LLM 产出结构化动作（填查询条件/刷新/填表单/提交/导航），经 SSE {@code client_tool}
 * 下发前端，由 {@code aiPageBridge} 路由到页面组件已注册的可编程 API 执行。
 * 仅能操作「当前页面可操作组件清单」中列出的 componentId（随对话上下文注入系统提示）。
 */
@Component
public class PageActionTool implements AiTool {

    public static final String NAME = "page_action";

    private final ObjectMapper objectMapper;

    public PageActionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "操作当前页面上的组件（填查询条件、点查询/刷新、填表单、校验、提交表单、页面导航）。"
                + "componentId 必须来自系统提示中「当前页面可操作组件」清单；清单为空说明本页无可操作组件。"
                + "submitForm 等写操作会先弹确认框由用户确认。结果由前端执行后回传。";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        ArrayNode actionEnum = action.putArray("enum");
        for (String a : new String[] {"setFilter", "resetFilter", "refresh", "openCreate",
                "fillForm", "validateForm", "submitForm", "navigate"}) {
            actionEnum.add(a);
        }
        action.put("description", "动作类型：setFilter=设置查询条件并刷新；resetFilter=清空筛选并刷新；"
                + "refresh=刷新列表；openCreate=打开新增表单弹窗；fillForm=给表单字段填值；"
                + "validateForm=校验表单；submitForm=提交表单（需用户确认）；navigate=页面导航");

        ObjectNode componentId = properties.putObject("componentId");
        componentId.put("type", "string");
        componentId.put("description", "目标组件 id（来自系统提示的组件清单；navigate 时可省略）");

        ObjectNode filter = properties.putObject("filter");
        filter.put("type", "object");
        filter.put("description", "setFilter 用：{字段名: 值} 形式的查询条件");

        ObjectNode values = properties.putObject("values");
        values.put("type", "object");
        values.put("description", "fillForm 用：{字段名: 值} 形式的表单数据");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "navigate 用：目标页面路由（须在用户可访问页面清单内）");

        ArrayNode required = schema.putArray("required");
        required.add("action");
        return schema;
    }

    /** 服务端不执行：永远由前端执行并回传结果。 */
    @Override
    public String execute(JsonNode arguments, AiToolContext context) {
        return "{\"error\":\"page_action 应由前端执行，服务端不应调用此方法\"}";
    }

    @Override
    public boolean clientExecuted() {
        return true;
    }
}
