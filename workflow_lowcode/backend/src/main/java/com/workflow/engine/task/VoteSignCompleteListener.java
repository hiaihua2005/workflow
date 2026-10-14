package com.workflow.engine.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.engine.process.entity.NodeConfig;
import com.workflow.engine.process.repository.NodeConfigRepository;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多实例「权重达标通过」任务完成监听器（complete 事件）。
 *
 * <p>配合 MultiInstanceBpmnRewriter 注入到两类多实例节点的 userTask 上：
 * <ul>
 *   <li>会签 + approval.passRatio（1-99）：每票权重 1，同意票比例达标即通过</li>
 *   <li>票签 approval.multiMode=vote_sign：每票按 approval.weights 权重，
 *       同意权重 ≥ votePassWeight 即通过</li>
 * </ul>
 *
 * <p>职责：
 * <ol>
 *   <li>每完成一个审批实例，把该审批人权重累加到 {@code voteApprovedWeight}；</li>
 *   <li>判断「同意权重是否达标且未被驳回」，把结果写入 {@code approved} 布尔变量，
 *       供下游排他网关按 ${approved == true / false} 分支（面板预设条件已内置）。</li>
 * </ol>
 *
 * <p>注意：completionCondition 的权重达标判定由引擎基于同一变量完成，本监听器
 * 负责累加与结果落变量；未注入本监听器的节点（无比例配置）不受影响。
 */
@Component("voteSignCompleteListener")
public class VoteSignCompleteListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(VoteSignCompleteListener.class);

    private final NodeConfigRepository nodeConfigRepository;
    private final ObjectMapper objectMapper;

    public VoteSignCompleteListener(NodeConfigRepository nodeConfigRepository, ObjectMapper objectMapper) {
        this.nodeConfigRepository = nodeConfigRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void notify(DelegateTask delegateTask) {
        // DelegateTask 继承 VariableScope，直接读写流程变量
        // （votePassWeight 由 MultiInstanceApproverListener 在 MI 启动时写入）
        Object passWeightObj = delegateTask.getVariable("votePassWeight");
        if (!(passWeightObj instanceof Number)) {
            return;
        }

        try {
            String configJson = findConfigJson(delegateTask.getProcessDefinitionId(),
                    delegateTask.getTaskDefinitionKey());
            if (configJson == null) {
                return;
            }
            JsonNode approval = objectMapper.readTree(configJson).path("approval");
            String multiMode = approval.path("multiMode").asText(null);
            boolean voteMode = "vote_sign".equals(multiMode);
            boolean countersignRatioMode = "countersign".equals(multiMode)
                    && approval.path("passRatio").isNumber();

            if (!voteMode && !countersignRatioMode) {
                return;
            }

            // 本票权重：票签按配置表（缺省 1）；会签比例固定 1
            long weight = 1;
            if (voteMode) {
                String assignee = delegateTask.getAssignee();
                JsonNode weights = approval.path("weights");
                if (assignee != null && weights.isObject() && weights.path(assignee).isNumber()
                        && weights.path(assignee).asDouble() > 0) {
                    weight = Math.round(weights.path(assignee).asDouble());
                }
            }

            long approvedWeight = numberLong(delegateTask.getVariable("voteApprovedWeight"));
            approvedWeight += weight;
            delegateTask.setVariable("voteApprovedWeight", approvedWeight);

            boolean rejected = Boolean.TRUE.equals(delegateTask.getVariable("rejected"));
            boolean pass = !rejected && approvedWeight >= numberLong(passWeightObj);
            delegateTask.setVariable("approved", pass);

            log.info("VoteSignCompleteListener: taskId={} assignee={} weight={} approvedWeight={} "
                            + "passWeight={} approved={}",
                    delegateTask.getId(), delegateTask.getAssignee(), weight,
                    approvedWeight, numberLong(passWeightObj), pass);
        } catch (Exception e) {
            log.error("VoteSignCompleteListener 执行失败 taskId={}: {}",
                    delegateTask.getId(), e.getMessage(), e);
        }
    }

    private String findConfigJson(String processDefinitionId, String activityId) {
        if (processDefinitionId == null || activityId == null) {
            return null;
        }
        List<NodeConfig> configs = nodeConfigRepository.findByProcessDefinitionId(processDefinitionId);
        for (NodeConfig nc : configs) {
            if (activityId.equals(nc.getNodeId())) {
                return nc.getConfigJson();
            }
        }
        return null;
    }

    private long numberLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
