package com.workflow.engine.process.bpmn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.engine.logicflow.entity.LogicFlowDef;
import com.workflow.engine.logicflow.repository.LogicFlowDefRepository;
import com.workflow.engine.tenant.TenantProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BPMN XML 多实例改写器。
 *
 * <p>在部署前扫描 BPMN XML 中的 userTask，根据 NodeConfig 的 approval.multiMode 配置，
 * 将普通 userTask 改写为 MI parallel（会签/或签/票签）：
 * <ul>
 *   <li>countersign（会签）：completionCondition = nrOfCompletedInstances == nrOfInstances；
 *       配置 approval.passRatio（1-99）后按权重比例通过</li>
 *   <li>or_sign（或签）：completionCondition = nrOfCompletedInstances >= 1</li>
 *   <li>vote_sign（票签）：权重投票（approval.weights + votePassRatio），按累计权重达标通过</li>
 * </ul>
 *
 * <p>同时处理 serviceTask 的部署改写：审批面板配置的 Java Bean/表达式实现、
 * 异步执行（flowable:async）与失败重试周期（flowable:failedJobRetryTimeCycle）。
 *
 * <p>改写器假设 userTask 已有 flowable:assignee 属性，改写时添加
 * multiInstanceLoopCharacteristics 子元素，collection 绑定到 ${approverList}，
 * elementVariable 绑定到 approver。
 */
@Component
public class MultiInstanceBpmnRewriter {

    private static final Logger log = LoggerFactory.getLogger(MultiInstanceBpmnRewriter.class);

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final String FLOWABLE_PREFIX = "flowable";

    private static final String COLLECTION_EXPR = "${approverList}";
    private static final String ELEMENT_VAR = "approver";
    private static final String ELEMENT_VAR_EXPR = "${approver}";
    private static final String COUNTERSIGN_CONDITION = "${rejected || (nrOfCompletedInstances == nrOfInstances)}";
    private static final String OR_SIGN_CONDITION = "${rejected || (nrOfCompletedInstances >= 1)}";
    /** 比例通过（会签配 passRatio / 票签）公共条件：权重达标通过，或全部完成，或被驳回。 */
    private static final String WEIGHT_PASS_CONDITION =
            "${rejected || (voteApprovedWeight >= votePassWeight) || (nrOfCompletedInstances == nrOfInstances)}";

    private final ObjectMapper objectMapper;
    /** logicflow 服务任务：部署期校验被引用逻辑流存在且不含同步阻塞节点（DELAY/LLM）。 */
    private final LogicFlowDefRepository logicFlowDefRepository;
    private final TenantProvider tenantProvider;

    public MultiInstanceBpmnRewriter(ObjectMapper objectMapper,
                                     LogicFlowDefRepository logicFlowDefRepository,
                                     TenantProvider tenantProvider) {
        this.objectMapper = objectMapper;
        this.logicFlowDefRepository = logicFlowDefRepository;
        this.tenantProvider = tenantProvider;
    }

