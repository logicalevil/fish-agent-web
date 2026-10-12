package com.zheng.fishagent.web.agent;

import com.zheng.fishagent.agent.ToolCallMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 相关配置，前缀 {@code fish-agent}。
 * <p>
 * 默认值对齐 SDK 自身的默认值，便于「不配任何东西也能跑起来」。
 *
 * @param baseUrl              Ollama 服务地址
 * @param model                模型名，例如 qwen2.5:7b
 * @param temperature          采样温度
 * @param mode                 工具调用策略：native（原生 Tool Calling）或 react（文本 ReAct）
 * @param streaming            是否流式输出
 * @param maxIterations        单次 run 的最大推理轮数
 * @param maxRepeatedToolCalls 同一工具调用允许重复的最大次数，超过判定为死循环
 * @param sessionWindowSize    会话记忆滑动窗口大小（SYSTEM 消息始终保留）
 * @param instructions         追加的行为准则，与 SDK 内置 ReAct 说明拼接
 */
@ConfigurationProperties(prefix = "fish-agent")
public record AgentProperties(
        String baseUrl,
        String model,
        double temperature,
        String mode,
        boolean streaming,
        int maxIterations,
        int maxRepeatedToolCalls,
        int sessionWindowSize,
        String instructions) {

    public AgentProperties {
        baseUrl = isBlank(baseUrl) ? "http://localhost:11434" : baseUrl;
        model = isBlank(model) ? "qwen2.5:7b" : model;
        mode = isBlank(mode) ? "native" : mode;
        maxIterations = maxIterations <= 0 ? 8 : maxIterations;
        maxRepeatedToolCalls = maxRepeatedToolCalls <= 0 ? 3 : maxRepeatedToolCalls;
        sessionWindowSize = sessionWindowSize <= 0 ? 20 : sessionWindowSize;
        if (instructions == null) {
            instructions = DEFAULT_INSTRUCTIONS;
        }
    }

    /** 默认行为准则：强制模型在合适场景下调用工具，避免凭猜测作答。 */
    public static final String DEFAULT_INSTRUCTIONS = """
            你是一个运行在用户本机的助手，可以调用工具查询时间、计算表达式、查询天气、统计文本。
            涉及时间、计算、天气、文本统计的问题必须调用工具，不要凭猜测回答。
            如果工具返回错误，请根据错误信息修正参数后重试。
            最终回答请使用简体中文，简洁直接。""";

    /**
     * 把配置里的字符串解析成 SDK 的 {@link ToolCallMode}。
     * <p>
     * 无法识别时回退到 {@link ToolCallMode#NATIVE_TOOLS}，而不是启动失败——
     * 这毕竟是个用于验证效果的测试程序。
     */
    public ToolCallMode toolCallMode() {
        return "react".equalsIgnoreCase(mode) || "react_text".equalsIgnoreCase(mode)
                ? ToolCallMode.REACT_TEXT
                : ToolCallMode.NATIVE_TOOLS;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
