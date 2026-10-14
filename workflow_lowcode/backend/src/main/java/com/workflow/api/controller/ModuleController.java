package com.workflow.api.controller;

import com.workflow.common.domain.R;
import com.workflow.engine.module.ModuleDependencyAnalyzer;
import com.workflow.engine.module.ModuleExportService;
import com.workflow.engine.module.ModuleImportService;
import com.workflow.engine.module.ModuleInspectService;
import com.workflow.engine.module.ModulePackageCodec;
import com.workflow.engine.module.ModuleTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块管理 API（导出 / 导入预检 / 导入执行 / 依赖分析）。
 *
 * <p>租户隔离：统一取 {@code X-Tenant-Id}（缺省 default）；返回统一 {@link R} 包装
 * （导出为二进制流除外）。
 */
@RestController
@RequestMapping("/api/v1/modules")
public class ModuleController {

    private final ModuleDependencyAnalyzer analyzer;
    private final ModuleExportService exportService;
    private final ModuleInspectService inspectService;
    private final ModuleImportService importService;

    public ModuleController(ModuleDependencyAnalyzer analyzer,
                            ModuleExportService exportService,
                            ModuleInspectService inspectService,
                            ModuleImportService importService) {
        this.analyzer = analyzer;
        this.exportService = exportService;
        this.inspectService = inspectService;
        this.importService = importService;
    }

    // ------------------------------------------------------------------
    // DTO
    // ------------------------------------------------------------------

    public record AnalyzeReq(List<ElementReq> elements) {
    }

    public record ElementReq(String type, String key) {
    }

    /** 闭包预览节点。 */
    public record AnalyzeNodeVO(String refKey, String name, List<String> dependsOn,
                                List<String> externalDeps, boolean selected, boolean autoAdded) {
    }

    public record AnalyzeVO(List<AnalyzeNodeVO> nodes, List<String> missing) {
    }

    public record ExportReq(String name, String description, List<ElementReq> elements,
                            Boolean includeClosure) {
    }

    public record ImportActionReq(String type, String key, String strategy) {
    }

    // ------------------------------------------------------------------
    // 端点
    // ------------------------------------------------------------------

    /** 依赖闭包分析：选择集 → 自动补全依赖后的完整节点图（导出预览用）。 */
    @PostMapping("/analyze")
    public R<AnalyzeVO> analyze(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                @RequestBody AnalyzeReq req) {
        Set<String> selected = new LinkedHashSet<>();
        if (req.elements() != null) {
            for (ElementReq e : req.elements()) {
                selected.add(ModuleTypes.ElementType.parse(e.type()).name() + ":" + e.key());
            }
        }
        ModuleDependencyAnalyzer.Graph graph = analyzer.scan(tenantId);
        ModuleDependencyAnalyzer.Closure closure = analyzer.closure(graph, selected);

        List<AnalyzeNodeVO> nodes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String ref : closure.refs()) {
            ModuleDependencyAnalyzer.Node node = graph.nodes().get(ref);
            if (node == null) {
                missing.add(ref);
                continue;
            }
            nodes.add(new AnalyzeNodeVO(node.refKey(), node.name(),
                    new ArrayList<>(node.dependsOn()), new ArrayList<>(node.externalDeps()),
                    selected.contains(ref), !selected.contains(ref)));
        }
        return R.ok(new AnalyzeVO(nodes, missing));
    }

    /** 导出模块包（zip 流下载）。 */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                         @RequestHeader(value = "X-User-Id", required = false) String operator,
                                         @RequestBody ExportReq req) throws IOException {
        List<ModuleTypes.ElementRef> refs = new ArrayList<>();
        for (ElementReq e : req.elements()) {
            refs.add(new ModuleTypes.ElementRef(ModuleTypes.ElementType.parse(e.type()).name(), e.key()));
        }
        ModuleExportService.ExportResult result = exportService.export(tenantId, operator,
                new ModuleExportService.ExportRequest(req.name(), req.description(), refs, req.includeClosure()));
        String fileName = new String(result.fileName().getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(result.zipBytes());
    }

    /** 导入预检：解析包、冲突判定、依赖缺口。不落库。 */
    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ModuleInspectService.InspectResult> inspect(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("file") MultipartFile file) throws IOException {
        return R.ok(inspectService.inspect(tenantId, file.getBytes()));
    }

    /** 导入执行：按逐元素策略（OVERWRITE/SKIP）写入目标租户。 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ModuleImportService.ImportResult> imports(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", required = false) String operator,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "actions", required = false) String actionsJson) throws IOException {
        List<ModuleImportService.ImportAction> actions = new ArrayList<>();
        if (actionsJson != null && !actionsJson.isBlank()) {
            for (ImportActionReq a : parseActions(actionsJson)) {
                actions.add(new ModuleImportService.ImportAction(a.type(), a.key(), a.strategy()));
            }
        }
        return R.ok(importService.importModule(tenantId, operator, file.getBytes(), actions));
    }

    private List<ImportActionReq> parseActions(String actionsJson) throws IOException {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(actionsJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, ImportActionReq.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("actions 参数解析失败: " + e.getMessage(), e);
        }
    }
}
