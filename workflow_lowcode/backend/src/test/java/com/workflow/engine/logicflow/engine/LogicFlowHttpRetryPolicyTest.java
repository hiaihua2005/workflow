package com.workflow.engine.logicflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.engine.logic.BackendBeanRegistry;
import com.workflow.engine.logic.executor.GroovyScriptLogic;
import com.workflow.engine.logic.executor.HttpLogicExecutor;
import com.workflow.engine.logic.parse.VariableResolver;
import com.workflow.engine.logicflow.dsl.LogicFlowDsl;
import com.workflow.engine.logicflow.dsl.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTTP 节点重试策略（消除双重重试）：
 * <ul>
 *   <li>节点级 retryCount 是唯一重试入口（引擎 executeNodeBody 包裹），传给 HttpLogicExecutor 的
 *       retryCount 为 0 —— 否则实际调用次数会变成 (1+节点级)×(1+config)；</li>
 *   <li>存量 DSL 兼容：节点级未配置时才回退到 config.retryCount 旧值，保证旧流程行为不降级。</li>
 * </ul>
 */
class LogicFlowHttpRetryPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpLogicExecutor httpExecutor;
    private LogicFlowEngine engine;

    @BeforeEach
    void setUp() {
        httpExecutor = mock(HttpLogicExecutor.class);
        GroovyScriptLogic groovyScriptLogic = mock(GroovyScriptLogic.class);
        BackendBeanRegistry beanRegistry = mock(BackendBeanRegistry.class);
        VariableResolver variableResolver = mock(VariableResolver.class);
        when(variableResolver.resolve(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        engine = new LogicFlowEngine(httpExecutor, groovyScriptLogic, beanRegistry, variableResolver,
                objectMapper);
    }

    private LogicFlowDsl.NodeDef httpNode(Integer nodeRetryCount, Integer configRetryCount) {
        LogicFlowDsl.NodeDef node = new LogicFlowDsl.NodeDef();
        node.setId("http_1");
        node.setType(NodeType.HTTP);
        node.setName("调用风控");
        ObjectNode config = objectMapper.createObjectNode();
        config.put("url", "https://api.example.com/risk");
        config.put("method", "GET");
        if (configRetryCount != null) {
            config.put("retryCount", configRetryCount);
        }
        node.setConfig(config);
        node.setRetryCount(nodeRetryCount);
        return node;
    }

    private static LogicFlowDsl.NodeDef plainNode(String id, NodeType type) {
        LogicFlowDsl.NodeDef node = new LogicFlowDsl.NodeDef();
        node.setId(id);
        node.setType(type);
        node.setName(id);
        return node;
    }

    private static LogicFlowDsl.EdgeDef edge(String source, String target) {
        LogicFlowDsl.EdgeDef edge = new LogicFlowDsl.EdgeDef();
        edge.setId(source + "->" + target);
        edge.setSource(source);
        edge.setTarget(target);
        return edge;
    }

    /** 捕获所有调用传给 HttpLogicExecutor 的 retryCount（第 9 个入参）。 */
    private List<Integer> capturedRetryArgs(int expectedCalls) {
        ArgumentCaptor<Integer> retryCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(httpExecutor, times(expectedCalls)).execute(anyString(), anyString(), anyMap(),
                anyList(), anyList(), anyMap(), anyInt(), anyInt(), retryCaptor.capture());
        return retryCaptor.getAllValues();
    }

    @Test
    void nodeLevelRetryIsTheOnlyRetry_sendsZeroToHttpExecutor() {
        // 节点级 2 次重试、config 遗留 3 次：只应按节点级重试 3 次调用（1+2），且传 0 给执行器
        when(httpExecutor.execute(anyString(), anyString(), anyMap(), anyList(), anyList(), anyMap(),
                anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("外部接口 500"));

        LogicFlowDsl flow = new LogicFlowDsl();
        flow.setNodes(List.of(plainNode("start_1", NodeType.START),
                httpNode(2, 3),
                plainNode("end_1", NodeType.END)));
        flow.setEdges(List.of(edge("start_1", "http_1"), edge("http_1", "end_1")));

        LogicFlowEngine.RunOutcome outcome = engine.run(flow, Map.of());

        assertThat(outcome.status()).isEqualTo("FAILED");
        // 双重重试修复前会是 (1+2)×(1+3)=12 次；现在恒等于 1+节点级
        assertThat(capturedRetryArgs(3)).containsExactly(0, 0, 0);
    }

    @Test
    void legacyConfigRetryFallsBackOnlyWhenNodeLevelMissing() {
        when(httpExecutor.execute(anyString(), anyString(), anyMap(), anyList(), anyList(), anyMap(),
                anyInt(), anyInt(), anyInt())).thenReturn("{\"ok\":true}");

        LogicFlowDsl flow = new LogicFlowDsl();
        flow.setNodes(List.of(plainNode("start_1", NodeType.START),
                httpNode(null, 2), // 存量 DSL：重试只写在 config 里
                plainNode("end_1", NodeType.END)));
        flow.setEdges(List.of(edge("start_1", "http_1"), edge("http_1", "end_1")));

        LogicFlowEngine.RunOutcome outcome = engine.run(flow, Map.of());

        assertThat(outcome.status()).isEqualTo("SUCCESS");
        // 节点级未配置 → 回退传 config 旧值（旧流程行为不降级），且仅调用一次（无外层重试）
        assertThat(capturedRetryArgs(1)).containsExactly(2);
    }

    @Test
    void zeroNodeLevelRetryDoesNotRetry() {
        when(httpExecutor.execute(anyString(), anyString(), anyMap(), anyList(), anyList(), anyMap(),
                anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("boom"));

        LogicFlowDsl flow = new LogicFlowDsl();
        flow.setNodes(List.of(plainNode("start_1", NodeType.START),
                httpNode(0, 0),
                plainNode("end_1", NodeType.END)));
        flow.setEdges(List.of(edge("start_1", "http_1"), edge("http_1", "end_1")));

        engine.run(flow, java.util.Map.of());

        assertThat(capturedRetryArgs(1)).containsExactly(0);
    }
}
