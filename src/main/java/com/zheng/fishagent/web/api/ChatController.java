package com.zheng.fishagent.web.api;

import com.zheng.fishagent.agent.AgentListener;
import com.zheng.fishagent.agent.AgentResult;
import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.tool.Tool;
import com.zheng.fishagent.tool.ToolSpec;
import com.zheng.fishagent.web.agent.AgentFactory;
import com.zheng.fishagent.web.agent.AgentSessionManager;
import com.zheng.fishagent.web.agent.SessionStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地演示用的对话接口。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>对话是<b>同步</b>接口（{@code ChatRequest}/{@code ChatResponse}），流式分片通过
 *       {@link AgentListener} 收集后一并返回。这样避免引入 SSE 的复杂度，
 *       同时仍然展示「流式输出确实发生了」。</li>
 *   <li>接口层不做任何并发控制——同一会话的串行化由 {@link AgentSessionManager}
 *       的每会话锁负责，{@link AgentFactory} 也保证每个会话独占一个 Agent 实例。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentSessionManager sessionManager;
    private final AgentFactory agentFactory;
    private final SessionStore sessionStore;

    public ChatController(AgentSessionManager sessionManager,
                          AgentFactory agentFactory,
                          SessionStore sessionStore) {
        this.sessionManager = sessionManager;
        this.agentFactory = agentFactory;
        this.sessionStore = sessionStore;
    }

    /**
     * 发送一条消息，返回最终答案与过程事件。
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        String sessionId = normalizeSession(request.sessionId());
        String input = request.message() == null ? "" : request.message().trim();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        CollectingListener listener = new CollectingListener();

        long startedAt = System.currentTimeMillis();
        AgentResult result;
        try {
            result = sessionManager.chat(sessionId, input, listener);
        } catch (RuntimeException e) {
            // 模型不可用等异常：把原因原样带回前端，便于排查
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("sessionId", sessionId);
            error.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            error.put("elapsedMs", System.currentTimeMillis() - startedAt);
            return error;
        }
        long elapsed = System.currentTimeMillis() - startedAt;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("answer", result.text());
        body.put("stopReason", result.stopReason());
        body.put("iterations", result.iterations());
        body.put("reachedMaxIterations", result.reachedMaxIterations());
        body.put("tokenUsage", Map.of(
                "promptTokens", result.usage().promptTokens(),
                "completionTokens", result.usage().completionTokens(),
                "totalTokens", result.usage().totalTokens()));
        body.put("elapsedMs", elapsed);
        // 流式模式下把收到的分片拼出来，用来直观确认流式确实生效
        body.put("streamedText", listener.streamed.toString());
        body.put("streamChunkCount", listener.chunkCount);
        body.put("events", listener.events);
        return body;
    }

    /**
     * 指定会话的记忆消息（用于观察多轮记忆是否生效）。
     */
    @GetMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> history(@PathVariable String sessionId) {
        List<Message> messages = sessionManager.history(normalizeSession(sessionId));
        return messages.stream().map(ChatController::describe).toList();
    }

    /**
     * 清空指定会话的记忆，但保留 Agent 实例（下一条消息仍是同一会话）。
     */
    @PostMapping(value = "/sessions/{sessionId}/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reset(@PathVariable String sessionId) {
        String id = normalizeSession(sessionId);
        sessionManager.reset(id);
        return Map.of("sessionId", id, "reset", true,
                "remainingMessages", sessionManager.history(id).size());
    }

    /**
     * 销毁指定会话（释放 Agent 与记忆），下次同 id 会重新创建。
     */
    @DeleteMapping(value = "/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> destroy(@PathVariable String sessionId) {
        String id = normalizeSession(sessionId);
        boolean existed = sessionManager.destroy(id);
        return Map.of("sessionId", id, "destroyed", existed,
                "activeSessions", sessionManager.activeSessionCount());
    }

    /**
     * 已注册的工具清单（验证 Schema 自动生成的结果）。
     */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> tools() {
        return agentFactory.tools().stream().map(tool -> {
            ToolSpec spec = tool.spec();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", spec.name());
            item.put("description", spec.description());
            item.put("parameters", spec.parameters());
            return item;
        }).toList();
    }

    /**
     * 运行时概览：当前模式、活跃会话数、存储中的会话数。
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", agentFactory.mode().name());
        body.put("toolCount", agentFactory.tools().size());
        body.put("activeSessions", sessionManager.activeSessionCount());
        body.put("storedSessions", sessionStore.sessionCount());
        body.put("sessionIds", sessionManager.sessionIds());
        return body;
    }

    /**
     * 把消息转成便于前端展示的结构。
     */
    private static Map<String, Object> describe(Message message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", message.role().value());
        item.put("content", message.content());
        if (message.hasToolCalls()) {
            item.put("toolCalls", message.toolCalls().stream()
                    .map(call -> Map.of("name", call.name(), "arguments", call.arguments()))
                    .toList());
        }
        if (message.toolName() != null) {
            item.put("toolName", message.toolName());
        }
        return item;
    }

    private static String normalizeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId.trim();
    }

    /** 请求体。 */
    public record ChatRequest(String sessionId, String message) {
    }

    /**
     * 收集过程事件，供接口一并返回。
     * <p>
     * 注意：SDK 的 {@code run} 与所有回调都在<b>同一个线程</b>上执行，
     * 因此这里不需要任何同步。
     */
    private static final class CollectingListener implements AgentListener {

        private final List<Map<String, Object>> events = new java.util.ArrayList<>();
        private final StringBuilder streamed = new StringBuilder();
        private int chunkCount;

        @Override
        public void onStart(String userInput) {
            add("start", Map.of("userInput", userInput));
        }

        @Override
        public void onThought(String thought) {
            add("thought", Map.of("thought", thought));
        }

        @Override
        public void onToken(String token) {
            // 只在首个分片时记事件，避免事件列表被 token 淹没
            if (chunkCount == 0) {
                add("stream", Map.of("note", "开始接收流式分片"));
            }
            chunkCount++;
            streamed.append(token);
        }

        @Override
        public void onMessage(Message message) {
            add("message", Map.of(
                    "role", message.role().value(),
                    "hasToolCalls", message.hasToolCalls(),
                    "content", abbreviate(message.content())));
        }

        @Override
        public void onToolCall(ToolCall call) {
            add("toolCall", Map.of("name", call.name(), "arguments", call.arguments()));
        }

        @Override
        public void onToolResult(ToolCall call, String result) {
            add("toolResult", Map.of("name", call.name(), "result", abbreviate(result)));
        }

        @Override
        public void onFinish(AgentResult result) {
            add("finish", Map.of("stopReason", result.stopReason(), "iterations", result.iterations()));
        }

        @Override
        public void onError(Throwable error) {
            add("error", Map.of("message", String.valueOf(error.getMessage()),
                    "type", error.getClass().getSimpleName()));
        }

        private void add(String type, Map<String, Object> payload) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.putAll(payload);
            events.add(event);
        }

        private static String abbreviate(String text) {
            if (text == null) {
                return "";
            }
            return text.length() <= 300 ? text : text.substring(0, 300) + "…";
        }
    }
}
