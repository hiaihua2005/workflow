package com.workflow.engine.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.engine.datasource.entity.DataSourceDefinition;
import com.workflow.engine.datasource.repository.DataSourceDefinitionRepository;
import com.workflow.engine.form.entity.FormDefinition;
import com.workflow.engine.form.repository.FormDefinitionRepository;
import com.workflow.engine.logicflow.dsl.LogicFlowDsl;
import com.workflow.engine.logicflow.dsl.NodeType;
import com.workflow.engine.logicflow.entity.FormLogicBinding;
import com.workflow.engine.logicflow.repository.FormLogicBindingRepository;
import com.workflow.engine.page.entity.PageDefinition;
import com.workflow.engine.page.repository.PageDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模块依赖闭包分析器（导出期）。
 *
 * <p>扫描当前租户内五类已发布元素的引用边，构建依赖图；导出选择集经 BFS 自动补全
 * 依赖闭包。无法打包的引用（HTTP 地址、系统内置数据源、物理表名直连）归入
 * {@code externalDeps}，仅提示不打包。
 *
 * <p>依赖边清单（唯一事实来源）：
 * <pre>
 * FORM        → FORM（schema 内 dataPicker/LookupPicker props.sourceFormKey）
 *             ⇄ LOGIC_FLOW（wf_form_logic_binding 行，随表单导出）
 * PAGE        → FORM（form_key）、DATA_SOURCE（data_source_id + schema.dataSources[].refId）
 * DATA_SOURCE → FORM（form_key；SYSTEM/API 类型为外部依赖不导出）
 * LOGIC_FLOW  → FORM（DATA_* 节点 config.formKey）、LOGIC_FLOW（SUBFLOW 节点 flowId/flowKey）
 *             → external：HTTP 节点 url、物理表名直连
 * WORKFLOW    → FORM（BPMN formKey + 节点配置 formKey）、LOGIC_FLOW（节点配置 flowKey）
 * </pre>
 */
