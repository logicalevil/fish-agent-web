package com.zheng.fishagent.web.agent;

import com.zheng.fishagent.agent.Agent;
import com.zheng.fishagent.agent.AgentListener;
import com.zheng.fishagent.agent.AgentResult;
import com.zheng.fishagent.memory.ChatMemory;
import com.zheng.fishagent.message.Message;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话管理器：把「会话标识」映射到「一个独占的 Agent 实例」，并负责并发安全。
 * <p>
 * Fish-Agent 的 {@code Agent} 本体不带线程安全保证，同一个会话若被两个请求线程同时 run，
 * 记忆与会话内部状态会被交叉写入。这里用「每会话一把锁」把同一会话的请求串行化，
 * 不同会话之间仍然完全并行。注意：<b>锁必须与 Agent 一一对应，不能全局一把锁</b>，
 * 否则一个慢会话会拖住所有会话。
 * <p>
 * 记忆来源委托给 {@link SessionStore}，因此更换存储后端不影响本类。
 */
public class AgentSessionManager {

    /**
     * 一个会话的运行时载体：Agent 与其专属锁、记忆。
     * <p>
     * <b>为什么用 {@link ReentrantLock} 而不是 SDK 的 {@code MemoryLock}</b>：
     * 两者的临界区语义不同，不可混用。
     * <ul>
     *   <li>这里的锁要包住<b>整个 run</b>——包含模型调用，可能持续数十秒；</li>
     *   <li>同一会话的请求应当<b>排队等待</b>（它们通常来自同一个用户，等前者完成是预期行为），
     *       而 {@code MemoryLock#execute} 在取不到锁时会抛异常，用在这里会让用户的第二个问题
     *       直接报错。</li>
     * </ul>
     */
    private record Session(Agent agent, ReentrantLock lock, ChatMemory memory) {
    }

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SessionStore sessionStore;
    private final AgentFactory agentFactory;

    public AgentSessionManager(SessionStore sessionStore, AgentFactory agentFactory) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore 不能为空");
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory 不能为空");
    }

    /**
     * 取得指定会话的 Agent 并执行一轮对话。
     * <p>
     * 同一会话的调用会被串行化；不同会话互不阻塞。
     * <p>
     * <b>为什么锁的粒度是「整个 run」而不是「单次记忆操作」</b>：
     * 一次 run 内部的「读—改—写」是跨多次记忆访问的——先读取历史构造请求，
     * 再发起模型调用（可能数十秒），最后才写入新消息。若只锁单次记忆操作
     * （SDK 的 {@code MemoryLock} 正是这个粒度），会留下这样一段窗口：
     * <pre>
     *   线程A: [读] ─── 模型调用(20s) ─── [写]
     *   线程B:      [读] ─── 模型调用(20s) ─── [写]
     *              ↑ 两者读到相同历史，B 看不到 A 的写入
     * </pre>
     * 结果是消息不会丢（记忆锁保证了这点），但 B 的推理基于过期上下文，可能给出互相矛盾的回答。
     * 因此这里必须把锁提到 run 级别，让同一会话的推理真正串行。
     */
    public AgentResult chat(String sessionId, String userInput, AgentListener listener) {
        Session session = sessionFor(sessionId);
        session.lock().lock();
        try {
            return session.agent().run(userInput, listener);
        } finally {
            session.lock().unlock();
        }
    }

    /**
     * 读取指定会话的记忆消息（只读快照），不存在时返回空列表。
     */
    public List<Message> history(String sessionId) {
        Session session = sessions.get(normalize(sessionId));
        if (session == null) {
            return List.of();
        }
        session.lock().lock();
        try {
            return List.copyOf(session.memory().messages());
        } finally {
            session.lock().unlock();
        }
    }

    /**
     * 清空指定会话记忆，但保留 Agent 实例。
     */
    public void reset(String sessionId) {
        Session session = sessions.get(normalize(sessionId));
        if (session == null) {
            // 尚未建立运行时会话，直接清底层存储即可（通常是空操作）
            sessionStore.clear(sessionId);
            return;
        }
        session.lock().lock();
        try {
            session.agent().reset();
        } finally {
            session.lock().unlock();
        }
    }

    /**
     * 销毁指定会话（释放 Agent 与记忆）。
     */
    public boolean destroy(String sessionId) {
        Session removed = sessions.remove(normalize(sessionId));
        sessionStore.remove(sessionId);
        return removed != null;
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    /** 已登记的会话标识快照，便于调试。 */
    public Set<String> sessionIds() {
        return Set.copyOf(sessions.keySet());
    }

    private Session sessionFor(String sessionId) {
        String id = normalize(sessionId);
        return sessions.computeIfAbsent(id, key -> {
            ChatMemory memory = sessionStore.memoryFor(key);
            Agent agent = agentFactory.create(memory);
            return new Session(agent, new ReentrantLock(), memory);
        });
    }

    private static String normalize(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        String trimmed = sessionId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空白");
        }
        return trimmed;
    }
}
