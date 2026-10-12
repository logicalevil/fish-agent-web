package com.zheng.fishagent.web.config;

import com.zheng.fishagent.tool.FunctionTool;
import com.zheng.fishagent.tool.Tool;
import com.zheng.fishagent.web.agent.AgentFactory;
import com.zheng.fishagent.web.agent.AgentProperties;
import com.zheng.fishagent.web.agent.AgentSessionManager;
import com.zheng.fishagent.web.agent.InMemorySessionStore;
import com.zheng.fishagent.web.agent.SessionStore;
import com.zheng.fishagent.web.tool.DemoTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 把 SDK 的各个部件装配成 Spring Bean。
 * <p>
 * 装配顺序：工具 → AgentFactory → SessionStore → AgentSessionManager。
 * 其中 {@link SessionStore} 标了 {@link ConditionalOnMissingBean}，
 * 因此想换成持久化实现时，只需在别处提供一个自己的 {@code SessionStore} Bean，
 * 本配置会自动让位，无需改动这里的代码。
 */
@Configuration
public class AgentConfiguration {

    /**
     * 注解式工具集：由 SDK 扫描 {@code @AgentTool} 方法生成。
     * <p>
     * 注意它<b>不是</b> {@code Tool} 的集合——注解容器需要经
     * {@link com.zheng.fishagent.tool.ToolRegistry#register(Object)} 扫描后
     * 才变成一个个 {@code Tool}，因此这里单独作为 Bean 暴露。
     */
    @Bean
    public DemoTools demoTools() {
        return new DemoTools();
    }

    /**
     * Lambda 式工具，覆盖 SDK 支持的另一种注册方式。
     */
    @Bean
    public List<Tool> fishAgentFunctionTools() {
        return List.of(
                FunctionTool.builder()
                        .name("count_text")
                        .description("统计一段文本的字符数（按 Unicode 码点计）与行数")
                        .parameter("text", "string", "要统计的文本", true)
                        .function(arguments -> {
                            String text = String.valueOf(arguments.getOrDefault("text", ""));
                            int codePoints = text.codePointCount(0, text.length());
                            long lines = text.lines().count();
                            return "字符数：" + codePoints + "，行数：" + lines;
                        })
                        .build(),
                FunctionTool.builder()
                        .name("server_env")
                        .description("返回本测试程序的运行环境信息，例如 JDK 版本与操作系统")
                        .function(arguments -> "JDK " + System.getProperty("java.version")
                                + " / " + System.getProperty("os.name")
                                + " " + System.getProperty("os.arch"))
                        .build());
    }

    @Bean
    public AgentFactory agentFactory(AgentProperties properties,
                                     DemoTools demoTools,
                                     List<Tool> fishAgentFunctionTools) {
        return new AgentFactory(properties, demoTools, fishAgentFunctionTools);
    }

    /**
     * 默认的会话存储：进程内内存实现，重启即丢。
     * <p>
     * 想换成基于 SDK {@code FileChatMemory} 的持久化实现，或基于 Redis 的分布式实现，
     * 提供自己的 {@code SessionStore} Bean 覆盖即可。
     */
    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore sessionStore(AgentProperties properties) {
        return new InMemorySessionStore(properties.sessionWindowSize());
    }

    @Bean
    public AgentSessionManager agentSessionManager(SessionStore sessionStore, AgentFactory agentFactory) {
        return new AgentSessionManager(sessionStore, agentFactory);
    }
}
