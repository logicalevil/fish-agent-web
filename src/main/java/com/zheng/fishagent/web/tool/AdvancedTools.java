package com.zheng.fishagent.web.tool;

import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 复杂参数类型的工具集，用于验证 SDK 的 Schema 自动生成与运行时类型转换。
 * <p>
 * {@link DemoTools} 只用了 {@code String} / {@code double} 这类简单参数，
 * 本类专门覆盖其余分支：
 * <ul>
 *   <li>{@link #searchProducts} 同时使用<b>枚举</b>、<b>数组</b>与<b>嵌套 record</b>，
 *       Schema 需要递归生成，且模型传值可能出现大小写不符、数组传成字符串等抖动；</li>
 *   <li>{@link #generateText} 验证<b>长返回值</b>是否会被截断或撑爆上下文；</li>
 *   <li>{@link #listCategories} 是<b>无参工具</b>，其 Schema 属性为空，
 *       模型可能传空对象或干脆不传参数。</li>
 * </ul>
 * <p>
 * 这些都是纯计算，不访问网络或磁盘，保证测试与演示都可离线运行。
 */
public class AdvancedTools {

    /** 枚举参数：Schema 中会展开为 {@code enum} 取值列表。 */
    public enum SortBy {
        /** 按价格从低到高。 */
        PRICE_ASC,
        /** 按价格从高到低。 */
        PRICE_DESC,
        /** 按销量从高到低。 */
        SALES,
        /** 按评分从高到低。 */
        RATING
    }

    /** 嵌套 record 参数：Schema 中会展开为带 {@code required} 的子对象。 */
    public record PriceRange(Double min, Double max) {
    }

    /** 演示用的商品数据。 */
    private record Product(String name, String category, double price, int sales, double rating) {
    }

    private static final List<Product> PRODUCTS = List.of(
            new Product("机械键盘", "数码", 399.0, 1200, 4.7),
            new Product("无线鼠标", "数码", 129.0, 3400, 4.5),
            new Product("显示器支架", "数码", 219.0, 860, 4.3),
            new Product("人体工学椅", "家具", 1299.0, 420, 4.8),
            new Product("升降桌", "家具", 1899.0, 310, 4.6),
            new Product("台灯", "家居", 159.0, 2100, 4.4),
            new Product("保温杯", "家居", 89.0, 5600, 4.2),
            new Product("笔记本支架", "数码", 79.0, 4300, 4.1));

    /**
     * 组合验证枚举、数组与嵌套 record 三种参数类型。
     * <p>
     * 描述里刻意强调「不传参数返回全部」与「想知道某商品多少钱时用这个」：
     * 早期描述只写「筛选并排序」，模型把它理解成筛选器而非查询器，
     * 于是遇到「机械键盘多少钱」时不去调用，反而反问用户要单价。
     */
    @AgentTool("查询商品的价格、销量与评分。可按关键词、分类、价格区间筛选并排序；" +
            "不传任何参数时返回全部商品。想知道某个商品（例如机械键盘）多少钱时，用这个工具查询，不要反问用户")
    public String searchProducts(
            @ToolParam(value = "商品名或分类的关键词，为空表示不限", required = false) String keyword,
            @ToolParam(value = "排序方式，取值：PRICE_ASC / PRICE_DESC / SALES / RATING", required = false) SortBy sortBy,
            @ToolParam(value = "要筛选的分类标签，可传多个，例如 [\"数码\", \"家具\"]", required = false) List<String> categories,
            @ToolParam(value = "价格区间，例如 {\"min\": 100, \"max\": 1000}", required = false) PriceRange priceRange) {

        List<Product> matched = new ArrayList<>(PRODUCTS);

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            matched.removeIf(p -> !p.name().contains(kw) && !p.category().contains(kw));
        }
        if (categories != null && !categories.isEmpty()) {
            matched.removeIf(p -> categories.stream().noneMatch(c -> c.equalsIgnoreCase(p.category())));
        }
        if (priceRange != null) {
            if (priceRange.min() != null) {
                matched.removeIf(p -> p.price() < priceRange.min());
            }
            if (priceRange.max() != null) {
                matched.removeIf(p -> p.price() > priceRange.max());
            }
        }

        Comparator<Product> comparator = switch (sortBy == null ? SortBy.RATING : sortBy) {
            case PRICE_ASC -> Comparator.comparingDouble(Product::price);
            case PRICE_DESC -> Comparator.comparingDouble(Product::price).reversed();
            case SALES -> Comparator.comparingInt(Product::sales).reversed();
            case RATING -> Comparator.comparingDouble(Product::rating).reversed();
        };
        matched.sort(comparator);

        if (matched.isEmpty()) {
            return "没有找到符合条件的商品。";
        }
        return matched.stream()
                .map(p -> String.format("%s（%s）¥%.0f，销量 %d，评分 %.1f",
                        p.name(), p.category(), p.price(), p.sales(), p.rating()))
                .collect(Collectors.joining("\n", "共 " + matched.size() + " 件：\n", ""));
    }

    /**
     * 验证长返回值的行为：模型能否正确处理大段文本，是否会被截断。
     */
    @AgentTool("生成指定字符数的示例文本，用于测试长返回值")
    public String generateText(
            @ToolParam("要生成的字符数，1 到 5000 之间") int length) {

        int target = Math.max(1, Math.min(5000, length));
        StringBuilder sb = new StringBuilder(target + 32);
        String unit = "这是用于测试的示例文本内容。";
        while (sb.length() < target) {
            sb.append(unit);
        }
        String result = sb.substring(0, target);
        return "已生成 " + target + " 个字符：\n" + result
                + "\n（本次返回共 " + result.length() + " 字符）";
    }

    /**
     * 无参工具：Schema 的属性为空，用于验证模型在无参数时的调用行为。
     */
    @AgentTool("列出所有可用的商品分类，不需要任何参数")
    public String listCategories() {
        return PRODUCTS.stream()
                .map(Product::category)
                .distinct()
                .collect(Collectors.joining("、", "可用分类：", ""));
    }

    /**
     * 返回结构化的分类统计，供模型做进一步推理（例如与 calculate 组合）。
     */
    @AgentTool("按分类统计商品数量、平均价格与总销量")
    public Map<String, Object> categoryStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalProducts", PRODUCTS.size());
        Map<String, Object> byCategory = new LinkedHashMap<>();
        for (String category : PRODUCTS.stream().map(Product::category).distinct().toList()) {
            List<Product> group = PRODUCTS.stream()
                    .filter(p -> p.category().equals(category))
                    .toList();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("count", group.size());
            stats.put("averagePrice", Math.round(group.stream()
                    .mapToDouble(Product::price).average().orElse(0) * 100) / 100.0);
            stats.put("totalSales", group.stream().mapToInt(Product::sales).sum());
            byCategory.put(category, stats);
        }
        result.put("byCategory", byCategory);
        return result;
    }
}
