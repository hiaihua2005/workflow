package com.workflow.api.dto;

public class RejectRequest {
    private String userId;
    private String reason;
    /** 退回目标节点（chooseStartNode 退回模式）；空 = 退回发起人节点（默认）。 */
    private String targetNodeId;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }
}
