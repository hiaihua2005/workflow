package com.workflow.engine.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.engine.datasource.entity.DataSourceDefinition;
import com.workflow.engine.datasource.repository.DataSourceDefinitionRepository;
import com.workflow.engine.form.entity.FormDefinition;
import com.workflow.engine.form.repository.FormDefinitionRepository;
import com.workflow.engine.logicflow.entity.FormLogicBinding;
import com.workflow.engine.logicflow.entity.LogicFlowDef;
import com.workflow.engine.logicflow.repository.FormLogicBindingRepository;
import com.workflow.engine.logicflow.repository.LogicFlowDefRepository;
import com.workflow.engine.page.entity.PageDefinition;
import com.workflow.engine.page.repository.PageDefinitionRepository;
import com.workflow.engine.process.entity.NodeConfig;
import com.workflow.engine.process.entity.ProcessDraft;
import com.workflow.engine.process.repository.NodeConfigRepository;
import com.workflow.engine.process.repository.ProcessDraftRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块导出服务：选择集（已含闭包）→ 只导已发布快照 → zip 模块包。
 *
 * <p>导出内容：
 * <ul>
 *   <li>FORM：PUBLISHED 行（name/key/type/schema/columnConfig/processKey）+ 启用触发绑定；</li>
 *   <li>PAGE：PUBLISHED 行（type/formKey/dataSourceId/schema）；</li>
 *   <li>DATA_SOURCE：FORM/WORKFLOW 类型行（SYSTEM/API 为平台/外部资源不导出）；</li>
 *   <li>LOGIC_FLOW：PUBLISHED 行的 dsl；</li>
 *   <li>WORKFLOW：草稿 bpmnXml + 编辑态节点配置。</li>
 * </ul>
 */
@Service
public class ModuleExportService {

    private final ModuleDependencyAnalyzer analyzer;
    private final FormDefinitionRepository formDefRepository;
    private final PageDefinitionRepository pageDefRepository;
    private final DataSourceDefinitionRepository dataSourceRepository;
    private final LogicFlowDefRepository logicFlowRepository;
    private final FormLogicBindingRepository bindingRepository;
    private final ProcessDraftRepository processDraftRepository;
    private final NodeConfigRepository nodeConfigRepository;
    private final ObjectMapper objectMapper;

    public ModuleExportService(ModuleDependencyAnalyzer analyzer,
                               FormDefinitionRepository formDefRepository,
                               PageDefinitionRepository pageDefRepository,
                               DataSourceDefinitionRepository dataSourceRepository,
                               LogicFlowDefRepository logicFlowRepository,
                               FormLogicBindingRepository bindingRepository,
                               ProcessDraftRepository processDraftRepository,
                               NodeConfigRepository nodeConfigRepository,
                               ObjectMapper objectMapper) {
        this.analyzer = analyzer;
        this.formDefRepository = formDefRepository;
        this.pageDefRepository = pageDefRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.logicFlowRepository = logicFlowRepository;
        this.bindingRepository = bindingRepository;
        this.processDraftRepository = processDraftRepository;
        this.nodeConfigRepository = nodeConfigRepository;
        this.objectMapper = objectMapper;
    }

    /** 导出请求（includeClosure=false 时尊重前端已调整的选择集，不自动补依赖）。 */
    public record ExportRequest(String name, String description, List<ModuleTypes.ElementRef> elements,
                                Boolean includeClosure) {
    }

    /** 导出结果：zip 字节 + 元素数（供日志/响应头）。 */
    public record ExportResult(byte[] zipBytes, int elementCount, String fileName) {
    }

    /**
     * 导出模块包。includeClosure=true（缺省）时选择集先经闭包分析自动补全依赖；
     * false 时尊重调用方传入的最终选择集（前端已在预览中完成闭包勾选并允许用户裁剪）。
     *
     * @throws IllegalArgumentException 元素不存在或状态不允许导出
     */
    public ExportResult export(String tenantId, String operator, ExportRequest request) throws IOException {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("模块名称不能为空");
        }
        if (request.elements() == null || request.elements().isEmpty()) {
            throw new IllegalArgumentException("导出元素不能为空");
        }

        ModuleDependencyAnalyzer.Graph graph = analyzer.scan(tenantId);

        List<String> selectedRefs = request.elements().stream().map(ModuleTypes.ElementRef::refKey).toList();
        // 闭包兜底补全（BFS，保持选择序）；前端显式裁剪过选择集时尊重用户意图
        boolean includeClosure = request.includeClosure() == null || request.includeClosure();
        Set<String> finalRefs = includeClosure
                ? analyzer.closure(graph, selectedRefs).refs()
                : new LinkedHashSet<>(selectedRefs);

        Map<String, ModuleTypes.ElementPayload> payloads = new LinkedHashMap<>();
        List<ModuleTypes.ManifestElement> manifestElements = new ArrayList<>();
        Set<String> externalDeps = new LinkedHashSet<>();

        for (String ref : finalRefs) {
            ModuleTypes.ElementRef parsed = ModuleTypes.ElementRef.parse(ref);
            ModuleTypes.ElementPayload payload = loadPayload(tenantId, parsed, graph);
            if (payload == null) {
                continue; // 图中占位但实际不可导出（已由 loadPayload 校验过；防御）
            }
            String type = parsed.type();
            String key = parsed.key();
            payloads.put(ref, payload);

            ModuleDependencyAnalyzer.Node node = graph.nodes().get(ref);
            List<String> dependsOn = node == null ? List.of() : new ArrayList<>(node.dependsOn());
            List<String> externals = node == null ? List.of() : new ArrayList<>(node.externalDeps());
            externalDeps.addAll(externals);
            manifestElements.add(new ModuleTypes.ManifestElement(type, key, payload.name(),
                    ModulePackageCodec.entryOf(type, key), dependsOn, externals));
        }

