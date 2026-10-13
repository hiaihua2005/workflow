package com.workflow.ai.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.workflow.ai.model.ChatMessage;
import com.workflow.ai.model.ChatOptions;
import com.workflow.ai.model.PageRef;
import com.workflow.ai.model.ToolCall;
import com.workflow.ai.tool.AiToolContext;
import org.springframework.stereotype.Component;

/**
 * 客户端执行工具的 Agent 会话存储。
 *
 * <p>agent 循环遇到前端执行工具（{@code client_tool}）时暂停，会话（含完整消息上下文
 * 与待执行工具队列）暂存于此；前端执行完毕经 {@code /chat/continue} 回传结果续跑。
 * 内存态 + TTL 过期 + 容量上限，服务重启即失效（客户端收到会话过期错误后重新发起对话）。
 */
@Component
public class AiAgentSessionStore {

    /** 会话 TTL：10 分钟（前端执行 + 模型续跑的合理上限）。 */
    private static final long TTL_MS = 10 * 60 * 1000L;

    /** 最大并发会话数（防泄漏；超限时淘汰最旧）。 */
    private static final int MAX_SESSIONS = 200;

    /** 进行中的 agent 会话。 */
    public static class Session {
        public final String id;
        public final List<ChatMessage> messages = new ArrayList<>();
        public ChatOptions options;
        public AiToolContext context;
        public final Map<String, PageRef> navigations = new LinkedHashMap<>();
        public final List<ToolCall> queue = new ArrayList<>();
        /** 重复操作检测：最近一次客户端调用签名与连续重复次数。 */
        public String lastClientSig = "";
        public int repeatCount = 0;
        public int stepsUsed = 0;
        public final long startMs = System.currentTimeMillis();
        public long lastTouchMs = startMs;

        Session(String id) {
            this.id = id;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 新建会话并登记。 */
    public Session create(String sessionId) {
        prune();
        Session session = new Session(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    /** 取会话（懒过期：过期即删除并返回 null）。 */
    public Session get(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.lastTouchMs > TTL_MS) {
            sessions.remove(sessionId);
            return null;
        }
        session.lastTouchMs = System.currentTimeMillis();
        return session;
    }

    /** 会话正常结束（产出最终回复或步数耗尽）后移除。 */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    /** 过期清理 + 容量上限淘汰（按 lastTouch 最旧优先）。 */
    private void prune() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().lastTouchMs > TTL_MS) {
                it.remove();
            }
        }
        while (sessions.size() >= MAX_SESSIONS) {
            String oldest = null;
            long oldestTouch = Long.MAX_VALUE;
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                if (e.getValue().lastTouchMs < oldestTouch) {
                    oldestTouch = e.getValue().lastTouchMs;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            sessions.remove(oldest);
        }
    }
}
