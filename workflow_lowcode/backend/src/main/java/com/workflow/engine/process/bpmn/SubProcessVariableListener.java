package com.workflow.engine.process.bpmn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.engine.process.entity.NodeConfig;
import com.workflow.engine.process.repository.NodeConfigRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内嵌子流程变量绑定监听器（配合 {@code MultiInstanceBpmnRewriter} 注入的
 * executionListener start/end 使用）。
 *
 * <p>从 NodeConfig 快照读取 {@code subProcess} 配置块：
 * <pre>
 * "subProcess": {
 *   "inParams":  [{ "source": "orderId",     "target": "bizOrderId" }],  // start：父级流程变量 → 子流程局部变量
 *   "outParams": [{ "source": "auditResult", "target": "reviewResult" }] // end：子流程变量 → 流程变量
 * }
 * </pre>
 *
 * <p>语义：
 * <ul>
 *   <li>start：读父级流程变量（getVariable 沿作用域链），以 {@code setVariableLocal}
 *       写入子流程作用域——避免同名变量遮蔽父级值；</li>
 *   <li>end：读子流程作用域变量（getVariableLocal，仅局部），回写流程变量。</li>
 * </ul>
 */
@Component("subProcessVariableListener")
public class SubProcessVariableListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SubProcessVariableListener.class);

    private final NodeConfigRepository nodeConfigRepository;
    private final ObjectMapper objectMapper;

    public SubProcessVariableListener(NodeConfigRepository nodeConfigRepository,
                                      ObjectMapper objectMapper) {
        this.nodeConfigRepository = nodeConfigRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void notify(DelegateExecution execution) {
        String event = execution.getEventName();
        String activityId = execution.getCurrentActivityId();
        JsonNode sp = resolveSubProcessConfig(execution.getProcessDefinitionId(), activityId);
        if (!sp.isObject()) {
            return;
        }

        if ("start".equals(event)) {
            copyInParams(sp.path("inParams"), execution);
        } else if ("end".equals(event)) {
            copyOutParams(sp.path("outParams"), execution);
        }
    }

    /**
     * 解析 subProcess 配置块：优先按当前 activityId 精确匹配（挂在 subProcess 上的 start 监听器）；
     * end 监听器注入在子流程内部结束事件上，activityId 是 endEvent 的 id，
     * 此时遍历找带 subProcess 块的节点配置（单流程一般仅一个内嵌子流程）。
     */
    private JsonNode resolveSubProcessConfig(String processDefinitionId, String activityId) {
        JsonNode exact = readConfig(processDefinitionId, activityId).path("subProcess");
        if (exact.isObject()) {
            return exact;
        }
        try {
            for (NodeConfig nc : nodeConfigRepository.findByProcessDefinitionId(processDefinitionId)) {
                JsonNode sp = objectMapper.readTree(nc.getConfigJson()).path("subProcess");
                if (sp.isObject()) {
                    return sp;
                }
            }
        } catch (Exception e) {
            log.warn("SubProcessVariableListener: 遍历查找 subProcess 配置失败 defId={}: {}",
                    processDefinitionId, e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    /** start：父级流程变量 → 子流程局部变量（隔离子流程内外同名变量）。 */
    private void copyInParams(JsonNode inParams, DelegateExecution execution) {
        if (!inParams.isArray()) {
            return;
        }
        for (JsonNode mapping : inParams) {
            String source = textAt(mapping, "source");
            String target = textAt(mapping, "target");
            if (source == null || target == null) {
                continue;
            }
            String varName = source.startsWith("variable:") ? source.substring("variable:".length()) : source;
            execution.setVariableLocal(target, execution.getVariable(varName));
        }
    }

    /**
     * end：子流程局部变量 → 流程变量。
     * ⚠️ 用 getVariable（沿作用域链）而非 getVariableLocal：end 事件触发的 execution
     * 可能与 start 时写局部变量的 scope execution 不同级，getVariableLocal 会读空
     * （innerVal 写入成功但回写失败的真实案例）；局部变量在作用域链查找中可见。
     */
    private void copyOutParams(JsonNode outParams, DelegateExecution execution) {
        if (!outParams.isArray()) {
            return;
        }
        for (JsonNode mapping : outParams) {
            String source = textAt(mapping, "source");
            String target = textAt(mapping, "target");
            if (source == null || target == null) {
                continue;
            }
            execution.setVariable(target, execution.getVariable(source));
        }
    }

    private JsonNode readConfig(String processDefinitionId, String activityId) {
        try {
            List<NodeConfig> configs = nodeConfigRepository.findByProcessDefinitionId(processDefinitionId);
            for (NodeConfig nc : configs) {
                if (activityId.equals(nc.getNodeId())) {
                    return objectMapper.readTree(nc.getConfigJson());
                }
            }
        } catch (Exception e) {
            log.warn("SubProcessVariableListener: 读取 NodeConfig 失败 nodeId={}: {}",
                    activityId, e.getMessage());
        }
        return objectMapper.createObjectNode();
    }

    private String textAt(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            return null;
        }
        return v.asText().trim();
    }
}
