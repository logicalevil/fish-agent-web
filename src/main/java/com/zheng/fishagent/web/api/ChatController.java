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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地演示用的对话接口。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>两个对话入口并存</b>：{@code POST /api/chat} 同步返回完整结果（便于 curl 调试与查看
 *       事件时间线），{@code GET /api/chat/stream} 以 SSE 边生成边推送（用于真实演示体验）。</li>
 *   <li>接口层不做任何并发控制——同一会话的串行化由 {@link AgentSessionManager}
 *       的每会话锁负责，{@link AgentFactory} 也保证每个会话独占一个 Agent 实例。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    /** SSE 连接的最长存活时间：一次 run 可能包含多轮模型调用，取值偏宽裕。 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final AgentSessionManager sessionManager;
    private final AgentFactory agentFactory;
    private final SessionStore sessionStore;
    private final ExecutorService agentExecutor;

    public ChatController(AgentSessionManager sessionManager,
                          AgentFactory agentFactory,
                          SessionStore sessionStore,
                          ExecutorService agentExecutor) {
        this.sessionManager = sessionManager;
        this.agentFactory = agentFactory;
        this.sessionStore = sessionStore;
        this.agentExecutor = agentExecutor;
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
     * SSE 流式对话：边生成边推送，前端用 {@code EventSource} 接收。
     * <p>
     * <b>为什么必须放到独立线程</b>：{@code sessionManager.chat} 是阻塞调用（一次 run 可能耗时
     * 数十秒）。若直接在 Tomcat 的工作线程里执行，会占满线程池，并发几个请求就不可用了。
     * 因此这里提交到专用线程池，控制器方法立即返回 {@link SseEmitter}。
     * <p>
     * <b>事件格式</b>：每个事件都是一个 JSON 对象，形如 {@code {"type":"token","data":"..."}}。
     * 类型包括 token / toolCall / toolResult / message / finish / error。
     * 前端按 {@code type} 分发即可。
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId,
                             @RequestParam String message) {

        String id = normalizeSession(sessionId);
        String input = message == null ? "" : message.trim();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 客户端断开时把 emitter 标记为已失效，避免继续往里写
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        agentExecutor.submit(() -> {
            try {
                if (input.isEmpty()) {
                    send(emitter, closed, "error", Map.of("message", "message 不能为空"));
                    emitter.complete();
                    return;
                }

                AgentListener listener = new SseListener(emitter, closed);
                AgentResult result = sessionManager.chat(id, input, listener);

                send(emitter, closed, "finish", Map.of(
                        "stopReason", result.stopReason(),
                        "iterations", result.iterations(),
                        "totalTokens", result.usage().totalTokens()));
                emitter.complete();
            } catch (Exception e) {
                // 已经结束或客户端断开时，completeWithError 会再抛异常，这里一并吞掉
                send(emitter, closed, "error", Map.of(
                        "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 客户端已断开，无需处理
                }
            }
        });

        return emitter;
    }

    /**
     * 向 SSE 连接写一个事件。客户端已断开时静默忽略。
     * <p>
     * <b>关键</b>：{@code emitter.send} 在客户端断开时会抛 IOException。SDK 的回调
     * <b>不做异常隔离</b>（见 {@code CompositeAgentListener} 的说明），一旦让异常逃逸出去，
     * 整个 Agent 运行会被中断。所以这里必须吞掉 IO 异常。
     */
    private static void send(SseEmitter emitter, AtomicBoolean closed, String type, Map<String, Object> payload) {
        if (closed.get()) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.putAll(payload);
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            closed.set(true);
        }
    }

    /**
     * 把 Agent 事件桥接到 SSE。所有回调都运行在同一个线程（SDK 不创建线程），因此无需同步。
     */
    private static final class SseListener implements AgentListener {

        private final SseEmitter emitter;
        private final AtomicBoolean closed;

        private SseListener(SseEmitter emitter, AtomicBoolean closed) {
            this.emitter = emitter;
            this.closed = closed;
        }

        @Override
        public void onStart(String userInput) {
            send(emitter, closed, "start", Map.of("userInput", userInput));
        }

        @Override
        public void onThought(String thought) {
            send(emitter, closed, "thought", Map.of("thought", thought));
        }

        @Override
        public void onToken(String token) {
            send(emitter, closed, "token", Map.of("data", token));
        }

        @Override
        public void onMessage(Message message) {
            send(emitter, closed, "message", Map.of(
                    "role", message.role().value(),
                    "hasToolCalls", message.hasToolCalls(),
                    "content", message.content()));
        }

        @Override
        public void onToolCall(ToolCall call) {
            send(emitter, closed, "toolCall", Map.of(
                    "name", call.name(),
                    "arguments", call.arguments()));
        }

        @Override
        public void onToolResult(ToolCall call, String result) {
            send(emitter, closed, "toolResult", Map.of(
                    "name", call.name(),
                    "result", result == null ? "" : result));
        }

        @Override
        public void onError(Throwable error) {
            send(emitter, closed, "error", Map.of(
                    "message", String.valueOf(error.getMessage()),
                    "type", error.getClass().getSimpleName()));
        }
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
