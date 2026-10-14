package com.workflow.engine.module;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 模块包 zip 编解码：manifest.json + entities/&lt;type&gt;/&lt;key&gt;.json。
 *
 * <p>JSON 文本一律 UTF-8；zip 条目名统一 '/' 分隔。载荷中可能出现非 UTF-8 安全的
 * 任意内容均已是文本（schema/bpmn/dsl/configJson），无需二进制通道。
 */
public final class ModulePackageCodec {

    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String ENTITIES_PREFIX = "entities/";

    /** 上限保护：单包 64MB、单文件 16MB、条目 2000。 */
    private static final long MAX_PACKAGE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 2000;

    private ModulePackageCodec() {
    }

    /** 写包结果。 */
    public record Package(ModuleTypes.ModuleManifest manifest,
                          Map<String, ModuleTypes.ElementPayload> payloads) {
    }

    /** 打包为 zip 字节流。 */
    public static byte[] write(ModuleTypes.ModuleManifest manifest,
                               Map<String, ModuleTypes.ElementPayload> payloads,
                               ObjectMapper objectMapper) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(manifest));
            zip.closeEntry();

            for (ModuleTypes.ManifestElement el : manifest.elements()) {
                ModuleTypes.ElementPayload payload = payloads.get(el.type() + ":" + el.key());
                if (payload == null) {
                    throw new IllegalStateException("manifest 元素缺少载荷: " + el.type() + ":" + el.key());
                }
                String entry = el.file() != null ? el.file() : ENTITIES_PREFIX + el.type().toLowerCase()
                        + "/" + el.key() + ".json";
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
                zip.closeEntry();
            }
        }
        byte[] bytes = bos.toByteArray();
        if (bytes.length > MAX_PACKAGE_BYTES) {
            throw new IOException("模块包超出大小上限（64MB）");
        }
        return bytes;
    }

    /** 读取并校验 zip：返回 manifest + 载荷表（key = TYPE:key）。 */
    public static Package read(byte[] zipBytes, ObjectMapper objectMapper) throws IOException {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IOException("模块包为空");
        }
        if (zipBytes.length > MAX_PACKAGE_BYTES) {
            throw new IOException("模块包超出大小上限（64MB）");
        }
        String manifestJson = null;
        Map<String, ModuleTypes.ElementPayload> payloads = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("模块包条目数超出上限");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IOException("模块包含非法条目路径: " + name);
                }
                byte[] content = zip.readAllBytes();
                if (content.length > MAX_FILE_BYTES) {
                    throw new IOException("模块包单文件超出大小上限（16MB）: " + name);
                }
                if (MANIFEST_ENTRY.equals(name)) {
                    manifestJson = new String(content, StandardCharsets.UTF_8);
                } else if (name.startsWith(ENTITIES_PREFIX) && name.endsWith(".json")) {
                    ModuleTypes.ElementPayload payload = objectMapper.readValue(content,
                            ModuleTypes.ElementPayload.class);
                    if (payload.type() == null || payload.key() == null) {
                        throw new IOException("载荷缺少 type/key: " + name);
                    }
                    payloads.put(payload.type() + ":" + payload.key(), payload);
                }
                zip.closeEntry();
            }
        }
        if (manifestJson == null) {
            throw new IOException("模块包缺少 " + MANIFEST_ENTRY);
        }
        ModuleTypes.ModuleManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestJson, ModuleTypes.ModuleManifest.class);
        } catch (IOException e) {
            throw new IOException("manifest.json 解析失败: " + e.getMessage(), e);
        }
        if (manifest.manifestVersion() != ModuleTypes.MANIFEST_VERSION) {
            throw new IOException("不支持的 manifest 版本: " + manifest.manifestVersion()
                    + "（当前支持 v" + ModuleTypes.MANIFEST_VERSION + "）");
        }
        // manifest 与载荷一致性：manifest 声明的元素必须都有载荷
        for (ModuleTypes.ManifestElement el : manifest.elements()) {
            if (!payloads.containsKey(el.type() + ":" + el.key())) {
                throw new IOException("manifest 声明的元素缺少载荷文件: " + el.type() + ":" + el.key());
            }
        }
        return new Package(manifest, payloads);
    }

    /** 生成元素在包内的条目路径。 */
    public static String entryOf(String type, String key) {
        return ENTITIES_PREFIX + type.toLowerCase() + "/" + key + ".json";
    }

    /** 列出 zip 内条目名（导入前的轻量探测，不解析内容）。 */
    public static List<String> listEntries(byte[] zipBytes) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                zip.closeEntry();
            }
        }
        return names;
    }

    /** 供测试/调试：manifest 的 base64 指纹（防手改校验的简易实现）。 */
    public static String fingerprint(byte[] zipBytes) {
        return Base64.getEncoder().encodeToString(zipBytes).substring(0, 16);
    }
}
