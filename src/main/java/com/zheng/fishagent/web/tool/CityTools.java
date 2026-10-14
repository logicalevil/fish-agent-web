package com.zheng.fishagent.web.tool;

import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 城市信息工具集，用于验证<b>多轮工具协作</b>。
 * <p>
 * 与 {@link DemoTools} 里按维度拆开的工具不同，本类提供的是<b>聚合型</b>查询：
 * 一次调用返回时区、温度、天气等多项数据。配合 {@code calculate} 使用，
 * 可以构造出需要「多次工具调用 + 中间结果传递」的问题，例如：
 * <pre>
 *   北京和东京现在的温差是多少？
 *   → getCityInfo(北京) + getCityInfo(东京) + calculate(差值)
 * </pre>
 * 这恰好覆盖 Agent 最核心的能力：一轮内多次迭代、跨工具的上下文传递。
 * <p>
 * 天气为确定性的模拟数据（按城市名哈希生成），不联网，保证结果可复现。
 */
public class CityTools {

    /** 支持的城市及其时区。 */
    private static final Map<String, String> CITIES = new LinkedHashMap<>();

    static {
        CITIES.put("北京", "Asia/Shanghai");
        CITIES.put("上海", "Asia/Shanghai");
        CITIES.put("香港", "Asia/Hong_Kong");
        CITIES.put("东京", "Asia/Tokyo");
        CITIES.put("首尔", "Asia/Seoul");
        CITIES.put("新加坡", "Asia/Singapore");
        CITIES.put("伦敦", "Europe/London");
        CITIES.put("巴黎", "Europe/Paris");
        CITIES.put("柏林", "Europe/Berlin");
        CITIES.put("纽约", "America/New_York");
        CITIES.put("洛杉矶", "America/Los_Angeles");
        CITIES.put("悉尼", "Australia/Sydney");
    }

    private static final List<String> CONDITIONS = List.of("晴", "多云", "小雨", "阴", "雷阵雨");

    /**
     * 聚合查询：一次返回该城市的时区与模拟天气。
     * <p>
     * 返回 {@code Map} 会被序列化为 JSON 交给模型，因此模型能直接读到结构化的数值字段，
     * 便于它把这些值传给 {@code calculate} 做进一步计算。
     */
    @AgentTool("查询一个城市的综合信息，包括当前时间、时区与天气。适合需要跨城市对比的场景")
    public Map<String, Object> getCityInfo(
            @ToolParam("城市名称，例如：北京、东京、伦敦") String city) {

        if (city == null || city.isBlank()) {
            return Map.of("error", "必须提供城市名称", "supported", CITIES.keySet());
        }

        String name = city.trim();
        String zoneId = CITIES.get(name);
        if (zoneId == null) {
            return Map.of("error", "未知城市：" + name, "supported", CITIES.keySet());
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zoneId));
        int seed = Math.abs(name.hashCode());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", name);
        result.put("zoneId", zoneId);
        result.put("localTime", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("utcOffsetHours", now.getOffset().getTotalSeconds() / 3600.0);
        result.put("condition", CONDITIONS.get(seed % CONDITIONS.size()));
        // 温度保留一位小数，便于模型直接拿去与其它城市做减法
        result.put("temperatureCelsius", Math.round((10 + seed % 200 / 10.0) * 10) / 10.0);
        return result;
    }

    /**
     * 批量查询，减少模型为对比多个城市而发起的调用次数。
     */
    @AgentTool("一次查询多个城市的综合信息，比逐个查询更高效")
    public Map<String, Object> getCityInfoBatch(
            @ToolParam("城市名称列表，例如 [\"北京\", \"东京\"]") List<String> cities) {

        Map<String, Object> result = new LinkedHashMap<>();
        if (cities == null || cities.isEmpty()) {
            result.put("error", "城市列表不能为空");
            return result;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        for (String city : cities) {
            details.put(city, getCityInfo(city));
        }
        result.put("count", cities.size());
        result.put("cities", details);
        return result;
    }

    /**
     * 列出支持的城市，配合模型的参数修正（当查询失败时提示可用值）。
     */
    @AgentTool("列出所有支持查询的城市名称")
    public String listSupportedCities() {
        return CITIES.keySet().stream().collect(java.util.stream.Collectors.joining("、", "支持的城市：", ""));
    }
}
