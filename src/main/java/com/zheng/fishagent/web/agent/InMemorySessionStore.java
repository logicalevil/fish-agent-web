package com.zheng.fishagent.web.agent;

import com.zheng.fishagent.memory.ChatMemory;
import com.zheng.fishagent.memory.MessageWindowChatMemory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认的会话存储实现：进程内内存 Map，重启后会话丢失。
 * <p>
 * 适用于本地验证 SDK 效果。每个 sessionId 对应一个带滑动窗口的
 * {@link MessageWindowChatMemory}，窗口外的历史仍保留在底层记忆中（{@code fullHistory()} 可查）。
 */
public class InMemorySessionStore implements SessionStore {

    /** SYSTEM 消息始终保留，这里限制的是「除 SYSTEM 外」参与上下文的最近消息条数。 */
    private static final int DEFAULT_WINDOW_SIZE = 20;

    private final ConcurrentMap<String, ChatMemory> memories = new ConcurrentHashMap<>();
    private final int windowSize;

    public InMemorySessionStore() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public InMemorySessionStore(int windowSize) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize 必须大于 0，当前为 " + windowSize);
        }
        this.windowSize = windowSize;
    }

    @Override
    public ChatMemory memoryFor(String sessionId) {
        String id = requireSessionId(sessionId);
        // computeIfAbsent 保证同一会话并发首次访问只创建一个记忆实例
        return memories.computeIfAbsent(id, key -> MessageWindowChatMemory.of(key, windowSize));
    }

    @Override
    public void clear(String sessionId) {
        ChatMemory memory = memories.get(requireSessionId(sessionId));
        if (memory != null) {
            memory.clear();
        }
    }

    @Override
    public boolean remove(String sessionId) {
        return memories.remove(requireSessionId(sessionId)) != null;
    }

    @Override
    public int sessionCount() {
        return memories.size();
    }

    private static String requireSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        String trimmed = sessionId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空白");
        }
        return trimmed;
    }
}