    /**
     * 改写 BPMN XML，根据 nodeConfigs 添加多实例特性。
     *
     * @param bpmnXml    原始 BPMN XML
     * @param nodeConfigs nodeId → configJson 字符串
     * @return 改写后的 BPMN XML（无配置变更则原样返回）
     */
    public String rewrite(String bpmnXml, Map<String, String> nodeConfigs) {
        if (bpmnXml == null || bpmnXml.isBlank() || nodeConfigs == null || nodeConfigs.isEmpty()) {
            return bpmnXml;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

            boolean modified = false;
            NodeList userTasks = doc.getElementsByTagNameNS(BPMN_NS, "userTask");

            for (int i = 0; i < userTasks.getLength(); i++) {
                Element userTask = (Element) userTasks.item(i);
                String nodeId = userTask.getAttribute("id");
                if (nodeId == null || nodeId.isEmpty()) {
                    continue;
                }

                String configJson = nodeConfigs.get(nodeId);
                if (configJson == null || configJson.isBlank()) {
                    continue;
                }

                // 任务创建行为监听器（Task 61：自动审批/去重/找不到人策略/类型化解析/短信通知）
                // 对所有有配置的 userTask 注入（含单实例与多实例），旧配置块缺失时监听器内部直接放行
                injectTaskCreateListener(doc, userTask);

                String multiMode = extractMultiMode(configJson);
                if (multiMode != null) {
                    Integer passRatio = extractPassRatio(configJson, multiMode);
                    applyMultiInstance(doc, userTask, multiMode, passRatio);
                    modified = true;
                    log.debug("改写 userTask [{}] 为多实例模式: {}", nodeId, multiMode);
                    continue;
                }

                // 单实例节点：根据 approval.userIds 设置审批人
                // 1 人 → flowable:assignee；多人 → flowable:candidateUsers
                List<String> userIds = extractUserIds(configJson);
                if (userIds.isEmpty()) {
                    modified = true;
                    continue;
                }
                applySingleAssignee(doc, userTask, userIds);
                modified = true;
                log.debug("改写 userTask [{}] 单实例审批人: {}", nodeId, userIds);
            }

            // serviceTask 改写：实现注入（java/expression/logicflow）+ 异步 + 失败重试周期
            NodeList serviceTasks = doc.getElementsByTagNameNS(BPMN_NS, "serviceTask");
            for (int i = 0; i < serviceTasks.getLength(); i++) {
                Element serviceTask = (Element) serviceTasks.item(i);
                String nodeId = serviceTask.getAttribute("id");
                if (nodeId == null || nodeId.isEmpty()) {
                    continue;
                }
                String configJson = nodeConfigs.get(nodeId);
                if (configJson == null || configJson.isBlank()) {
                    continue;
                }
                if (applyServiceTask(doc, serviceTask, configJson)) {
                    modified = true;
                }
            }

            // 内嵌子流程改写：变量绑定（subProcess.inParams/outParams → 执行监听器）
            NodeList subProcesses = doc.getElementsByTagNameNS(BPMN_NS, "subProcess");
            for (int i = 0; i < subProcesses.getLength(); i++) {
                Element subProcess = (Element) subProcesses.item(i);
                String nodeId = subProcess.getAttribute("id");
                if (nodeId == null || nodeId.isEmpty()) {
                    continue;
                }
                String configJson = nodeConfigs.get(nodeId);
                if (configJson == null || configJson.isBlank()) {
                    continue;
                }
                if (applySubProcessVariables(doc, subProcess, configJson)) {
                    modified = true;
                }
            }

            if (!modified) {
                return bpmnXml;
            }

            return serializeDocument(doc);
        } catch (Exception e) {
            log.warn("BPMN XML 改写失败，返回原始 XML: {}", e.getMessage());
            return bpmnXml;
        }
    }

