package com.workflow.engine.task;

import com.workflow.api.dto.OperationsConfig;
import com.workflow.common.exception.BusinessException;
import com.workflow.engine.form.mapping.VariableMappingWriter;
import com.workflow.engine.history.entity.WfTaskComment;
import com.workflow.engine.history.repository.WfTaskCommentRepository;
import com.workflow.engine.process.bpmn.InitiatorNodeResolver;
import com.workflow.engine.process.config.NodeOptions;
import com.workflow.engine.process.config.NodeOptionsService;
import com.workflow.engine.process.config.ProcessPolicy;
import com.workflow.engine.process.entity.NodeConfig;
import com.workflow.engine.process.repository.NodeConfigRepository;
import com.workflow.engine.tenant.TenantProvider;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 流程驳回服务。
 *
 * <p>使用 Flowable changeActivityState API 将当前任务节点移回发起人节点。
 * 支持 MI 节点整体回退（MI parallel 的 changeActivityState 会取消全部子实例）。
 *
 * <p>驳回时设置流程变量 rejected=true，触发 multi-instance 节点的
 * completionCondition 终止多实例活动（三种模式：会签/或签/依次审批）。
 */
@Service
public class RejectService {

    private static final Logger log = LoggerFactory.getLogger(RejectService.class);

    private final TaskService flowableTaskService;
    private final RuntimeService runtimeService;
    private final InitiatorNodeResolver initiatorNodeResolver;
    private final TenantProvider tenantProvider;
    private final WfTaskCommentRepository commentRepository;
    private final VariableMappingWriter variableMappingWriter;
    private final NodeOptionsService nodeOptionsService;
    private final NodeConfigRepository nodeConfigRepository;
    private final HistoryService historyService;
    private final com.workflow.engine.logicflow.service.LogicFlowApprovalTrigger logicFlowApprovalTrigger;

    public RejectService(TaskService flowableTaskService,
                         RuntimeService runtimeService,
                         InitiatorNodeResolver initiatorNodeResolver,
                         TenantProvider tenantProvider,
                         WfTaskCommentRepository commentRepository,
                         VariableMappingWriter variableMappingWriter,
                         NodeOptionsService nodeOptionsService,
                         NodeConfigRepository nodeConfigRepository,
                         HistoryService historyService,
                         com.workflow.engine.logicflow.service.LogicFlowApprovalTrigger logicFlowApprovalTrigger) {
        this.flowableTaskService = flowableTaskService;
        this.runtimeService = runtimeService;
        this.initiatorNodeResolver = initiatorNodeResolver;
        this.tenantProvider = tenantProvider;
        this.commentRepository = commentRepository;
        this.variableMappingWriter = variableMappingWriter;
        this.nodeOptionsService = nodeOptionsService;
        this.nodeConfigRepository = nodeConfigRepository;
        this.historyService = historyService;
        this.logicFlowApprovalTrigger = logicFlowApprovalTrigger;
    }

    /**
     * 驳回任务（退回到发起人节点，兼容旧调用）。
     */
    @Transactional
    public void reject(String taskId, String userId, String reason) {
        reject(taskId, userId, reason, null);
    }

