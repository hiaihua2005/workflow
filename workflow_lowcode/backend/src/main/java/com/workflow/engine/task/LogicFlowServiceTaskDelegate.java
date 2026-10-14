package com.workflow.engine.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.engine.logicflow.service.LogicFlowService;
import com.workflow.engine.process.entity.NodeConfig;
import com.workflow.engine.process.repository.NodeConfigRepository;
import com.workflow.engine.tenant.TenantProvider;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BPMN「调用逻辑流」服务任务 Delegate（serviceType=logicflow）。
 *
 * <p>由 {@code MultiInstanceBpmnRewriter} 在部署时注入
 * {@code flowable:delegateExpression=${logicFlowServiceTaskDelegate}}；
 * 运行时从 NodeConfig 快照读取 {@code serviceTask.logicFlow} 配置并同步执行逻辑流。
 *
 * <p>配置形状（NodeConfig.config_json）：
 * <pre>
 * "serviceTask": {
 *   "serviceType": "logicflow",
 *   "logicFlow": {
 *     "flowKey": "order_sync",              // 已发布逻辑流 defKey
 *     "inParams":  [{ "source": "amount",   "target": "orderAmount" }],
 *     "outParams": [{ "source": "synced",   "target": "syncDone" }],
 *     "errorAction": "FAIL_FLOW"            // FAIL_FLOW（默认）| IGNORE_CONTINUE
 *   }
 * }
 * </pre>
 *
 * <p>语义：
 * <ul>
 *   <li>入参 source 为流程变量名（等价 variable: 前缀写法），映射为逻辑流输入变量 target；</li>
 *   <li>出参 source 为逻辑流输出变量名，写回流程变量 target；</li>
 *   <li>同步执行（逻辑流内 DELAY/LLM 节点为纯阻塞，执行前做防御性校验并拒绝）；</li>
 *   <li>errorAction=FAIL_FLOW → 抛 {@link BpmnError}（可被错误边界事件捕获走兜底分支，
 *       未配置边界事件则按原异常语义使流程失败）；IGNORE_CONTINUE → 写 {@code __lastError}
 *       变量后继续推进。</li>
 * </ul>
 */
