package com.zheng.fishagent.web.tool;

import com.zheng.fishagent.example.Calculator;
import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注解式工具集：只需在方法上标注 {@link AgentTool}，参数上标注 {@link ToolParam}。
 * <p>
 * 本类故意覆盖了几种典型返回类型，用来验证 SDK 的返回值处理：
 * <ul>
 *   <li>{@link #currentTime} 返回 {@code String}</li>
 *   <li>{@link #queryWeather} 返回 {@code Map}（会被序列化成 JSON 交给模型）</li>
 *   <li>{@link #divide} 在除零时抛异常（异常应被 SDK 转成错误文本回填给模型）</li>
 * </ul>
 */
public class DemoTools {

    private static final Map<String, String> ZONE_IDS = new LinkedHashMap<>();

    static {
        ZONE_IDS.put("北京", "Asia/Shanghai");
        ZONE_IDS.put("上海", "Asia/Shanghai");
        ZONE_IDS.put("香港", "Asia/Hong_Kong");
        ZONE_IDS.put("东京", "Asia/Tokyo");
        ZONE_IDS.put("伦敦", "Europe/London");
        ZONE_IDS.put("巴黎", "Europe/Paris");
        ZONE_IDS.put("纽约", "America/New_York");
        ZONE_IDS.put("洛杉矶", "America/Los_Angeles");
    }

    @AgentTool("获取指定城市的当前日期与时间，城市为空时返回本机时间")
    public String currentTime(
            @ToolParam(value = "城市名称，例如：北京、东京、伦敦", required = false) String city) {

        ZonedDateTime now;
        if (city == null || city.isBlank()) {
            now = ZonedDateTime.now();
        } else {
            String zoneId = ZONE_IDS.get(city.trim());
            if (zoneId == null) {
                return "未知城市：" + city + "，当前支持：" + ZONE_IDS.keySet();
            }
            now = ZonedDateTime.now(ZoneId.of(zoneId));
        }
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "（" + now.getZone() + "）";
    }

    @AgentTool("计算一个数学表达式，支持 + - * / % ^ 与括号")
    public String calculate(
            @ToolParam("需要计算的表达式，例如 (1+2)*3 或 2^10") String expression) {
        return expression + " = " + Calculator.format(Calculator.evaluate(expression));
    }

    @AgentTool("查询指定城市的天气（演示用的模拟数据，不联网）")
    public Object queryWeather(
            @ToolParam("城市名称，不能为空") String city,
            @ToolParam(value = "温度单位：celsius 或 fahrenheit", required = false) String unit) {

        if (city == null || city.isBlank()) {
            return "错误：必须提供城市名称，例如 queryWeather(city=\"东京\")";
        }
        List<String> conditions = List.of("晴", "多云", "小雨", "阴", "雷阵雨");
        int seed = Math.abs(city.hashCode());
        double celsius = 10 + seed % 200 / 10.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("condition", conditions.get(seed % conditions.size()));
        if ("fahrenheit".equalsIgnoreCase(unit)) {
            result.put("temperature", Math.round(celsius * 9 / 5 + 32));
            result.put("unit", "fahrenheit");
        } else {
            result.put("temperature", celsius);
            result.put("unit", "celsius");
        }
        result.put("updatedAt", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 故意可能失败的工具，用于验证 SDK 的异常回填机制：
     * 工具抛出的异常不应中断整个 run，而应变成错误文本让模型自行纠正。
     */
    @AgentTool("执行两个数的除法运算。除数不能为 0，否则会报错")
    public String divide(
            @ToolParam("被除数") double dividend,
            @ToolParam("除数，不能为 0") double divisor) {
        if (divisor == 0) {
            throw new IllegalArgumentException("除数不能为 0");
        }
        return dividend + " / " + divisor + " = " + Calculator.format(dividend / divisor);
    }

    @AgentTool("把一段文本反转。适合验证无需推理的纯工具调用场景")
    public String reverseText(
            @ToolParam("需要反转的文本") String text) {
        if (text == null) {
            return "";
        }
        return new StringBuilder(text).reverse().toString();
    }
}