    /**
     * 查询可退回的历史节点候选（chooseStartNode 退回模式用）。
     *
     * <p>候选 = 本实例历史已产生任务的节点（去重，排除当前节点），
     * 按最后办理时间倒序。仅当当前节点的 returnOptions.chooseStartNode=true 时返回非空列表，
     * 否则返回空列表（前端据此回落「退回发起人」默认行为）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, String>> returnCandidates(String taskId) {
        Task task = flowableTaskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();
        if (task == null) {
            throw new IllegalStateException("Task not found: " + taskId);
        }
        String processDefinitionId = task.getProcessDefinitionId();
        NodeOptions nodeOpts = nodeOptionsService
                .find(processDefinitionId, task.getTaskDefinitionKey()).orElse(null);
        if (nodeOpts == null || !Boolean.TRUE.equals(nodeOpts.getChooseStartNode())) {
            return List.of();
        }

        // 历史节点：本实例全部已办结任务，按节点去重，排除当前节点
        Map<String, String> candidates = new LinkedHashMap<>();
        for (HistoricTaskInstance t : historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .finished()
                .orderByHistoricTaskInstanceEndTime()
                .desc()
                .list()) {
            String key = t.getTaskDefinitionKey();
            if (key == null || key.isBlank() || candidates.containsKey(key)
                    || key.equals(task.getTaskDefinitionKey())) {
                continue;
            }
            String name = t.getName() == null || t.getName().isBlank() ? key : t.getName();
            candidates.put(key, name);
        }
        return candidates.entrySet().stream()
                .map(e -> Map.of("nodeId", e.getKey(), "nodeName", e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 驳回任务到指定节点。
     *
     * @param taskId       当前任务 ID
     * @param userId       操作人
     * @param reason       驳回原因
     * @param targetNodeId 退回目标节点；null = 发起人节点（旧行为）。
     *                     非 null 时要求当前节点开启 returnOptions.chooseStartNode，
     *                     且目标必须是本实例历史中出现过的节点。
     */
    @Transactional
    public void reject(String taskId, String userId, String reason, String targetNodeId) {
        Task task = flowableTaskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

        if (task == null) {
            throw new IllegalStateException("Task not found: " + taskId);
        }

        String processDefinitionId = task.getProcessDefinitionId();
        String currentActivityId = task.getTaskDefinitionKey();

        // 退回目标：指定历史节点（chooseStartNode 模式）或发起人节点（默认）
        String resolvedTarget = resolveTargetNodeId(task, processDefinitionId, currentActivityId, targetNodeId);

        // Task 61/65 退回门禁（对齐 NodeJS returnTask）：allowReturn/allowReject 权限
        // + 意见必填节点退回理由也必填
        OperationsConfig operations = extractOperationsFor(processDefinitionId, currentActivityId);
        if (!operations.isAllowReturn() && !operations.isAllowReject()) {
            throw new BusinessException(400, "该节点不允许退回");
        }
        NodeOptions nodeOpts = nodeOptionsService
                .find(processDefinitionId, currentActivityId).orElse(null);
        // Task 69：退回理由必填 = 节点级 commentRequired OR 流程级 commentPolicy(enabled)
        //（REJECT_RETURN 与 ALL 都覆盖退回）
        ProcessPolicy policy = loadProcessPolicy(processDefinitionId);
        boolean rejectCommentRequired =
                (nodeOpts != null && Boolean.TRUE.equals(nodeOpts.getCommentRequired()))
                        || policy.isCommentPolicyEnabled();
        if (rejectCommentRequired && (reason == null || reason.isBlank())) {
            String label = task.getName() == null || task.getName().isBlank()
                    ? currentActivityId : task.getName();
            throw new BusinessException(400, "审批意见必填（节点「" + label + "」）");
        }

        log.info("驳回任务 taskId={} userId={} reason={} 从 {} → {}",
                taskId, userId, reason, currentActivityId, resolvedTarget);

        // 设置拒绝标记，触发 multi-instance completionCondition 终止多实例；
        // 同时写 approved=false，供配置了通过比例/票签的下游网关按拒绝分支流转
        runtimeService.setVariable(task.getProcessInstanceId(), "rejected", true);
        runtimeService.setVariable(task.getProcessInstanceId(), "approved", false);

        // Task 69 退回免审（retakeSkipApproved）：收集本实例已通过任务的节点 ID（去重、排除退回目标节点），
        // 写入 __retakeApprovedNodes（对齐 NodeJS EngineRuntime.reject）；
        // ⚠️ 必须在 changeState **之前**写入，重走节点的 create 监听器才能读到；
        // 退回时点前已完成才算「已通过」，changeState 撤销中的任务此刻还未 finish，天然不会误收。
        if (policy.isRetakeSkipApproved()) {
            runtimeService.setVariable(task.getProcessInstanceId(),
                    WorkflowTaskService.VAR_RETAKE_APPROVED_NODES,
                    collectApprovedNodes(processDefinitionId, resolvedTarget));
        }

        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(currentActivityId, resolvedTarget)
                .changeState();

        // 写入审批意见
        if (userId != null) {
            WfTaskComment comment = new WfTaskComment();
            comment.setId(UUID.randomUUID().toString().replace("-", ""));
            comment.setTenantId(tenantProvider.getTenantId());
            comment.setTaskId(taskId);
            comment.setProcessInstanceId(task.getProcessInstanceId());
            comment.setUserId(userId);
            comment.setAction("reject");
            comment.setComment(reason);
            commentRepository.save(comment);
        }

        // 流程变量映射写入（驳回后发起人重新填报，变量随新表单数据刷新）
        try {
            variableMappingWriter.write(processDefinitionId, task.getProcessInstanceId());
        } catch (Exception e) {
            log.warn("Failed to write variable mappings after reject task [{}]: {}", taskId, e.getMessage());
        }

        // 驳回退回后触发逻辑编排（AFTER_TASK_RETURN；失败语义由绑定 executionMode 决定）
        logicFlowApprovalTrigger.onTaskReturned(task.getProcessInstanceId(), taskId, userId, reason);
    }

    /**
     * 解析退回目标节点。
     *
     * <p>targetNodeId 为空 → 发起人节点（旧行为）；非空 → 校验
     * chooseStartNode 门禁 + 目标必须是本实例历史中出现过的节点。
     */
    private String resolveTargetNodeId(Task task, String processDefinitionId,
                                       String currentActivityId, String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            String initiatorNodeId = initiatorNodeResolver.resolve(processDefinitionId);
            if (initiatorNodeId == null) {
                throw new IllegalStateException(
                        "Initiator node not found for process definition: " + processDefinitionId);
            }
            if (currentActivityId.equals(initiatorNodeId)) {
                throw new IllegalStateException(
                        "Cannot reject: current node is already the initiator node");
            }
            return initiatorNodeId;
        }