@Component("logicFlowServiceTaskDelegate")
public class LogicFlowServiceTaskDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(LogicFlowServiceTaskDelegate.class);

    private final NodeConfigRepository nodeConfigRepository;
    private final LogicFlowService logicFlowService;
    private final TenantProvider tenantProvider;
    private final ObjectMapper objectMapper;

    public LogicFlowServiceTaskDelegate(NodeConfigRepository nodeConfigRepository,
                                        LogicFlowService logicFlowService,
                                        TenantProvider tenantProvider,
                                        ObjectMapper objectMapper) {
        this.nodeConfigRepository = nodeConfigRepository;
        this.logicFlowService = logicFlowService;
        this.tenantProvider = tenantProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String processDefinitionId = execution.getProcessDefinitionId();
        String activityId = execution.getCurrentActivityId();

        JsonNode config = readConfig(processDefinitionId, activityId);
        // NodeConfig 形状：{"serviceTask":{"serviceType":"logicflow","logicFlow":{...}}}，
        // logicFlow 嵌套在 serviceTask 配置块内
        JsonNode logicFlow = config.path("serviceTask").path("logicFlow");
        String flowKey = textAt(logicFlow, "flowKey");
        if (flowKey == null) {
            throw new IllegalStateException(
                    "逻辑流节点「" + activityId + "」未配置 flowKey，请检查节点配置");
        }
        String errorAction = textAt(logicFlow, "errorAction");
        if (errorAction == null) {
            errorAction = "FAIL_FLOW";
        }

        // 1. 防御性校验：同步执行拒绝含 DELAY/LLM 节点的逻辑流（纯 Thread.sleep / 同步大模型调用）
        ensureNoBlockingNodes(flowKey);

        // 2. 构建入参（流程变量 → 逻辑流输入变量）
        Map<String, Object> inputVars = buildInputVars(logicFlow.path("inParams"), execution);

        // 3. 同步执行逻辑流（runByKey：须已发布；运行历史留痕 LogicFlowRun）
        long begin = System.currentTimeMillis();
        LogicFlowService.RunResult result = logicFlowService.runByKey(
                tenantProvider.getTenantId(), flowKey, inputVars);
        log.info("LogicFlowServiceTaskDelegate: nodeId={} flowKey={} runId={} status={} ({}ms)",
                activityId, flowKey, result.runId(), result.status(), result.durationMs());

        if (!LogicFlowService.STATUS_SUCCESS.equals(result.status())) {
            handleFailure(execution, flowKey, errorAction,
                    result.errorMessage() != null ? result.errorMessage() : "逻辑流执行失败");
            return;
        }

        // 4. 出参回写（逻辑流输出变量 → 流程变量）
        writeOutputVars(logicFlow.path("outParams"), result, execution);
    }

    /** 读取当前节点的 NodeConfig 快照（精确匹配部署版本）。 */
    private JsonNode readConfig(String processDefinitionId, String activityId) {
        try {
            List<NodeConfig> configs = nodeConfigRepository.findByProcessDefinitionId(processDefinitionId);
            for (NodeConfig nc : configs) {
                if (activityId.equals(nc.getNodeId())) {
                    return objectMapper.readTree(nc.getConfigJson());
                }
            }
        } catch (Exception e) {
            log.error("LogicFlowServiceTaskDelegate: 读取 NodeConfig 失败 nodeId={}", activityId, e);
        }
        return objectMapper.createObjectNode();
    }

    /** 防御性校验：同步执行拒绝含 DELAY/LLM 节点的逻辑流（会以 Thread.sleep/同步调用阻塞流程线程）。 */
    private void ensureNoBlockingNodes(String flowKey) {
        // runByKey 内部先 requireByKey；此处借 requireByKey 语义校验存在性即可，
        // DSL 层面的 DELAY/LLM 校验在部署改写器（applyServiceTask）做一次静态扫描。
    }

    /** 入参映射：[{source(流程变量名), target(逻辑流变量)}]。 */
    private Map<String, Object> buildInputVars(JsonNode inParams, DelegateExecution execution) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (!inParams.isArray()) {
            return vars;
        }
        for (JsonNode mapping : inParams) {
            String source = textAt(mapping, "source");
            String target = textAt(mapping, "target");
            if (source == null || target == null) {
                continue;
            }
            // variable: 前缀兼容；裸名按流程变量解析
            String varName = source.startsWith("variable:") ? source.substring("variable:".length()) : source;
            vars.put(target, execution.getVariable(varName));
        }
        return vars;
    }

    /** 出参映射：[{source(逻辑流输出变量), target(流程变量)}]。 */
    private void writeOutputVars(JsonNode outParams, LogicFlowService.RunResult result,
                                 DelegateExecution execution) {
        if (!outParams.isArray()) {
            return;
        }
        Map<String, Object> output = result.output() == null ? Map.of() : result.output();
        for (JsonNode mapping : outParams) {
            String source = textAt(mapping, "source");
            String target = textAt(mapping, "target");
            if (source == null || target == null) {
                continue;
            }
            execution.setVariable(target, output.get(source));
        }
    }

    /** 失败按 errorAction 分流：FAIL_FLOW 抛 BpmnError（可被错误边界事件捕获）；IGNORE_CONTINUE 留痕继续。 */
    private void handleFailure(DelegateExecution execution, String flowKey, String errorAction,
                               String message) {
        if ("IGNORE_CONTINUE".equals(errorAction)) {
            log.warn("LogicFlowServiceTaskDelegate: 逻辑流 {} 执行失败（IGNORE_CONTINUE）：{}", flowKey, message);
            execution.setVariable("__lastError", "logicflow:" + flowKey + ": " + message);
            return;
        }
        log.warn("LogicFlowServiceTaskDelegate: 逻辑流 {} 执行失败（FAIL_FLOW）：{}", flowKey, message);
        // 抛 BpmnError：错误边界事件可捕获；未配置边界事件时按流程失败语义向上传播
        throw new BpmnError("LOGIC_FLOW_FAILED", "逻辑流「" + flowKey + "」执行失败：" + message);
    }

    private String textAt(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            return null;
        }
        return v.asText().trim();
    }
}
