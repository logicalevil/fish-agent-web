package com.zheng.fishagent.web.agent;

import com.zheng.fishagent.FishAgent;
import com.zheng.fishagent.agent.Agent;
import com.zheng.fishagent.agent.ToolCallMode;
import com.zheng.fishagent.memory.ChatMemory;
import com.zheng.fishagent.tool.Tool;
import com.zheng.fishagent.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;

/**
 * 按会话组装 {@link Agent}。
 * <p>
 * 关键点：{@code Agent} 内部持有不可变缓存的系统提示词，且<b>工具必须在首次 run 之前注册完毕</b>，
 * 所以每个会话都从头构建一个 Agent，并独占传入的 {@link ChatMemory}。
 * 工具集是只读共享的——SDK 的工具实现本身无状态，可安全复用。
 */
public class AgentFactory {

    private final AgentProperties properties;
    private final ToolRegistry toolRegistry;

    /**
     * @param properties    行为配置
     * @param functionTools Lambda 式工具；可以为 {@code null}
     * @param annotated    若干注解式工具容器；SDK 会扫描各自的 {@code @AgentTool} 方法，
     *                      可以为空。用可变参数是为了新增工具时不必修改本构造签名
     */
    public AgentFactory(AgentProperties properties,
                        List<Tool> functionTools,
                        Object... annotated) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        ToolRegistry registry = new ToolRegistry();
        // 注解容器走 register(Object)，由 SDK 反射扫描并生成 Schema
        if (annotated != null) {
            for (Object container : annotated) {
                if (container != null) {
                    registry.register(container);
                }
            }
        }
        if (functionTools != null) {
            functionTools.forEach(registry::register);
        }
        this.toolRegistry = registry;
    }

    /**
     * 为指定记忆创建一个全新的、独占该记忆的 Agent。
     *
     * @param memory 该会话独有的记忆，由 {@link SessionStore} 提供
     */
    public Agent create(ChatMemory memory) {
        return FishAgent.builder()
                .ollama(properties.baseUrl(), properties.model())
                .temperature(properties.temperature())
                .tools(toolRegistry)
                .memory(memory)
                .mode(properties.toolCallMode())
                .streaming(properties.streaming())
                .maxIterations(properties.maxIterations())
                .maxRepeatedToolCalls(properties.maxRepeatedToolCalls())
                .instructions(properties.instructions())
                .build();
    }

    public List<Tool> tools() {
        return toolRegistry.all();
    }

    public ToolCallMode mode() {
        return properties.toolCallMode();
    }
}
