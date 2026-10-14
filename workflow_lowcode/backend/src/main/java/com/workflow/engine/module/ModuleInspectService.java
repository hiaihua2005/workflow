package com.workflow.engine.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.engine.datasource.repository.DataSourceDefinitionRepository;
import com.workflow.engine.form.repository.FormDefinitionRepository;
import com.workflow.engine.logicflow.repository.LogicFlowDefRepository;
import com.workflow.engine.page.repository.PageDefinitionRepository;
import com.workflow.engine.process.repository.ProcessDraftRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块导入预检服务：解析包 + 冲突判定 + 依赖缺口 + 警告。不写任何数据。
 *
 * <p>冲突判定按各实体真实唯一键：FORM/PAGE/WORKFLOW 按 (tenant, key)，
 * LOGIC_FLOW 按 flow_key，DATA_SOURCE 按 source_key 或 name（FORM/WORKFLOW 类型）任一命中。
 */
@Service
public class ModuleInspectService {

    private final FormDefinitionRepository formDefRepository;
    private final PageDefinitionRepository pageDefRepository;
    private final DataSourceDefinitionRepository dataSourceRepository;
    private final LogicFlowDefRepository logicFlowRepository;
    private final ProcessDraftRepository processDraftRepository;
    private final ObjectMapper objectMapper;

    public ModuleInspectService(FormDefinitionRepository formDefRepository,
                                PageDefinitionRepository pageDefRepository,
                                DataSourceDefinitionRepository dataSourceRepository,
                                LogicFlowDefRepository logicFlowRepository,
                                ProcessDraftRepository processDraftRepository,
                                ObjectMapper objectMapper) {
        this.formDefRepository = formDefRepository;
        this.pageDefRepository = pageDefRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.logicFlowRepository = logicFlowRepository;
        this.processDraftRepository = processDraftRepository;
        this.objectMapper = objectMapper;
    }

    /** 单元素预检结果。 */
    public record ElementInspect(String type, String key, String name,
                                 boolean exists, String targetVersion,
                                 List<String> missingDeps, List<String> warnings) {
    }

    /** 预检结果。 */
    public record InspectResult(String moduleName, String moduleDescription, String sourceTenant,
                                String exportedAt, List<ElementInspect> elements,
                                List<String> globalWarnings) {
    }

    public InspectResult inspect(String tenantId, byte[] zipBytes) throws IOException {
        ModulePackageCodec.Package pkg = ModulePackageCodec.read(zipBytes, objectMapper);
        ModuleTypes.ModuleManifest manifest = pkg.manifest();

        List<ElementInspect> elements = new ArrayList<>();
        List<String> globalWarnings = new ArrayList<>();
        globalWarnings.add("菜单与权限不随模块导入，导入完成后请手工配置菜单入口。");
        globalWarnings.add("工作流以草稿态导入，需在流程设计器中检查并部署。");
        if (!tenantId.equals(manifest.module().sourceTenant())) {
            globalWarnings.add("模块来源租户为 " + manifest.module().sourceTenant()
                    + "，导入目标租户为 " + tenantId + "。");
        }

        Set<String> packageRefs = new LinkedHashSet<>();
        for (ModuleTypes.ManifestElement el : manifest.elements()) {
            packageRefs.add(el.type() + ":" + el.key());
        }

        for (ModuleTypes.ManifestElement el : manifest.elements()) {
            List<String> missing = new ArrayList<>();
            for (String dep : orEmpty(el.dependsOn())) {
                if (!packageRefs.contains(dep) && !targetHasElement(tenantId, dep)) {
                    missing.add(dep);
                }
            }
            boolean exists = targetExists(tenantId, el.type(), el.key());
            elements.add(new ElementInspect(el.type(), el.key(), el.name(), exists,
                    exists ? targetVersionOf(tenantId, el.type(), el.key()) : null,
                    missing, orEmpty(el.externalDeps())));
        }
        return new InspectResult(manifest.module().name(), manifest.module().description(),
                manifest.module().sourceTenant(), manifest.module().exportedAt(), elements, globalWarnings);
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    /** 目标租户是否已有该元素（按真实唯一键）。 */
    private boolean targetExists(String tenantId, String type, String key) {
        return switch (type) {
            case "FORM" -> formDefRepository.existsByTenantIdAndKey(tenantId, key);
            case "PAGE" -> pageDefRepository.existsByTenantIdAndKey(tenantId, key);
            case "DATA_SOURCE" -> dataSourceRepository.findByTenantIdAndSourceKey(tenantId, key).isPresent()
                    || dataSourceRepository.findByTenantIdAndTypeAndName(tenantId, "FORM", key).isPresent()
                    || dataSourceRepository.findByTenantIdAndTypeAndName(tenantId, "WORKFLOW", key).isPresent();
            case "LOGIC_FLOW" -> logicFlowRepository.existsByTenantIdAndFlowKey(tenantId, key);
            case "WORKFLOW" -> processDraftRepository.findByTenantIdAndKey(tenantId, key).isPresent();
            default -> false;
        };
    }

    /** 目标已有元素的当前版本/状态描述（预检展示用）。 */
    private String targetVersionOf(String tenantId, String type, String key) {
        return switch (type) {
            case "FORM" -> formDefRepository
                    .findFirstByTenantIdAndKeyAndStatusOrderByVersionDesc(tenantId, key, "PUBLISHED")
                    .map(f -> "v" + f.getVersion()).orElse(null);
            case "PAGE" -> pageDefRepository
                    .findFirstByTenantIdAndKeyAndStatusOrderByVersionDesc(tenantId, key, "PUBLISHED")
                    .map(p -> "v" + p.getVersion()).orElse(null);
            case "DATA_SOURCE" -> dataSourceRepository.findByTenantIdAndSourceKey(tenantId, key)
                    .map(d -> d.getStatus()).orElse(null);
            case "LOGIC_FLOW" -> logicFlowRepository.findByTenantIdAndFlowKey(tenantId, key)
                    .map(l -> "v" + l.getVersion()).orElse(null);
            case "WORKFLOW" -> processDraftRepository.findByTenantIdAndKey(tenantId, key)
                    .map(d -> d.getStatus()).orElse(null);
            default -> null;
        };
    }

    /** 依赖缺口判定：dep（TYPE:key）不在包内时，目标租户是否已有。 */
    private boolean targetHasElement(String tenantId, String dep) {
        ModuleTypes.ElementRef ref = ModuleTypes.ElementRef.parse(dep);
        return targetExists(tenantId, ref.type(), ref.key());
    }
}
