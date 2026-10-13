package com.workflow.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 工具：dom_action —— 浏览器原语兜底（前端执行）。
 *
 * <p>当 {@code page_action} 覆盖不了（页面无注册组件/长尾交互）时，模型基于前端回传的
 * 「DOM 语义树」（可交互元素编号清单）输出原语动作（点击/输入/选择），前端执行器
 * 代为触发原生事件并回传执行后的新树，循环至目标完成。
 */
@Component
public class DomActionTool implements AiTool {

    public static final String NAME = "dom_action";

    private final ObjectMapper objectMapper;

    public DomActionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "浏览器原语操作（兜底）：对当前页面 DOM 语义树中的编号元素执行 click/input/select。"
                + "target 是语义树中的元素编号（如 \"[3]\"）；input/select 需给 value。"
                + "执行后前端会回传新的语义树，继续下一步直到完成。"
                + "优先使用 page_action（结构化、更可靠），仅当无对应结构化能力时才用本工具。"
                + "危险按钮（删除/提交等）执行前会弹确认框。";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        ArrayNode actionEnum = action.putArray("enum");
        for (String a : new String[] {"click", "input", "select", "readPage"}) {
            actionEnum.add(a);
        }
        action.put("description", "click=点击元素；input=向输入框填文本；select=选择下拉选项；readPage=重新获取当前语义树");

        ObjectNode target = properties.putObject("target");
        target.put("type", "string");
        target.put("description", "语义树元素编号，如 \"[3]\"（来自上一次 dom_action 回传或 page_context 的语义树）");

        ObjectNode value = properties.putObject("value");
        value.put("type", "string");
        value.put("description", "input=要填入的文本；select=要选中的选项文本");

        ArrayNode required = schema.putArray("required");
        required.add("action");
        required.add("target");
        return schema;
    }

    @Override
    public String execute(JsonNode arguments, AiToolContext context) {
        return "{\"error\":\"dom_action 应由前端执行，服务端不应调用此方法\"}";
    }

    @Override
    public boolean clientExecuted() {
        return true;
    }
}
