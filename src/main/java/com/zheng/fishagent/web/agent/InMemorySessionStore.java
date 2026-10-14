package com.zheng.fishagent.web.agent;

import com.zheng.fishagent.llm.ChatModel;
import com.zheng.fishagent.memory.ChatMemory;
import com.zheng.fishagent.memory.FileChatMemory;
import com.zheng.fishagent.memory.InMemoryChatMemory;
import com.zheng.fishagent.memory.MessageWindowChatMemory;
import com.zheng.fishagent.memory.SummarizingChatMemory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 默认的会话存储实现：进程内维护「会话标识 → 记忆」的映射。
 * <p>
 * <b>记忆结构是「并列」而不是「嵌套」</b>：底层（{@link #baseFactory}）只负责保存完整原文，
 * 运行期策略在其上二选一——
 * <ul>
 *   <li>启用摘要时用 {@link SummarizingChatMemory}：早期消息压成一条摘要，信息不硬丢失；</li>
 *   <li>关闭摘要时用 {@link MessageWindowChatMemory}：只把最近若干条送进模型。</li>
 * </ul>
 * 两者不会互相包裹。若把窗口套在摘要里层，摘要读到的「原文」其实是裁剪结果，
 * 会出现「早期消息压缩不到」「底层条数封顶后阈值永不触发」等问题（见
 * {@link SummarizingChatMemory} 类注释）。精简上下文本来就是摘要自己的职责。
 * <p>
 * <b>底层实现可替换</b>：默认是 {@link FileChatMemory}，会话按 JSONL 落盘到
 * {@code <user.dir>/.fishagent/sessions/<sessionId>.jsonl}，重启不丢；
 * 想换存储介质时不改本类，从外部传入新的 {@link #baseFactory} 即可。
 * <p>
 * 注意这里返回的是<b>未加锁</b>的记忆：{@code ReActAgent} 在构造时会自动用
 * {@code LockedChatMemory} 包装它，因此无需在此重复加锁。
 */
public class InMemorySessionStore implements SessionStore {

    /** SYSTEM 消息始终保留，这里限制的是「除 SYSTEM 外」参与上下文的最近消息条数。 */
    private static final int DEFAULT_WINDOW_SIZE = 20;

    private final ConcurrentMap<String, ChatMemory> memories = new ConcurrentHashMap<>();

    /** 底层记忆工厂：只保存完整原文，不做任何裁剪。 */
    private final Function<String, ChatMemory> baseFactory;

    private final int windowSize;
    private final boolean summarizeEnabled;
    private final int summarizeTrigger;
    private final int summarizeKeep;

    /** 用于生成摘要的模型；关闭摘要时为 null。 */
    private final ChatModel summarizeModel;

    /**
     * 默认：进程内内存原文 + 关闭摘要 + 默认窗口大小。
     */
    public InMemorySessionStore() {
        this(DEFAULT_WINDOW_SIZE, false, 20, 5, null);
    }

    public InMemorySessionStore(int windowSize) {
        this(windowSize, false, 20, 5, null);
    }

    /**
     * 底层为「进程内内存」的便捷构造，用于测试与不需要持久化的场景。
     * 需要落盘请改用 {@link #InMemorySessionStore(Function, int, boolean, int, int, ChatModel)}
     * 并传入 {@code id -> FileChatMemory.of(id, directory)}。
     */
    public InMemorySessionStore(int windowSize, boolean summarizeEnabled,
                                int summarizeTrigger, int summarizeKeep,
                                ChatModel summarizeModel) {
        this(InMemoryChatMemory::new,
                windowSize, summarizeEnabled, summarizeTrigger, summarizeKeep, summarizeModel);
    }

    /**
     * @param baseFactory      底层记忆工厂，入参为 sessionId，返回值<b>必须保存完整原文</b>；
     *                         典型实现就一行：{@code id -> FileChatMemory.of(id, directory)}
     * @param windowSize       关闭摘要时参与上下文的最近消息条数（作用于窗口装饰器，与底层无关）
     * @param summarizeEnabled 是否启用摘要压缩
     * @param summarizeTrigger 未摘要消息数超过该值时触发压缩
     * @param summarizeKeep    压缩后保留的最近消息条数
     * @param summarizeModel   生成摘要用的模型；启用摘要时必须非 null
     */
    public InMemorySessionStore(Function<String, ChatMemory> baseFactory,
                                int windowSize, boolean summarizeEnabled,
                                int summarizeTrigger, int summarizeKeep,
                                ChatModel summarizeModel) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize 必须大于 0，当前为 " + windowSize);
        }
        this.baseFactory = Objects.requireNonNull(baseFactory, "baseFactory 不能为空");
        this.windowSize = windowSize;
        this.summarizeEnabled = summarizeEnabled && summarizeModel != null;
        this.summarizeTrigger = summarizeTrigger;
        this.summarizeKeep = summarizeKeep;
        this.summarizeModel = summarizeModel;
    }

    @Override
    public ChatMemory memoryFor(String sessionId) {
        String id = requireSessionId(sessionId);
        // computeIfAbsent 保证同一会话并发首次访问只创建一个记忆实例
        return memories.computeIfAbsent(id, this::createMemory);
    }

    private ChatMemory createMemory(String id) {
        // 底层只负责落盘与提供完整原文；是否裁剪、是否摘要由运行期策略决定
        ChatMemory base = baseFactory.apply(id);
        if (summarizeEnabled) {
            return SummarizingChatMemory.of(base, summarizeModel, summarizeTrigger, summarizeKeep);
        }
        return MessageWindowChatMemory.windowing(base, windowSize);
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

    /**
     * 是否启用了摘要压缩，供状态接口展示。
     */
    public boolean summarizeEnabled() {
        return summarizeEnabled;
    }

    public int summarizeTrigger() {
        return summarizeTrigger;
    }

    public int summarizeKeep() {
        return summarizeKeep;
    }

    public int windowSize() {
        return windowSize;
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