@Component
public class ModuleDependencyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ModuleDependencyAnalyzer.class);

    /** BPMN XML 中 formKey 属性（flowable 命名空间或无前缀）。 */
    private static final Pattern BPMN_FORM_KEY = Pattern.compile("(?:flowable:)?formKey\\s*=\\s*\"([^\"]+)\"");

    private final FormDefinitionRepository formDefRepository;
    private final PageDefinitionRepository pageDefRepository;
    private final DataSourceDefinitionRepository dataSourceRepository;
    private final FormLogicBindingRepository bindingRepository;
    private final com.workflow.engine.logicflow.repository.LogicFlowDefRepository logicFlowRepository;
    private final com.workflow.engine.process.repository.ProcessDraftRepository processDraftRepository;
    private final com.workflow.engine.process.repository.NodeConfigRepository nodeConfigRepository;
    private final ObjectMapper objectMapper;

    public ModuleDependencyAnalyzer(FormDefinitionRepository formDefRepository,
                                    PageDefinitionRepository pageDefRepository,
                                    DataSourceDefinitionRepository dataSourceRepository,
                                    FormLogicBindingRepository bindingRepository,
                                    com.workflow.engine.logicflow.repository.LogicFlowDefRepository logicFlowRepository,
                                    com.workflow.engine.process.repository.ProcessDraftRepository processDraftRepository,
                                    com.workflow.engine.process.repository.NodeConfigRepository nodeConfigRepository,
                                    ObjectMapper objectMapper) {
        this.formDefRepository = formDefRepository;
        this.pageDefRepository = pageDefRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.bindingRepository = bindingRepository;
        this.logicFlowRepository = logicFlowRepository;
        this.processDraftRepository = processDraftRepository;
        this.nodeConfigRepository = nodeConfigRepository;
        this.objectMapper = objectMapper;
    }

    /** 图节点：元素引用 + 名称 + 依赖边 + 外部依赖。 */
    public record Node(String refKey, String name, Set<String> dependsOn, Set<String> externalDeps) {
    }

    /** 全量扫描结果。 */
    public record Graph(Map<String, Node> nodes) {
    }

    /** 闭包结果：补全后的选择集（LinkedHashSet 保持 BFS 序）。 */
    public record Closure(Set<String> refs) {
    }

    /**
     * 全量扫描当前租户的已发布元素，构建引用图。
     * FORM/PAGE 取 PUBLISHED 行；LOGIC_FLOW 取 PUBLISHED；DATA_SOURCE 取非 SYSTEM/API 的行；
     * WORKFLOW 取草稿（bpmn 在草稿上维护）。
     */
    public Graph scan(String tenantId) {
        Map<String, Node> nodes = new LinkedHashMap<>();

        // FORM（含 WORKFLOW 审批表单：BPMN formKey 可能引用）
        for (FormDefinition f : formDefRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId, org.springframework.data.domain.Pageable.unpaged())) {
            if (!"PUBLISHED".equals(f.getStatus())) {
                continue;
            }
            Set<String> deps = new LinkedHashSet<>();
            Set<String> external = new LinkedHashSet<>();
            collectFormSchemaRefs(f.getSchema(), f.getKey(), deps, external);
            // 触发绑定（随表单导出）：仅 BUSINESS 业务表单挂绑定
            if ("BUSINESS".equals(f.getType())) {
                for (FormLogicBinding b : bindingRepository
                        .findByTenantIdAndFormTypeAndFormKeyOrderByCreatedAtAsc(tenantId, "BUSINESS", f.getKey())) {
                    if (Boolean.TRUE.equals(b.getEnabled())) {
                        deps.add("LOGIC_FLOW:" + b.getFlowKey());
                    }
                }
            }
            nodes.put("FORM:" + f.getKey(), new Node("FORM:" + f.getKey(), f.getName(), deps, external));
        }

        // PAGE
        for (PageDefinition p : pageDefRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId, org.springframework.data.domain.Pageable.unpaged())) {
            if (!"PUBLISHED".equals(p.getStatus())) {
                continue;
            }
            Set<String> deps = new LinkedHashSet<>();
            Set<String> external = new LinkedHashSet<>();
            if (p.getFormKey() != null && !p.getFormKey().isBlank()) {
                deps.add("FORM:" + p.getFormKey());
            }
            // dataSourceId（id 引用 → 转 key 引用）与 schema 内 refId（同）
            Set<String> dsIds = new LinkedHashSet<>();
            if (p.getDataSourceId() != null && !p.getDataSourceId().isBlank()) {
                dsIds.add(p.getDataSourceId());
            }
            collectPageSchemaDsRefs(p.getSchema(), dsIds);
            for (String dsId : dsIds) {
                String ref = dataSourceIdToRef(tenantId, dsId);
                if (ref != null) {
                    deps.add(ref);
                } else {
                    external.add("DATA_SOURCE_ID:" + dsId);
                }
            }
            nodes.put("PAGE:" + p.getKey(), new Node("PAGE:" + p.getKey(), p.getName(), deps, external));
        }

        // DATA_SOURCE（FORM/WORKFLOW 类型可导出；SYSTEM/API 为平台/外部资源）
        for (DataSourceDefinition ds : dataSourceRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId, org.springframework.data.domain.Pageable.unpaged())) {
            String key = dsKeyOf(ds);
            if (key == null) {
                continue;
            }
            Set<String> deps = new LinkedHashSet<>();
            Set<String> external = new LinkedHashSet<>();
            if ("FORM".equals(ds.getType())) {
                if (ds.getFormKey() != null && !ds.getFormKey().isBlank()) {
                    deps.add("FORM:" + ds.getFormKey());
                } else {
                    external.add("FORM_ID:" + ds.getFormId());
                }
            }
            nodes.put("DATA_SOURCE:" + key, new Node("DATA_SOURCE:" + key, ds.getName(), deps, external));
        }

        // LOGIC_FLOW
        for (com.workflow.engine.logicflow.entity.LogicFlowDef lf : logicFlows(tenantId)) {
            Set<String> deps = new LinkedHashSet<>();
            Set<String> external = new LinkedHashSet<>();
            collectDslRefs(lf.getDslJson(), tenantId, deps, external);
            nodes.put("LOGIC_FLOW:" + lf.getFlowKey(),
                    new Node("LOGIC_FLOW:" + lf.getFlowKey(), lf.getName(), deps, external));
        }

        // WORKFLOW（草稿即权威源）
        for (com.workflow.engine.process.entity.ProcessDraft pd : processDrafts(tenantId)) {
            Set<String> deps = new LinkedHashSet<>();
            Set<String> external = new LinkedHashSet<>();
            collectBpmnRefs(pd.getBpmnXml(), deps);
            collectNodeConfigRefs(pd.getId(), tenantId, deps, external);
            nodes.put("WORKFLOW:" + pd.getKey(), new Node("WORKFLOW:" + pd.getKey(), pd.getName(), deps, external));
        }

        return new Graph(nodes);
    }

    /**
     * 从选择集出发 BFS 计算闭包（自动补全依赖）。图中不存在的引用（如依赖了
     * 未发布表单）原样保留并计入 missing（由调用方决定提示或拒绝）。
     */
    public Closure closure(Graph graph, Collection<String> selectedRefs) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        for (String ref : selectedRefs) {
            if (!visited.contains(ref)) {
                visited.add(ref);
                queue.add(ref);
            }
        }
        while (!queue.isEmpty()) {
            String ref = queue.removeFirst();
            Node node = graph.nodes().get(ref);
            if (node == null) {
                continue;
            }
            for (String dep : node.dependsOn()) {
                if (visited.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        return new Closure(visited);
    }

    // ------------------------------------------------------------------
    // 引用扫描（按实体）
    // ------------------------------------------------------------------

    /** 表单 schema：递归找 dataPicker/LookupPicker 组件的 props.sourceFormKey。 */
    private void collectFormSchemaRefs(String schema, String selfKey, Set<String> deps, Set<String> external) {
        if (schema == null || schema.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(schema);
            collectSourceFormKeys(root, deps);
        } catch (Exception e) {
            log.warn("表单 schema 解析失败（跳过引用扫描）: formKey={}, err={}", selfKey, e.getMessage());
            external.add("SCHEMA_PARSE_ERROR");
        }
    }

    /** 递归遍历 schema JSON：任何节点 props.sourceFormKey 视为对其他业务表单的引用。 */
    private void collectSourceFormKeys(JsonNode node, Set<String> deps) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode props = node.get("props");
            if (props != null && props.isObject()) {
                JsonNode sfn = props.get("sourceFormKey");
                if (sfn != null && sfn.isTextual() && !sfn.asText().isBlank()) {
                    deps.add("FORM:" + sfn.asText());
                }
            }
            node.forEach(child -> collectSourceFormKeys(child, deps));
        } else if (node.isArray()) {
            node.forEach(child -> collectSourceFormKeys(child, deps));
        }
    }

    /** 页面 schema（PAGE 类型）：收集 dataSources[].refId（数据源 id 引用）。 */
    private void collectPageSchemaDsRefs(String schema, Set<String> dsIds) {
        if (schema == null || schema.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(schema);
            JsonNode arr = root.path("dataSources");
            if (arr.isArray()) {
                for (JsonNode entry : arr) {
                    JsonNode refId = entry.get("refId");
                    if (refId != null && refId.isTextual() && !refId.asText().isBlank()) {
                        dsIds.add(refId.asText());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("页面 schema 解析失败（跳过 refId 扫描）: {}", e.getMessage());
        }
    }

    /** 数据源 id → 引用 key；找不到/不可导出类型返回 null（记 external）。 */
    private String dataSourceIdToRef(String tenantId, String dsId) {
        return dataSourceRepository.findByIdAndTenantId(dsId, tenantId)
                .map(this::dsKeyOf)
                .map(k -> "DATA_SOURCE:" + k)
                .orElse(null);
    }

    /** 数据源导出 key：FORM/WORKFLOW 用 formKey（=source_key），其余返回 null（不可导出）。 */
    private String dsKeyOf(DataSourceDefinition ds) {
        if ("FORM".equals(ds.getType()) || "WORKFLOW".equals(ds.getType())) {
            return ds.getFormKey() != null && !ds.getFormKey().isBlank() ? ds.getFormKey() : ds.getSourceKey();
        }
        return null;
    }

    /** 逻辑流 DSL：DATA_* 节点 formKey、SUBFLOW flowId/flowKey、HTTP url、BATCH body 递归。 */
    private void collectDslRefs(String dslJson, String tenantId, Set<String> deps, Set<String> external) {
        if (dslJson == null || dslJson.isBlank()) {
            return;
        }
        try {
            LogicFlowDsl dsl = LogicFlowDsl.parse(dslJson, objectMapper);
            for (LogicFlowDsl.NodeDef node : dsl.getNodes()) {
                if (node.getType() == null) {
                    continue;
                }
                scanNode(node.getType(), node.getConfig(), tenantId, deps, external);
            }
        } catch (Exception e) {
            log.warn("逻辑流 DSL 解析失败（跳过引用扫描）: {}", e.getMessage());
            external.add("DSL_PARSE_ERROR");
        }
    }

    private void scanNode(NodeType type, JsonNode config, String tenantId,
                          Set<String> deps, Set<String> external) {
        if (config == null || config.isMissingNode() || config.isNull()) {
            return;
        }
        switch (type) {
            case HTTP -> {
                JsonNode url = config.get("url");
                if (url != null && url.isTextual() && !url.asText().isBlank()) {
                    external.add("HTTP:" + url.asText());
                }
            }
            case SUBFLOW -> {
                String ref = textOrNull(config, "flowKey");
                if (ref == null) {
                    // flowId 为逻辑流 id → 反查 flowKey
                    String flowId = textOrNull(config, "flowId");
                    if (flowId != null) {
                        ref = flowIdToKey(tenantId, flowId);
                        if (ref == null) {
                            external.add("LOGIC_FLOW_ID:" + flowId);
                        }
                    }
                }
                if (ref != null) {
                    deps.add("LOGIC_FLOW:" + ref);
                }
            }
            case DATA_QUERY, DATA_INSERT, DATA_UPDATE, DATA_DELETE, DATA_UPSERT -> {
                String formKey = textOrNull(config, "formKey");
                if (formKey != null) {
                    if (formExists(tenantId, formKey)) {
                        deps.add("FORM:" + formKey);
                    } else {
                        // 物理表名直连（resolveDataTable 的任意数据表路径）
                        external.add("TABLE:" + formKey);
                    }
                }
                // DATA_UPSERT 的多条目形态 {entries:[{formKey,...}]}
                JsonNode entries = config.get("entries");
                if (entries != null && entries.isArray()) {
                    for (JsonNode entry : entries) {
                        String ek = textOrNull(entry, "formKey");
                        if (ek != null) {
                            if (formExists(tenantId, ek)) {
                                deps.add("FORM:" + ek);
                            } else {
                                external.add("TABLE:" + ek);
                            }
                        }
                    }
                }
            }
            case BATCH -> {
                JsonNode body = config.get("body");
                if (body != null && body.isArray()) {
                    for (JsonNode item : body) {
                        String t = textOrNull(item, "type");
                        if (t == null) {
                            continue;
                        }
                        try {
                            scanNode(NodeType.valueOf(t), item.get("config"), tenantId, deps, external);
                        } catch (IllegalArgumentException ignore) {
                            // 循环体节点类型非法由发布校验负责
                        }
                    }
                }
            }
            default -> {
                // START/END/CONDITION/SWITCH/BEAN/SCRIPT/TRANSFORM/..., 无实体引用
            }
        }
    }

    /** BPMN XML：formKey 属性 → 表单引用。 */
    private void collectBpmnRefs(String bpmnXml, Set<String> deps) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            return;
        }
        Matcher m = BPMN_FORM_KEY.matcher(bpmnXml);
        while (m.find()) {
            String key = m.group(1);
            if (key != null && !key.isBlank()) {
                deps.add("FORM:" + key);
            }
        }
    }

    /** 流程节点配置（编辑态）：configJson 中 formKey/flowKey 字段 → 表单/逻辑流引用。 */
    private void collectNodeConfigRefs(String draftId, String tenantId,
                                       Set<String> deps, Set<String> external) {
        for (com.workflow.engine.process.entity.NodeConfig nc
                : nodeConfigRepository.findByProcessDefIdAndProcessDefinitionIdIsNull(draftId)) {
            String json = nc.getConfigJson();
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                collectConfigRefs(root, tenantId, deps, external);
            } catch (Exception e) {
                log.warn("节点配置解析失败（跳过引用扫描）: nodeId={}, err={}", nc.getNodeId(), e.getMessage());
            }
        }
    }

    /** 递归收集节点配置 JSON 中的 formKey / flowKey 字段。 */
    private void collectConfigRefs(JsonNode node, String tenantId, Set<String> deps, Set<String> external) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> en : node.properties()) {
                String field = en.getKey();
                JsonNode v = en.getValue();
                if (v.isTextual() && !v.asText().isBlank()) {
                    if ("formKey".equals(field)) {
                        String k = v.asText();
                        if (formExists(tenantId, k)) {
                            deps.add("FORM:" + k);
                        } else {
                            external.add("TABLE:" + k);
                        }
                    } else if ("flowKey".equals(field)) {
                        deps.add("LOGIC_FLOW:" + v.asText());
                    }
                } else if (v.isObject() || v.isArray()) {
                    collectConfigRefs(v, tenantId, deps, external);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectConfigRefs(child, tenantId, deps, external));
        }
    }

    /** SUBFLOW config.flowId 为逻辑流主键 id → 反查 flowKey（校验租户）。 */
    private String flowIdToKey(String tenantId, String flowId) {
        return logicFlowRepository.findById(flowId)
                .filter(lf -> tenantId.equals(lf.getTenantId()))
                .map(com.workflow.engine.logicflow.entity.LogicFlowDef::getFlowKey)
                .orElse(null);
    }

    private boolean formExists(String tenantId, String formKey) {
        return formDefRepository.existsByTenantIdAndKey(tenantId, formKey);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    // ------------------------------------------------------------------
    // 协作对象（延迟解析，避免构造器参数爆炸）
    // ------------------------------------------------------------------

    private List<com.workflow.engine.logicflow.entity.LogicFlowDef> logicFlows(String tenantId) {
        return logicFlowRepository.search(tenantId, null,
                        org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE))
                .getContent();
    }

    private List<com.workflow.engine.process.entity.ProcessDraft> processDrafts(String tenantId) {
        return processDraftRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId,
                org.springframework.data.domain.Pageable.unpaged()).getContent();
    }
}
