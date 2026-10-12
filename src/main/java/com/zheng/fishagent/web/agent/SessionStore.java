package com.zheng.fishagent.web.agent;

import com.zheng.fishagent.memory.ChatMemory;

/**
 * 会话存储抽象：为每个 sessionId 提供一份独立的 {@link ChatMemory}。
 * <p>
 * 之所以需要这层抽象，是因为 Fish-Agent 的 {@code Agent} 是有状态且<b>非线程安全</b>的：
 * 它内部持有会话记忆，因此不能跨请求线程共享，只能「一个会话一个实例」。
 * 把「记忆从哪来」抽出来之后，替换后端就只需新增一个实现，业务代码不用动：
 *
 * <ul>
 *   <li>{@link InMemorySessionStore} —— 默认实现，进程内 {@code ConcurrentHashMap}，重启即丢。</li>
 *   <li>后续想持久化，可实现一个基于 SDK {@code FileChatMemory} 的 store；
 *       想横向扩展，可实现一个基于 Redis 的 store。</li>
 * </ul>
 *
 * 实现类需要保证：同一 sessionId 并发调用返回的记忆对象是同一个、且过程线程安全。
 */
public interface SessionStore {

    /**
     * 取出（不存在则创建）指定会话的记忆。
     *
     * @param sessionId 会话标识，不能为空
     * @return 该会话独有的记忆实例
     */
    ChatMemory memoryFor(String sessionId);

    /**
     * 清空指定会话的记忆。会话不存在时静默返回。
     */
    void clear(String sessionId);

    /**
     * 移除指定会话（含其记忆与相关资源）。
     *
     * @return 之前是否存在该会话
     */
    boolean remove(String sessionId);

    /**
     * 当前登记的会话数量，用于监控/调试。
     */
    int sessionCount();
}
