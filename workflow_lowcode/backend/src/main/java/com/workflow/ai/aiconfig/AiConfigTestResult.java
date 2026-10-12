package com.workflow.ai.aiconfig;

/**
 * 连通性测试结果。
 */
public record AiConfigTestResult(boolean ok, long latencyMs, String message, String model) {

    public static AiConfigTestResult success(long latencyMs, String model) {
        return new AiConfigTestResult(true, latencyMs, "连接成功", model);
    }

    public static AiConfigTestResult failure(String message, String model) {
        return new AiConfigTestResult(false, -1, message, model);
    }
}
