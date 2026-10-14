package com.zheng.fishagent.web.tool;

import com.zheng.fishagent.tool.annotation.AgentTool;
import com.zheng.fishagent.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 有副作用的工具集，用于验证两件事：
 * <ol>
 *   <li><b>状态在多次调用间保持</b>——与前面那些纯函数工具不同，
 *       本类的写入会真的改变状态，因此「先添加再查询」这类跨调用场景才能被验证；</li>
 *   <li><b>重复调用检测</b>——SDK 的 {@code maxRepeatedToolCalls} 会在同一工具
 *       以相同参数被反复调用时判定死循环。{@link #addTodo} 的返回里带有写入计数，
 *       可以直观看出模型是否被误导而重复写入。</li>
 * </ol>
 * <p>
 * <b>关于线程安全</b>：SDK 的 {@code ReActAgent} 本身非线程安全，本测试程序用
 * 每会话一把锁串行化，因此这里按单线程使用即可。之所以仍用
 * {@link CopyOnWriteArrayList} 与 {@link AtomicInteger}，是为了避免在演示
 * 「跨会话」场景时出现意外。
 */
public class TodoTools {

    private final List<String> todos = new CopyOnWriteArrayList<>();
    private final AtomicInteger addCount = new AtomicInteger();

    /**
     * 写入型工具：返回里带上总条数与写入次数，便于观察是否发生重复调用。
     */
    @AgentTool("添加一条待办事项")
    public String addTodo(
            @ToolParam("待办内容") String content) {

        if (content == null || content.isBlank()) {
            return "错误：待办内容不能为空";
        }
        String item = content.trim();
        // 幂等保护：内容重复时不重复添加，并明确告知模型
        if (todos.contains(item)) {
            return "该待办已存在，未重复添加。当前共 " + todos.size() + " 条";
        }
        todos.add(item);
        int count = addCount.incrementAndGet();
        return "已添加：「" + item + "」。当前共 " + todos.size()
                + " 条待办（本次会话累计写入 " + count + " 次）";
    }

    @AgentTool("查看当前所有待办事项")
    public String listTodos() {
        if (todos.isEmpty()) {
            return "当前没有待办事项";
        }
        return todos.stream().collect(Collectors.joining("\n",
                "共 " + todos.size() + " 条待办：\n", ""));
    }

    @AgentTool("删除一条待办事项，按内容精确匹配")
    public String removeTodo(
            @ToolParam("要删除的待办内容") String content) {

        if (content == null || content.isBlank()) {
            return "错误：待办内容不能为空";
        }
        boolean removed = todos.remove(content.trim());
        return removed
                ? "已删除：「" + content.trim() + "」，剩余 " + todos.size() + " 条"
                : "未找到该待办：" + content.trim();
    }

    /**
     * 返回结构化统计，便于与 {@code calculate} 等工具组合使用。
     */
    @AgentTool("统计待办事项的数量与累计写入次数")
    public Map<String, Object> todoStats() {
        return Map.of(
                "pending", todos.size(),
                "totalWrites", addCount.get(),
                "items", List.copyOf(todos));
    }
}
