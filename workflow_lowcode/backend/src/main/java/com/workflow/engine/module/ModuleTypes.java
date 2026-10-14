package com.workflow.engine.module;

import java.util.List;

/**
 * 模块包格式模型（manifestVersion = 1）。
 *
 * <p>模块包为 zip：{@code manifest.json} + {@code entities/<type>/<key>.json}。
 * manifest 元素清单携带依赖边（{@code TYPE:key} 形式）与无法打包的外部依赖说明，
 * 导入端据此做缺口校验，不重复静态分析。
 */
public final class ModuleTypes {

    /** manifest 版本（防旧包导入新平台时静默错配）。 */
    public static final int MANIFEST_VERSION = 1;

    private ModuleTypes() {
    }

    /** 可打包元素类型。 */
    public enum ElementType {
        FORM, PAGE, DATA_SOURCE, LOGIC_FLOW, WORKFLOW;

        /** 解析类型字符串（大小写不敏感）；非法值抛 IllegalArgumentException。 */
        public static ElementType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("元素类型不能为空");
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知元素类型: " + raw);
            }
        }
    }

    /** manifest.module 信息块。 */
    public record ModuleInfo(String name, String description, String exportedAt,
                             String exportedBy, String sourceTenant) {
    }

    /** manifest.elements 条目。 */
    public record ManifestElement(String type, String key, String name, String file,
                                  List<String> dependsOn, List<String> externalDeps) {
    }

    /** manifest 根对象。 */
    public record ModuleManifest(int manifestVersion, ModuleInfo module, List<ManifestElement> elements) {
    }

    /**
     * 元素载荷（统一 record，五类元素按 type 取用各自字段；null 字段不写入 JSON）。
     * {@code sourceId} 为导出侧原行 id（导入时建立 oldId→newId 映射，供 id 引用重写）。
     */
    public record ElementPayload(
            String type, String key, String name, String sourceId,
            // FORM：formType=BUSINESS|WORKFLOW，schema/columnConfig 原文，processKey 随导
            String formType, String schema, String columnConfig, String processKey,
            List<BindingPayload> bindings,
            // PAGE
            String pageType, String formKey, String dataSourceId,
            // DATA_SOURCE
            String dsType, String sourceKey, String params, String formId, String dsStatus,
            // LOGIC_FLOW
            String description, String dsl,
            // WORKFLOW
            String bpmnXml, List<NodeConfigPayload> nodeConfigs) {

        /** 表单×逻辑流触发绑定。 */
        public record BindingPayload(String formType, String formKey, String triggerType,
                                     String flowKey, String executionMode, Boolean enabled,
                                     String description) {
        }

        /** 流程节点配置（编辑态行）。 */
        public record NodeConfigPayload(String nodeId, String nodeType, String configJson) {
        }

        // ---------------- 静态工厂（按类型装配，防位置参数错位） ----------------

        public static ElementPayload form(String sourceId, String key, String name, String formType,
                                          String schema, String columnConfig, String processKey,
                                          List<BindingPayload> bindings) {
            return new ElementPayload("FORM", key, name, sourceId, formType, schema, columnConfig,
                    processKey, bindings, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }

        public static ElementPayload page(String sourceId, String key, String name, String pageType,
                                          String formKey, String dataSourceId, String schema) {
            return new ElementPayload("PAGE", key, name, sourceId, null, schema, null, null, null,
                    pageType, formKey, dataSourceId, null, null, null, null, null,
                    null, null, null, null);
        }

        public static ElementPayload dataSource(String sourceId, String key, String name, String dsType,
                                                String sourceKey, String params, String formId,
                                                String dsStatus) {
            return new ElementPayload("DATA_SOURCE", key, name, sourceId, null, null, null, null,
                    null, null, null, null, dsType, sourceKey, params, formId, dsStatus,
                    null, null, null, null);
        }

        public static ElementPayload logicFlow(String sourceId, String key, String name,
                                               String description, String dsl) {
            return new ElementPayload("LOGIC_FLOW", key, name, sourceId, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    description, dsl, null, null);
        }

        public static ElementPayload workflow(String sourceId, String key, String name,
                                              String description, String bpmnXml,
                                              List<NodeConfigPayload> nodeConfigs) {
            return new ElementPayload("WORKFLOW", key, name, sourceId, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    description, null, bpmnXml, nodeConfigs);
        }
    }

    /** 导出请求中的元素引用（TYPE:key）。 */
    public record ElementRef(String type, String key) {
        public static ElementRef parse(String ref) {
            int i = ref.indexOf(':');
            if (i <= 0 || i == ref.length() - 1) {
                throw new IllegalArgumentException("元素引用格式非法（应为 TYPE:key）: " + ref);
            }
            return new ElementRef(ModuleTypes.ElementType.parse(ref.substring(0, i)).name(),
                    ref.substring(i + 1));
        }

        public String refKey() {
            return type + ":" + key;
        }
    }
}
