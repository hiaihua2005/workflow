package com.workflow.api.dto;

/**
 * 待办任务查询过滤参数。
 */
public record TaskTodoFilter(
        String processName,
        String initiator,
        String createTimeStart,
        String createTimeEnd,
        /** 排序：createTime（默认）| priority（优先级降序 + 创建时间降序）。 */
        String orderBy) {

    public static TaskTodoFilter empty() {
        return new TaskTodoFilter(null, null, null, null, null);
    }
}
