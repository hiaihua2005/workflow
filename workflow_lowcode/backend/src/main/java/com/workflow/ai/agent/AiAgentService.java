package com.workflow.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.ai.config.AiProperties;
import com.workflow.ai.exception.AiException;
import com.workflow.ai.model.ChatMessage;
import com.workflow.ai.model.ChatModel;
import com.workflow.ai.model.ChatOptions;
import com.workflow.ai.model.ChatResult;
import com.workflow.ai.model.PageRef;
import com.workflow.ai.model.ToolCall;
import com.workflow.ai.support.AiUsageRecorder;
import com.workflow.ai.tool.AiToolContext;
import com.workflow.ai.tool.AiToolRegistry;
import com.workflow.ai.tool.OpenPageTool;
import com.workflow.ai.tool.page.PageControlTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手编排：多轮对话 + 工具调用循环。
 */
@Service
public class AiAgentService {

    /** 单次请求最大工具调用步数（页面操作多步链需更多步数）。 */
    private static final int MAX_STEPS = 8;

    /** 页面清单注入系统提示的最大字符数（防 prompt 膨胀）。 */
    private static final int MAX_MANIFEST_CHARS = 8000;

    /** 单条回复最多附带的页面入口数。 */
    private static final int MAX_NAVIGATIONS = 3;

    static final String MODULE = "assistant";

