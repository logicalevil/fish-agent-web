package com.zheng.fishagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Fish-Agent SDK 的测试 Web 程序入口。
 * <p>
 * 本工程以「外部依赖」的方式消费 {@code com.zheng:fish-agent}，
 * 用来验证 SDK 的流式输出、工具调用、多轮记忆、异常回填等能力。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FishAgentWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(FishAgentWebApplication.class, args);
    }
}