        if (manifestElements.isEmpty()) {
            throw new IllegalArgumentException("选择集中没有可导出的已发布元素");
        }

        ModuleTypes.ModuleManifest manifest = new ModuleTypes.ModuleManifest(
                ModuleTypes.MANIFEST_VERSION,
                new ModuleTypes.ModuleInfo(request.name(), request.description(),
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        operator, tenantId),
                manifestElements);

        byte[] zip = ModulePackageCodec.write(manifest, payloads, objectMapper);
        String fileName = "module-" + sanitize(request.name()) + ".zip";
        return new ExportResult(zip, manifestElements.size(), fileName);
    }

    // ------------------------------------------------------------------
    // 载荷装配（只导已发布快照）
    // ------------------------------------------------------------------

    private ModuleTypes.ElementPayload loadPayload(String tenantId, ModuleTypes.ElementRef ref,
                                                   ModuleDependencyAnalyzer.Graph graph) {
        return switch (ref.type()) {
            case "FORM" -> loadForm(tenantId, ref.key());
            case "PAGE" -> loadPage(tenantId, ref.key());
            case "DATA_SOURCE" -> loadDataSource(tenantId, ref.key());
            case "LOGIC_FLOW" -> loadLogicFlow(tenantId, ref.key());
            case "WORKFLOW" -> loadWorkflow(tenantId, ref.key());
            default -> throw new IllegalArgumentException("未知元素类型: " + ref.type());
        };
    }

    private ModuleTypes.ElementPayload loadForm(String tenantId, String key) {
        FormDefinition f = formDefRepository
                .findFirstByTenantIdAndKeyAndStatusOrderByVersionDesc(tenantId, key, "PUBLISHED")
                .orElseThrow(() -> new IllegalArgumentException("表单不存在或未发布: " + key));

        List<ModuleTypes.ElementPayload.BindingPayload> bindings = new ArrayList<>();
        if ("BUSINESS".equals(f.getType())) {
            for (FormLogicBinding b : bindingRepository
                    .findByTenantIdAndFormTypeAndFormKeyOrderByCreatedAtAsc(tenantId, "BUSINESS", key)) {
                if (Boolean.TRUE.equals(b.getEnabled())) {
                    bindings.add(new ModuleTypes.ElementPayload.BindingPayload(b.getFormType(), b.getFormKey(),
                            b.getTriggerType(), b.getFlowKey(), b.getExecutionMode(), b.getEnabled(),
                            b.getDescription()));
                }
            }
        }
        return ModuleTypes.ElementPayload.form(f.getId(), f.getKey(), f.getName(), f.getType(),
                f.getSchema(), f.getColumnConfig(), f.getProcessKey(), bindings);
    }

    private ModuleTypes.ElementPayload loadPage(String tenantId, String key) {
        PageDefinition p = pageDefRepository
                .findFirstByTenantIdAndKeyAndStatusOrderByVersionDesc(tenantId, key, "PUBLISHED")
                .orElseThrow(() -> new IllegalArgumentException("页面不存在或未发布: " + key));
        return ModuleTypes.ElementPayload.page(p.getId(), p.getKey(), p.getName(), p.getType(),
                p.getFormKey(), p.getDataSourceId(), p.getSchema());
    }

    private ModuleTypes.ElementPayload loadDataSource(String tenantId, String key) {
        // FORM/WORKFLOW 类型按 formKey（=source_key）定位；其他类型不可导出
        DataSourceDefinition ds = dataSourceRepository.findByTenantIdAndFormKey(tenantId, key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "数据源不存在或为系统/外部类型（不可导出）: " + key));
        return ModuleTypes.ElementPayload.dataSource(ds.getId(), ds.getFormKey(), ds.getName(),
                ds.getType(), ds.getSourceKey(), ds.getParams(), ds.getFormId(), ds.getStatus());
    }

    private ModuleTypes.ElementPayload loadLogicFlow(String tenantId, String flowKey) {
        LogicFlowDef lf = logicFlowRepository.findByTenantIdAndFlowKey(tenantId, flowKey)
                .orElseThrow(() -> new IllegalArgumentException("逻辑流不存在: " + flowKey));
        if (!"PUBLISHED".equals(lf.getStatus())) {
            throw new IllegalArgumentException("逻辑流未发布，不可导出: " + flowKey);
        }
        return ModuleTypes.ElementPayload.logicFlow(lf.getId(), lf.getFlowKey(), lf.getName(),
                lf.getDescription(), lf.getDslJson());
    }

    private ModuleTypes.ElementPayload loadWorkflow(String tenantId, String key) {
        ProcessDraft pd = processDraftRepository.findByTenantIdAndKey(tenantId, key)
                .orElseThrow(() -> new IllegalArgumentException("流程不存在: " + key));
        List<ModuleTypes.ElementPayload.NodeConfigPayload> nodeConfigs = new ArrayList<>();
        for (NodeConfig nc : nodeConfigRepository
                .findByProcessDefIdAndProcessDefinitionIdIsNull(pd.getId())) {
            nodeConfigs.add(new ModuleTypes.ElementPayload.NodeConfigPayload(
                    nc.getNodeId(), nc.getNodeType(), nc.getConfigJson()));
        }
        return ModuleTypes.ElementPayload.workflow(pd.getId(), pd.getKey(), pd.getName(),
                pd.getDescription(), pd.getBpmnXml(), nodeConfigs);
    }

    private String sanitize(String name) {
        String s = name.replaceAll("[^\\w\\u4e00-\\u9fa5-]+", "-");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