    private static final String BASE_PROMPT = """
            你是「工作流管理平台」的智能助手，通过对话帮助用户完成平台内的任务。
            你的可用能力以“工具”形式提供：
            - create_form：当用户想要新建、创建一个表单时调用（**真实创建表单草稿并保存**）。参数：
              name=表单名称；description=字段需求描述；formType=表单类型。
            - 表单类型语义（formType）：BUSINESS=业务表单，纯数据填报，**不关联审批流程**；
              WORKFLOW=工作流表单，用于挂接审批流程。用户说“业务表单/登记表/信息表”用 BUSINESS，
              只有用户明确说“审批/流程/工作流表单”才用 WORKFLOW。
            - generate_form_schema：仅当用户想先看表单结构（不创建）时调用，只返回结构 JSON，不落库。
            - open_page：提供一个可点击的页面入口，引导用户前往平台内页面；path 必须来自下方
              「用户可访问页面」列表，禁止编造或使用未列出的路径。
            重要规则：
            - **如实描述工具结果**：只有 create_form 返回 ok=true 后才能说“已创建”；
              generate_form_schema 只生成了结构，**不是**创建完成，禁止说“已创建/已保存”。
            - create_form 成功后：说明表单已创建为**草稿（未发布）**，附内联链接
              [在设计器中打开](/form/designer?id=<formId>)，提示可在设计器中调整并发布；
              业务表单发布后即可在业务表单数据页面录入数据。
            - **表单与流程的关系**：业务表单（BUSINESS）没有审批流程；只有工作流表单（WORKFLOW）
              才可配置审批流程。创建业务表单后**禁止**提及“进入相关流程/提交审批/发起审批”；
              应引导用户在设计器调整发布后录入数据。
            - 你**无法替用户打开页面**，只能提供可点击的入口。**禁止**说“已为你打开/已跳转/已进入某页面”。
              应使用“可点击下方入口前往 X”这类措辞。
            - 当回答涉及平台页面时，**优先在正文中内联 Markdown 链接**，格式 `[页面名](/路径)`
              （路径必须取自下方「用户可访问页面」列表，例如 `[用户管理](/system/user)`），
              让用户可直接在文字中点击跳转；可在多处内联。
            - 当用户询问“怎么做某事 / 在哪里配置 / 如何管理”等答案位于某个页面的问题时，
              除内联链接外也可调用 open_page 补充入口。
            - 用户要求生成表单时，必须调用 create_form 或 generate_form_schema 工具，不要凭空编造表单结构。
            - 工具执行后，用简洁的中文说明结果或操作步骤。
            - 与平台操作无关的问题，礼貌说明你只能协助平台内的操作。
            - 始终使用中文，回复简洁；列表用 `-`，不要输出多余空行。

            ## 页面操作（page_control）
            - 用户提出页面操作诉求（查询、填表、点击、提交、筛选、读取数据、测试页面等）时，
              调用 page_control 下发动作序列；可用 op：
              set-query(查询条件赋值)/search(执行查询)/reset(清空条件)/set-value(表单赋值)/
              set-filter(数据组件筛选)/click(点击)/fill(输入)/open-create(打开新增)/
              submit(提交表单)/validate(校验)/delete-rows(删除选中行，危险)/refresh(刷新)/
              sort(排序 params:{sort,order})/navigate(跳转菜单路径)/get-state(读页面状态)/
              read-table(读表格行数据 params:{limit})/expect(断言 params:{type,expected,comparator})。
            - 动作结果以 {"observation":"page_action_result","results":[...]} 的用户消息回传：
              ok=false 时阅读 error 修正后重试；需要分步时每次调用只带下一步动作。
            - 只能使用「当前页面组件清单」中存在的 target/field；清单为空或无对应能力时如实告知用户。
            - 填表时只写入用户明确给出的值，禁止编造；submit/delete-rows 等写操作前先用 get-state
              确认关键值，并在回复中说明将要执行的操作。
            - 若用户要求“生成测试用例/设计测试用例”（不执行）：直接输出 JSON 数组，不调用工具，
              每条 {"name":"用例名","actions":[...]}，actions 使用上述 op；仅当用户要求“执行/回放”时才调用 page_control。
              生成用例时**只输出 JSON 数组本身**：禁止 Markdown 代码块围栏（```）、禁止在 JSON 前后添加任何说明文字。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AiProperties properties;
    private final AiToolRegistry toolRegistry;
    private final AiUsageRecorder recorder;
    private final ObjectMapper objectMapper;

    public AiAgentService(ObjectProvider<ChatModel> chatModelProvider,
                          AiProperties properties,
                          AiToolRegistry toolRegistry,
                          AiUsageRecorder recorder,
                          ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一轮对话（含工具调用）。
     *
     * @param history     历史消息（不含本轮 user 消息）
     * @param userMessage 本轮用户输入
     * @param pages       当前用户可访问页面（供 open_page 白名单与系统提示）
     * @param events      事件回调（工具调用/结果/最终回复）
     */
    public void chat(List<ChatMessage> history, String userMessage, List<PageRef> pages, JsonNode pageManifest, Events events) {
        ChatModel model = requireModel();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(pages, pageManifest)));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(userMessage));

        ChatOptions options = ChatOptions.withTools(toolRegistry.specs());
        AiToolContext context = new AiToolContext(pages == null ? List.of() : pages);
        Map<String, PageRef> navigations = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        for (int step = 0; step < MAX_STEPS; step++) {
            ChatResult result = model.completeWithTools(messages, options);
            if (!result.hasToolCalls()) {
                String text = result.content() == null || result.content().isBlank()
                        ? "（没有更多内容）" : result.content();
                // 兜底：模型未主动提供入口时，按回复中出现的页面名补全入口
                if (navigations.isEmpty()) {
                    for (PageRef page : matchPages(text, pages)) {
                        navigations.put(page.path(), page);
                    }
                }
                events.message(text, new ArrayList<>(navigations.values()));
                recorder.recordSuccess(MODULE, properties.getModel(), 0, 0, System.currentTimeMillis() - start);
                return;
            }
            messages.add(ChatMessage.assistantToolCalls(result.toolCalls()));
            for (ToolCall toolCall : result.toolCalls()) {
                JsonNode args = parse(toolCall.arguments());
                events.toolCall(toolCall.name(), args);
                String toolResultJson = toolRegistry.execute(toolCall.name(), args, context);
                JsonNode toolResult = parse(toolResultJson);
                events.toolResult(toolCall.name(), toolResult);
                collectNavigation(toolCall.name(), toolResult, navigations);
                // page_control：动作原样透传前端（SSE page_action 事件），工具结果仅回执 dispatched
                if (PageControlTool.NAME.equals(toolCall.name()) && toolResult.path("dispatched").asBoolean(false)) {
                    events.pageActions(toolResult.path("actions"));
                }
                messages.add(ChatMessage.tool(toolCall.id(), toolResultJson));
            }
        }

        events.message("抱歉，处理步骤过多，请把需求拆分为更小的请求后再试。", new ArrayList<>(navigations.values()));
        recorder.recordFailure(MODULE, properties.getModel(), "max steps exceeded",
                System.currentTimeMillis() - start);
    }

    /** open_page 成功结果 → 记录页面入口。 */
    private void collectNavigation(String toolName, JsonNode result, Map<String, PageRef> navigations) {
        if (!OpenPageTool.NAME.equals(toolName) || result == null) {
            return;
        }
        String path = result.path("path").asText("");
        if (path.isBlank()) {
            return;
        }
        String label = result.path("label").asText(path);
        navigations.put(path, new PageRef(path, label));
    }

    /** 兜底：按白名单页面名在回复文本中的出现顺序匹配入口（最多 MAX_NAVIGATIONS 个）。 */
    private List<PageRef> matchPages(String text, List<PageRef> pages) {
        List<PageRef> matched = new ArrayList<>();
        if (text == null || pages == null || pages.isEmpty()) {
            return matched;
        }
        for (PageRef page : pages) {
            if (page.label() != null && !page.label().isBlank() && text.contains(page.label())) {
                matched.add(page);
                if (matched.size() >= MAX_NAVIGATIONS) {
                    break;
                }
            }
        }
        return matched;
    }

    /** 组装系统提示：固定约束 + 当前用户可访问页面清单 + 当前页面组件清单（AI 页面操作依据）。 */
    private String buildSystemPrompt(List<PageRef> pages, JsonNode pageManifest) {
        StringBuilder sb = new StringBuilder(BASE_PROMPT);
        if (pages != null && !pages.isEmpty()) {
            sb.append("\n用户可访问页面（open_page 的 path 必须取自此处）：\n");
            for (PageRef page : pages) {
                sb.append("- ").append(page.label()).append(" : ").append(page.path()).append('\n');
            }
        }
        if (pageManifest != null && !pageManifest.isNull() && pageManifest.isObject()) {
            String manifest = pageManifest.toString();
            if (manifest.length() > MAX_MANIFEST_CHARS) {
                manifest = manifest.substring(0, MAX_MANIFEST_CHARS) + "...（已截断）";
            }
            sb.append("\n当前页面组件清单（page_control 的 target/field 必须取自此处；\"\"=页面未识别，如实告知用户）：\n")
              .append(manifest).append('\n');
        }
        return sb.toString();
    }

    private ChatModel requireModel() {
        if (!properties.isConfigured()) {
            throw new AiException(AiException.Code.CONFIG_MISSING, "AI 服务未配置");
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new AiException(AiException.Code.CONFIG_MISSING, "AI 服务未配置");
        }
        return model;
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    /** 事件回调。 */
    public interface Events {

        void toolCall(String name, JsonNode args);

        void toolResult(String name, JsonNode result);

        void message(String text, List<PageRef> navigations);

        /** page_control 动作序列下发（SSE page_action 事件，前端执行器消费）。 */
        default void pageActions(JsonNode actions) {
        }
    }
}
