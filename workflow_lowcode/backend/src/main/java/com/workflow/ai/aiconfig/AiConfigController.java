package com.workflow.ai.aiconfig;

import com.workflow.common.domain.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大模型动态配置端点（系统管理 → 模型配置）。
 *
 * <p>GET 读配置（apiKey 脱敏）；PUT 保存（留空密钥=保持不变，"-"=清空），
 * 保存即生效；POST /test 用草稿配置做连通性测试（不落库）。
 */
@RestController
@RequestMapping("/api/ai-config")
public class AiConfigController {

    private final AiConfigService service;

    public AiConfigController(AiConfigService service) {
        this.service = service;
    }

    @GetMapping
    public R<AiConfigVO> get() {
        return R.ok(service.get());
    }

    @PutMapping
    public R<AiConfigVO> save(@Valid @RequestBody AiConfigSaveRequest request) {
        return R.ok(service.save(request));
    }

    @PostMapping("/test")
    public R<AiConfigTestResult> test(@Valid @RequestBody AiConfigTestRequest request) {
        return R.ok(service.test(request));
    }
}