        String target = targetNodeId.trim();
        if (currentActivityId.equals(target)) {
            throw new BusinessException(400, "退回目标不能是当前节点");
        }
        NodeOptions nodeOpts = nodeOptionsService
                .find(processDefinitionId, currentActivityId).orElse(null);
        if (nodeOpts == null || !Boolean.TRUE.equals(nodeOpts.getChooseStartNode())) {
            throw new BusinessException(400, "该节点未开启「由审批人选择重审的起始节点」");
        }
        // 目标必须在本实例历史中出现过（已产生过任务）
        long count = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .taskDefinitionKey(target)
                .count();
        if (count <= 0) {
            throw new BusinessException(400, "退回目标节点不在本流程实例的历史节点中");
        }
        return target;
    }

    /**
     * 收集本实例已通过任务的节点 ID（去重、排除退回目标节点；对齐 NodeJS reject() 的
     * __retakeApprovedNodes 收集口径）。
     *
     * <p>⚠️ 只认正常办结的任务（deleteReason 为空）——被驳回/撤回撤销的任务虽然
     * 在历史里也带 end_time，但不算「已通过」。
     */
    private List<String> collectApprovedNodes(String processDefinitionId, String excludeNodeId) {
        Set<String> nodeIds = new LinkedHashSet<>();
        for (HistoricTaskInstance t : historyService.createHistoricTaskInstanceQuery()
                .processDefinitionId(processDefinitionId)
                .finished()
                .list()) {
            if (t.getDeleteReason() != null) {
                continue;
            }
            if (t.getTaskDefinitionKey() != null) {
                nodeIds.add(t.getTaskDefinitionKey());
            }
        }
        nodeIds.remove(excludeNodeId);
        return new ArrayList<>(nodeIds);
    }

    /** 流程级策略读取（{@code __PROCESS__} config_json；未配置/失败返回全关默认）。 */
    private ProcessPolicy loadProcessPolicy(String processDefinitionId) {
        try {
            for (NodeConfig nc : nodeConfigRepository.findByProcessDefinitionId(processDefinitionId)) {
                if (ProcessPolicy.PROCESS_LEVEL_NODE_ID.equals(nc.getNodeId())) {
                    return ProcessPolicy.parseProcessPolicy(nc.getConfigJson());
                }
            }
            return ProcessPolicy.parseProcessPolicy(null);
        } catch (Exception e) {
            log.warn("读取流程级策略失败 defId={}: {}", processDefinitionId, e.getMessage());
            return ProcessPolicy.parseProcessPolicy(null);
        }
    }

    /**
     * 读取节点/流程两级 operations 并 AND 合成（复用 WorkflowTaskService 同名口径；
     * 此处独立实现避免服务间环依赖）。
     */
    private OperationsConfig extractOperationsFor(String processDefinitionId, String taskDefinitionKey) {
        OperationsConfig nodeLevel = new OperationsConfig();
        try {
            List<NodeConfig> configs = nodeConfigRepository.findByProcessDefinitionId(processDefinitionId);
            OperationsConfig processLevel = new OperationsConfig();
            boolean hasProcessLevel = false;
            for (NodeConfig nc : configs) {
                if ("__PROCESS__".equals(nc.getNodeId())) {
                    processLevel = parseProcessOperationsFor(nc.getConfigJson());
                    hasProcessLevel = true;
                } else if (taskDefinitionKey.equals(nc.getNodeId())) {
                    nodeLevel = parseOperationsFor(nc.getConfigJson());
                }
            }
            if (!hasProcessLevel) {
                return nodeLevel;
            }
            OperationsConfig result = new OperationsConfig();
            result.setAllowReturn(processLevel.isAllowReturn() && nodeLevel.isAllowReturn());
            result.setAllowReject(processLevel.isAllowReject() && nodeLevel.isAllowReject());
            return result;
        } catch (Exception e) {
            log.warn("读取退回门禁操作配置失败 defId={}: {}", processDefinitionId, e.getMessage());
            return nodeLevel;
        }
    }

    /** 节点级 operations 解析（仅退回门禁关心的键）。 */
    private OperationsConfig parseOperationsFor(String configJson) {
        OperationsConfig result = new OperationsConfig();
        try {
            com.fasterxml.jackson.databind.JsonNode ops =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(configJson).get("operations");
            if (ops != null && ops.isObject()) {
                if (ops.has("allowReturn")) result.setAllowReturn(ops.get("allowReturn").asBoolean());
                if (ops.has("allowReject")) result.setAllowReject(ops.get("allowReject").asBoolean());
            }
        } catch (Exception ignored) {
            // 解析失败保持默认（全开）
        }
        return result;
    }

    /** 流程级 operations 解析（__PROCESS__ 节点 approvalPolicy.operations）。 */
    private OperationsConfig parseProcessOperationsFor(String configJson) {
        OperationsConfig result = new OperationsConfig();
        try {
            com.fasterxml.jackson.databind.JsonNode ops = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(configJson).path("approvalPolicy").path("operations");
            if (ops.isObject()) {
                if (ops.has("allowReturn")) result.setAllowReturn(ops.get("allowReturn").asBoolean());
                if (ops.has("allowReject")) result.setAllowReject(ops.get("allowReject").asBoolean());
            }
        } catch (Exception ignored) {
            // 解析失败保持默认（全开）
        }
        return result;
    }
}
