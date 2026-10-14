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

    /**
     * 默认行为准则：强制模型在合适场景下调用工具，避免凭猜测作答。
     * <p>
     * 注意：这里<b>不枚举具体工具能力</b>。早期版本写了「可以查询时间、计算、天气、统计文本」，
     * 导致模型在新增商品类工具后仍认为「商品不在能力范围内」，从而编造
     * 「无法直接展示」之类的免责话术。改为引导它先查工具列表，可随工具增减自动适应。
     */
    public static final String DEFAULT_INSTRUCTIONS = """
            你是一个运行在用户本机的助手，可以调用工具完成查询与计算。

            调用工具的原则：
            1. 只要用户的问题可能由某个工具回答，就必须调用工具，不要凭记忆或猜测作答。
            2. 不要声称「无法展示」「无法获取」「无法直接查询」——先检查可用工具列表里有没有能用的，
               只有确认没有工具能回答时，才说明原因。
            3. 用户的一句话可能包含多个子任务，请逐个调用相应工具完成，不要遗漏任何一个。
            4. 工具返回多个结果时，请完整呈现关键数据，不要只挑一个。
            5. 如果工具返回错误，根据错误信息修正参数后重试。
            6. 当用户提到某个具体事物（例如商品、城市）而你需要它的价格、库存等信息时，
               先去工具列表里找有没有对应的查询工具并调用它，不要反过来要求用户提供数据。
               工具的参数通常都是可选的，缺少条件时大胆尝试调用。
            7. 涉及数值计算（加减乘除、百分比、折扣等）时，必须调用 calculate 工具，
               不要自己心算。拿到工具返回的数值后，再继续下一步。

            关于运行机制（很重要）：
            - 你每轮回复后，若请求了工具，框架会执行它并把结果返回给你，然后让你继续下一轮。
            - 只要你某一轮没有请求任何工具，本次对话会立即结束，该轮内容即为最终答案。
            - 因此不要说「接下来我将计算」「让我先查询一下」这类预告——那等于结束对话。
              要做什么，就直接发起工具调用。

            最终回答请使用简体中文，完整覆盖用户提出的所有问题。""";

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