    /**
     * 注入任务创建行为监听器（create 事件）：自动审批类型、去重、找不到人策略、
     * 类型化审批人解析（initiator_self/initiator_select/role/expression）、短信通知落库。
     */
    private void injectTaskCreateListener(Document doc, Element userTask) {
        Element extensionElements = ensureExtensionElements(doc, userTask);
        // 防重复注入
        NodeList existingListeners = extensionElements.getChildNodes();
        for (int i = 0; i < existingListeners.getLength(); i++) {
            org.w3c.dom.Node child = existingListeners.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                if ("taskListener".equals(el.getLocalName())
                        && "${taskCreateBehaviorListener}".equals(el.getAttribute("delegateExpression"))) {
                    return;
                }
            }
        }
        Element listener = doc.createElementNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":taskListener");
        listener.setAttribute("event", "create");
        listener.setAttribute("delegateExpression", "${taskCreateBehaviorListener}");
        extensionElements.appendChild(listener);
    }

    private String extractMultiMode(String configJson) {
        try {
            JsonNode root = objectMapper.readTree(configJson);
            JsonNode approval = root.path("approval");
            if (approval.isMissingNode() || !approval.isObject()) {
                return null;
            }
            JsonNode modeNode = approval.path("multiMode");
            if (modeNode.isMissingNode() || modeNode.isNull()) {
                return null;
            }
            String mode = modeNode.asText();
            return ("countersign".equals(mode) || "or_sign".equals(mode) || "sequential".equals(mode)
                    || "vote_sign".equals(mode)) ? mode : null;
        } catch (Exception e) {
            log.debug("解析 NodeConfig JSON 失败，跳过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取通过比例（权重达标通过模式的门槛）：
     * <ul>
     *   <li>vote_sign（票签）：votePassRatio（1-100，缺省 50）</li>
     *   <li>countersign（会签）：passRatio（1-99 生效；缺省/100 返回 null = 全员通过旧语义）</li>
     *   <li>其他模式：null（使用原 completionCondition）</li>
     * </ul>
     */
    private Integer extractPassRatio(String configJson, String multiMode) {
        if (!"vote_sign".equals(multiMode) && !"countersign".equals(multiMode)) {
            return null;
        }
        try {
            JsonNode approval = objectMapper.readTree(configJson).path("approval");
            if ("vote_sign".equals(multiMode)) {
                JsonNode ratio = approval.path("votePassRatio");
                if (ratio.isNumber() && ratio.asDouble() >= 1 && ratio.asDouble() <= 100) {
                    return (int) Math.round(ratio.asDouble());
                }
                return 50;
            }
            JsonNode ratio = approval.path("passRatio");
            if (ratio.isNumber() && ratio.asDouble() >= 1 && ratio.asDouble() <= 99) {
                return (int) Math.round(ratio.asDouble());
            }
            return null;
        } catch (Exception e) {
            log.debug("解析通过比例失败: {}", e.getMessage());
            return "vote_sign".equals(multiMode) ? 50 : null;
        }
    }

    /**
     * 从 configJson 提取 approval.userIds（审批人 ID 列表）。
     */
    private List<String> extractUserIds(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(configJson);
            JsonNode approval = root.path("approval");
            if (approval.isMissingNode() || !approval.isObject()) {
                return List.of();
            }
            JsonNode userIdsNode = approval.path("userIds");
            List<String> userIds = new ArrayList<>();
            if (userIdsNode.isArray()) {
                for (JsonNode idNode : userIdsNode) {
                    if (idNode.isTextual() || idNode.isNumber()) {
                        String id = idNode.asText();
                        if (id != null && !id.isBlank()) {
                            userIds.add(id.trim());
                        }
                    }
                }
            }
            return userIds;
        } catch (Exception e) {
            log.debug("解析 NodeConfig approval.userIds 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 为单实例 userTask 设置审批人：
     * 1 人 → flowable:assignee="5"；
     * 多人 → flowable:candidateUsers="5,6"（任一候选人可办理）。
     */
    private void applySingleAssignee(Document doc, Element userTask, List<String> userIds) {
        String joined = String.join(",", userIds);
        if (userIds.size() == 1) {
            userTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":assignee", userIds.get(0));
        } else {
            userTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":candidateUsers", joined);
        }
        log.debug("单实例 userTask [{}] 设置审批人: {}", userTask.getAttribute("id"), joined);
    }

    private void applyMultiInstance(Document doc, Element userTask, String multiMode, Integer passRatio) {
        // 如果已有 multiInstanceLoopCharacteristics，不重复添加
        NodeList existing = userTask.getElementsByTagNameNS(BPMN_NS, "multiInstanceLoopCharacteristics");
        if (existing.getLength() > 0) {
            return;
        }

        // 设置 userTask 的 assignee 为 ${approver}（多实例 elementVariable），
        // 这样每个实例会自动分配给对应的审批人
        userTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":assignee", ELEMENT_VAR_EXPR);

        // 添加 extensionElements → executionListener（start 事件设置 approverList 变量）
        Element extensionElements = ensureExtensionElements(doc, userTask);
        Element listener = doc.createElementNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":executionListener");
        listener.setAttribute("event", "start");
        listener.setAttribute("delegateExpression", "${multiInstanceApproverListener}");
        extensionElements.appendChild(listener);

        // 比例通过（会签 passRatio / 票签 vote_sign）：注入任务完成监听器累计同意权重
        // 并在权重达标时写 approved 变量（下游排他网关可按 ${approved} 分支）
        boolean weightPassMode = passRatio != null;
        if (weightPassMode) {
            Element voteListener = doc.createElementNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":taskListener");
            voteListener.setAttribute("event", "complete");
            voteListener.setAttribute("delegateExpression", "${voteSignCompleteListener}");
            extensionElements.appendChild(voteListener);
        }

        Element miLoop = doc.createElementNS(BPMN_NS, "bpmn:multiInstanceLoopCharacteristics");
        boolean isSequential = "sequential".equals(multiMode);
        miLoop.setAttribute("isSequential", String.valueOf(isSequential));
        miLoop.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":collection", COLLECTION_EXPR);
        miLoop.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":elementVariable", ELEMENT_VAR);

        Element completionCondition = doc.createElementNS(BPMN_NS, "bpmn:completionCondition");
        // 依次审批（sequential）和会签（countersign）都要求全部完成，或签（or_sign）只需一人通过；
        // 配置通过比例/票签后按权重达标（或全部完成）即结束
        String condition;
        if (weightPassMode) {
            condition = WEIGHT_PASS_CONDITION;
        } else {
            condition = "or_sign".equals(multiMode) ? OR_SIGN_CONDITION : COUNTERSIGN_CONDITION;
        }
        completionCondition.setTextContent(condition);

        miLoop.appendChild(completionCondition);

        // multiInstanceLoopCharacteristics 必须插入到 incoming/outgoing 之后，
        // BPMN XSD 顺序：extensionElements? → incoming* → outgoing* → ioSpecification? → ... → loopCharacteristics? → rendering*
        Element insertBefore = null;
        NodeList children = userTask.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String ns = child.getNamespaceURI();
            String localName = child.getLocalName();
            // 跳过 extensionElements、incoming、outgoing（这些都排在 miLoop 之前）
            if (BPMN_NS.equals(ns) && ("extensionElements".equals(localName)
                    || "incoming".equals(localName)
                    || "outgoing".equals(localName))) {
                continue;
            }
            // flowable:extensionElements（旧命名空间兼容）
            if (FLOWABLE_NS.equals(ns) && "extensionElements".equals(localName)) {
                continue;
            }
            insertBefore = (Element) child;
            break;
        }
        if (insertBefore != null) {
            userTask.insertBefore(miLoop, insertBefore);
        } else {
            userTask.appendChild(miLoop);
        }
    }

    /**
     * 获取或创建元素的 extensionElements **直接子**元素。
     * ⚠️ 必须只查直接子元素：getElementsByTagNameNS 是递归查找，
     * 对容器类元素（如 subProcess）会错误命中内部子任务（userTask）的
     * extensionElements，导致监听器挂错节点（子流程监听器静默失效的真实案例）。
     *
     * <p>BPMN XSD 顺序：documentation* → extensionElements? → auditing? → ... → incoming* → outgoing* → ...
     * 所以 extensionElements 必须插入到第一个子元素之前。
     */
    private Element ensureExtensionElements(Document doc, Element parent) {
        // 仅遍历直接子元素（extensionElements 用 BPMN 命名空间）
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && "extensionElements".equals(child.getLocalName())
                    && BPMN_NS.equals(child.getNamespaceURI())) {
                return (Element) child;
            }
        }

        // 使用 bpmn: 前缀确保序列化正确
        Element ext = doc.createElementNS(BPMN_NS, "bpmn:extensionElements");

        // 插入到第一个子元素之前
        Element firstChild = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                firstChild = (Element) child;
                break;
            }
        }
        parent.insertBefore(ext, firstChild);
        return ext;
    }

    /**
     * serviceTask 部署改写：
     * <ul>
     *   <li>实现注入：java → flowable:expression="${bean.method(execution)}"（仅 bean 时用
     *       delegateExpression）；expression → flowable:expression（原样）；http 无内置
     *       Delegate，跳过实现注入（由 backendLogic/逻辑编排承接），仅应用异步/重试</li>
     *   <li>异步：serviceTask.async=true 或配置了重试 → flowable:async="true"
     *       （重试依赖 Job 机制，必须异步）</li>
     *   <li>重试：serviceTask.retryCount>0 → flowable:failedJobRetryTimeCycle =
     *       R{retryCount}/PT{retryIntervalMinutes}M</li>
     * </ul>
     *
     * @return 是否有改动
     */
    private boolean applyServiceTask(Document doc, Element serviceTask, String configJson) {
        boolean modified = false;
        try {
            JsonNode st = objectMapper.readTree(configJson).path("serviceTask");
            if (!st.isObject()) {
                return false;
            }

            // 1. 实现注入（幂等：已有实现属性则不覆盖）
            boolean hasImplementation =
                    serviceTask.hasAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":expression")
                            || serviceTask.hasAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":delegateExpression")
                            || serviceTask.hasAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":class");
            if (!hasImplementation) {
                String serviceType = textAt(st, "serviceType");
                if ("java".equals(serviceType)) {
                    String beanName = textAt(st, "beanName");
                    String methodName = textAt(st, "methodName");
                    if (beanName != null) {
                        if (methodName != null) {
                            serviceTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":expression",
                                    "${" + beanName + "." + methodName + "(execution)}");
                        } else {
                            serviceTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":delegateExpression",
                                    "${" + beanName + "}");
                        }
                        modified = true;
                        log.debug("改写 serviceTask [{}] Java 实现: bean={}, method={}",
                                serviceTask.getAttribute("id"), beanName, methodName);
                    }
                } else if ("expression".equals(serviceType)) {
                    String expression = textAt(st, "expression");
                    if (expression != null) {
                        // 剥离 ${} 包裹（存储为 UEL 原文时兼容）
                        String body = expression.trim();
                        if (body.startsWith("${") && body.endsWith("}")) {
                            body = body.substring(2, body.length() - 1);
                        }
                        serviceTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":expression", "${" + body + "}");
                        modified = true;
                        log.debug("改写 serviceTask [{}] 表达式实现", serviceTask.getAttribute("id"));
                    }
                } else if ("http".equals(serviceType)) {
                    log.warn("serviceTask [{}] 类型为 http，引擎无内置 HTTP Delegate，"
                            + "请使用逻辑流服务任务或节点后端逻辑实现；仅应用异步/重试配置", serviceTask.getAttribute("id"));
                } else if ("logicflow".equals(serviceType)) {
                    // 调用逻辑流：注入 LogicFlowServiceTaskDelegate；部署期校验 flowKey 存在
                    // 且不含同步阻塞节点（DELAY/LLM），失败即阻断部署
                    String flowKey = textAt(st.path("logicFlow"), "flowKey");
                    if (flowKey == null) {
                        throw new IllegalStateException(
                                "服务任务「" + serviceTask.getAttribute("id") + "」为逻辑流类型但未配置 flowKey");
                    }
                    validateLogicFlowForSyncCall(flowKey, serviceTask.getAttribute("id"));
                    serviceTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":delegateExpression",
                            "${logicFlowServiceTaskDelegate}");
                    modified = true;
                    log.debug("改写 serviceTask [{}] 逻辑流调用: flowKey={}",
                            serviceTask.getAttribute("id"), flowKey);
                }
            }

            // 2. 重试（先于异步判断：重试隐含异步）
            JsonNode retryCountNode = st.get("retryCount");
            int retryCount = retryCountNode != null && retryCountNode.isNumber()
                    ? (int) Math.round(retryCountNode.asDouble()) : 0;
            if (retryCount > 0) {
                JsonNode intervalNode = st.get("retryIntervalMinutes");
                int intervalMinutes = intervalNode != null && intervalNode.isNumber()
                        && intervalNode.asDouble() >= 1
                        ? (int) Math.round(intervalNode.asDouble()) : 10;
                String cycle = "R" + retryCount + "/PT" + intervalMinutes + "M";
                Element ext = ensureExtensionElements(doc, serviceTask);
                NodeList existingCycles = ext.getElementsByTagNameNS(FLOWABLE_NS,
                        FLOWABLE_PREFIX + ":failedJobRetryTimeCycle");
                if (existingCycles.getLength() > 0) {
                    existingCycles.item(0).setTextContent(cycle);
                } else {
                    Element cycleEl = doc.createElementNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":failedJobRetryTimeCycle");
                    cycleEl.setTextContent(cycle);
                    ext.appendChild(cycleEl);
                }
                modified = true;
                log.debug("改写 serviceTask [{}] 失败重试周期: {}", serviceTask.getAttribute("id"), cycle);
            }

            // 3. 异步（配置 async=true 或配置了重试）
            boolean asyncConfigured = st.path("async").asBoolean(false);
            if (asyncConfigured || retryCount > 0) {
                if (!"true".equals(serviceTask.getAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":async"))) {
                    serviceTask.setAttributeNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":async", "true");
                    modified = true;
                    log.debug("改写 serviceTask [{}] 异步执行", serviceTask.getAttribute("id"));
                }
            }
        } catch (Exception e) {
            log.warn("serviceTask [{}] 配置解析失败，跳过: {}", serviceTask.getAttribute("id"), e.getMessage());
        }
        return modified;
    }

    /**
     * 内嵌子流程变量绑定改写：subProcess.inParams/outParams 非空时注入
     * executionListener → {@code ${subProcessVariableListener}}：
     * <ul>
     *   <li>start：注入到 subProcess 元素 —— inParams[{source(父级流程变量),
     *       target(子流程内变量)}] → 写子流程作用域局部变量；</li>
     *   <li>end：注入到子流程**内部的结束事件**（endEvent）—— outParams[{source(子流程内变量),
     *       target(流程变量)}] → 回写流程变量。</li>
     * </ul>
     * ⚠️ end 监听器不能放 subProcess 元素上：Flowable 对 subProcess 的 end listener
     * 在父 execution 上触发，届时子流程 scope 的局部变量已销毁，出参读空
     * （innerVal 写入成功而 subEcho 回写失败的真实案例）；挂在内部 endEvent 上时
     * 子流程作用域仍在，可正常读取局部变量。
     */
    private boolean applySubProcessVariables(Document doc, Element subProcess, String configJson) {
        try {
            JsonNode sp = objectMapper.readTree(configJson).path("subProcess");
            if (!sp.isObject()) {
                return false;
            }
            boolean hasIn = sp.path("inParams").isArray() && sp.path("inParams").size() > 0;
            boolean hasOut = sp.path("outParams").isArray() && sp.path("outParams").size() > 0;
            if (!hasIn && !hasOut) {
                return false;
            }
            // 已注入（幂等）：查 subProcess 自身与内部 endEvent 的监听器
            boolean injected = containsVariableListener(subProcess);
            NodeList endEvents = subProcess.getElementsByTagNameNS(BPMN_NS, "endEvent");
            if (!injected) {
                for (int i = 0; i < endEvents.getLength(); i++) {
                    if (containsVariableListener((Element) endEvents.item(i))) {
                        injected = true;
                        break;
                    }
                }
            }
            if (injected) {
                return false;
            }
            if (hasIn) {
                Element ext = ensureExtensionElements(doc, subProcess);
                Element startListener = doc.createElementNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":executionListener");
                startListener.setAttribute("event", "start");
                startListener.setAttribute("delegateExpression", "${subProcessVariableListener}");
                ext.appendChild(startListener);
            }
            if (hasOut) {
                // end 监听器注入到内部第一个结束事件（子流程作用域内最后触发，可读局部变量）
                boolean attached = false;
                for (int i = 0; i < endEvents.getLength(); i++) {
                    Element endEvent = (Element) endEvents.item(i);
                    Element endExt = ensureExtensionElements(doc, endEvent);
                    Element endListener = doc.createElementNS(FLOWABLE_NS, FLOWABLE_PREFIX + ":executionListener");
                    endListener.setAttribute("event", "end");
                    endListener.setAttribute("delegateExpression", "${subProcessVariableListener}");
                    endExt.appendChild(endListener);
                    attached = true;
                    break;
                }
                if (!attached) {
                    log.warn("subProcess [{}] 配置了出参绑定但没有结束事件，出参回写将被跳过",
                            subProcess.getAttribute("id"));
                }
            }
            log.debug("改写 subProcess [{}] 变量绑定: inParams={} outParams={}",
                    subProcess.getAttribute("id"), hasIn, hasOut);
            return true;
        } catch (Exception e) {
            log.warn("subProcess [{}] 变量绑定配置解析失败，跳过: {}",
                    subProcess.getAttribute("id"), e.getMessage());
            return false;
        }
    }

    /** 元素的直接 extensionElements 中是否已含 subProcessVariableListener。 */
    private boolean containsVariableListener(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE
                    || !"extensionElements".equals(child.getLocalName())) {
                continue;
            }
            NodeList listeners = child.getChildNodes();
            for (int j = 0; j < listeners.getLength(); j++) {
                Node l = listeners.item(j);
                if (l.getNodeType() == Node.ELEMENT_NODE
                        && "subProcessVariableListener".equals(((Element) l).getAttribute("delegateExpression"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String textAt(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            return null;
        }
        return v.asText().trim();
    }

    /**
     * logicflow 服务任务部署期校验：
     * <ul>
     *   <li>flowKey 在当前租户必须存在（草稿/已发布均可保存，运行时要求已发布）；</li>
     *   <li>DSL 不得包含 DELAY/LLM 节点——同步调用会以 Thread.sleep/同步大模型调用
     *       阻塞流程推进线程，长耗时编排应走异步执行（配置 async=true 同样拒绝：
     *       Job 线程同样被阻塞）。</li>
     * </ul>
     */
    private void validateLogicFlowForSyncCall(String flowKey, String serviceTaskId) {
        LogicFlowDef def;
        try {
            def = logicFlowDefRepository
                    .findByTenantIdAndFlowKey(tenantProvider.getTenantId(), flowKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "服务任务「" + serviceTaskId + "」引用的逻辑流不存在: " + flowKey));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 仓库查询异常（如租户上下文缺失）：记录并放行，运行时 runByKey 再兜底
            log.warn("logicflow 服务任务 [{}] 校验 flowKey={} 时查询失败: {}",
                    serviceTaskId, flowKey, e.getMessage());
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(def.getDslJson());
            JsonNode nodes = root.path("nodes");
            if (nodes.isArray()) {
                for (JsonNode node : nodes) {
                    String type = node.path("type").asText("");
                    if ("DELAY".equals(type) || "LLM".equals(type)) {
                        throw new IllegalStateException("逻辑流「" + flowKey + "」包含 "
                                + type + " 节点，不能被流程节点同步调用（DELAY/LLM 为纯阻塞执行），"
                                + "请拆分长耗时编排或改用逻辑流 SUBFLOW 串联");
                    }
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("logicflow 服务任务 [{}] 校验 DSL 失败（放行，运行时兜底）: {}",
                    serviceTaskId, e.getMessage());
        }
    }

    private String serializeDocument(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
