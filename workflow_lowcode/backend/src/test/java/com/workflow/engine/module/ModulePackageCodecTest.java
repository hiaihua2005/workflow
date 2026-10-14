package com.workflow.engine.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块包编解码单元测试：zip 往返一致性、manifest 校验、非法包拒绝。
 */
class ModulePackageCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ModuleTypes.ModuleManifest sampleManifest() {
        return new ModuleTypes.ModuleManifest(
                ModuleTypes.MANIFEST_VERSION,
                new ModuleTypes.ModuleInfo("请假管理", "示例模块",
                        "2026-10-14T10:00:00", "admin", "default"),
                List.of(
                        new ModuleTypes.ManifestElement("FORM", "leave-apply", "请假申请",
                                ModulePackageCodec.entryOf("FORM", "leave-apply"),
                                List.of("DATA_SOURCE:ds-leave"), List.of()),
                        new ModuleTypes.ManifestElement("DATA_SOURCE", "ds-leave", "请假数据源",
                                ModulePackageCodec.entryOf("DATA_SOURCE", "ds-leave"),
                                List.of("FORM:leave-apply"), List.of())));
    }

    private Map<String, ModuleTypes.ElementPayload> samplePayloads() {
        Map<String, ModuleTypes.ElementPayload> payloads = new LinkedHashMap<>();
        payloads.put("FORM:leave-apply", ModuleTypes.ElementPayload.form("f-id-1", "leave-apply",
                "请假申请", "BUSINESS", "{\"rule\":[]}", "[]", null, List.of()));
        payloads.put("DATA_SOURCE:ds-leave", ModuleTypes.ElementPayload.dataSource("ds-id-1",
                "ds-leave", "请假数据源", "FORM", "ds-leave", null, "f-id-1", "ENABLED"));
        return payloads;
    }

    @Test
    void writeThenReadRoundTrip() throws IOException {
        byte[] zip = ModulePackageCodec.write(sampleManifest(), samplePayloads(), objectMapper);
        ModulePackageCodec.Package pkg = ModulePackageCodec.read(zip, objectMapper);

        assertEquals("请假管理", pkg.manifest().module().name());
        assertEquals(2, pkg.manifest().elements().size());
        assertEquals("FORM:leave-apply", pkg.manifest().elements().get(0).type() + ":"
                + pkg.manifest().elements().get(0).key());
        assertEquals("BUSINESS", pkg.payloads().get("FORM:leave-apply").formType());
        assertEquals("f-id-1", pkg.payloads().get("FORM:leave-apply").sourceId());
        assertEquals("f-id-1", pkg.payloads().get("DATA_SOURCE:ds-leave").formId());
    }

    @Test
    void readRejectsMissingManifest() throws IOException {
        // 只有载荷没有 manifest 的包
        byte[] zip = ModulePackageCodec.write(sampleManifest(), samplePayloads(), objectMapper);
        // 构造一个空 zip（无条目）
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("entities/form/x.json"));
            zos.write("{\"type\":\"FORM\",\"key\":\"x\"}".getBytes());
            zos.closeEntry();
        }
        IOException e = assertThrows(IOException.class, () -> ModulePackageCodec.read(bos.toByteArray(), objectMapper));
        assertTrue(e.getMessage().contains("manifest.json"));
    }

    @Test
    void readRejectsUnsupportedManifestVersion() throws IOException {
        ModuleTypes.ModuleManifest bad = new ModuleTypes.ModuleManifest(
                999, new ModuleTypes.ModuleInfo("x", null, null, null, null), List.of());
        byte[] zip = ModulePackageCodec.write(bad, new LinkedHashMap<>(), objectMapper);
        IOException e = assertThrows(IOException.class, () -> ModulePackageCodec.read(zip, objectMapper));
        assertTrue(e.getMessage().contains("manifest 版本"));
    }

    @Test
    void writeRejectsElementWithoutPayload() {
        // manifest 声明 2 个元素但只提供 1 个载荷 → 打包期即失败（保证 read 侧一致性校验的输入健全）
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ModulePackageCodec.write(sampleManifest(),
                        Map.of("FORM:leave-apply", samplePayloads().get("FORM:leave-apply")), objectMapper));
        assertTrue(e.getMessage().contains("缺少载荷"));
    }

    @Test
    void readRejectsElementWithoutPayloadInRawZip() throws IOException {
        // 绕过 write 直接构造残包：manifest 声明 2 个元素，zip 内只有 1 个载荷
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(ModulePackageCodec.MANIFEST_ENTRY));
            zos.write(objectMapper.writeValueAsBytes(sampleManifest()));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry(ModulePackageCodec.entryOf("FORM", "leave-apply")));
            zos.write(objectMapper.writeValueAsBytes(samplePayloads().get("FORM:leave-apply")));
            zos.closeEntry();
        }
        IOException e = assertThrows(IOException.class, () -> ModulePackageCodec.read(bos.toByteArray(), objectMapper));
        assertTrue(e.getMessage().contains("缺少载荷"));
    }

    @Test
    void readRejectsIllegalEntryPath() throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("entities/../evil.json"));
            zos.write("{}".getBytes());
            zos.closeEntry();
        }
        IOException e = assertThrows(IOException.class, () -> ModulePackageCodec.read(bos.toByteArray(), objectMapper));
        assertTrue(e.getMessage().contains("非法条目"));
    }
}
