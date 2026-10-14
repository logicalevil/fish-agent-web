package com.zheng.fishagent.web.config;

import com.zheng.fishagent.tool.FunctionTool;
import com.zheng.fishagent.tool.Tool;
import com.zheng.fishagent.web.agent.AgentFactory;
import com.zheng.fishagent.web.agent.AgentProperties;
import com.zheng.fishagent.web.agent.AgentSessionManager;
import com.zheng.fishagent.web.agent.InMemorySessionStore;
import com.zheng.fishagent.web.agent.SessionStore;
import com.zheng.fishagent.web.tool.AdvancedTools;
import com.zheng.fishagent.web.tool.CityTools;
import com.zheng.fishagent.web.tool.DemoTools;
import com.zheng.fishagent.web.tool.TodoTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
     * 基础工具集：简单参数、Map 返回、异常回填等典型场景。
     */
    @Bean
    public DemoTools demoTools() {
        return new DemoTools();
    }

    /**
     * 复杂参数工具集：枚举、数组、嵌套 record、长返回值、无参工具。
     * <p>
     * 单独成类是因为 {@link DemoTools} 有明确的教学定位（覆盖典型返回类型），
     * 本类验证的是另一批目标——Schema 递归生成与类型转换的容错。
     */
    @Bean
    public AdvancedTools advancedTools() {
        return new AdvancedTools();
    }

    /**
     * 城市信息工具集：聚合型查询，用于构造多轮工具协作的场景。
     */
    @Bean
    public CityTools cityTools() {
        return new CityTools();
    }

    /**
     * 有副作用的工具集：验证跨调用状态保持与重复调用检测。
     */
    @Bean
    public TodoTools todoTools() {
        return new TodoTools();
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

    /**
     * 执行 Agent 的专用线程池，供 SSE 接口使用。
     * <p>
     * <b>为什么需要它</b>：一次 run 是阻塞调用且可能耗时数十秒，若直接在 Tomcat 工作线程里执行，
     * 并发几个请求就会耗尽线程池。把 Agent 执行隔离到独立线程池，既能避免阻塞 Web 层，
     * 也能为这类长任务单独设定队列与拒绝策略。
     * <p>
     * 队列容量有限且拒绝策略为 AbortPolicy：宁可快速失败并告知调用方，也不要无限堆积请求——
     * 后者会让延迟不断累积，最终所有请求一起超时。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return new ThreadPoolExecutor(
                // 核心线程数：与常见并发会话数匹配
                4, 16,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("fish-agent-" + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 把全部注解式工具容器汇总后交给工厂，由 SDK 统一扫描。
     * <p>
     * 之所以传数组而非在工厂里逐个列举，是为了新增工具时只需加一个 Bean，
     * 不必再改工厂的构造签名。
     */
    @Bean
    public AgentFactory agentFactory(AgentProperties properties,
                                     List<Tool> fishAgentFunctionTools,
                                     DemoTools demoTools,
                                     AdvancedTools advancedTools,
                                     CityTools cityTools,
                                     TodoTools todoTools) {
        return new AgentFactory(properties, fishAgentFunctionTools,
                demoTools, advancedTools, cityTools, todoTools);
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
