package com.zheng.fishagent.web.tool;

import java.util.List;

/**
 * 极简四则运算求值器（递归下降）。
 * <p>
 * 之所以在本程序内自带一份，而不是复用 Fish-Agent SDK 的
 * {@code com.zheng.fishagent.example.Calculator}：example 包属于示例代码，
 * 不应当作公共 API 依赖——它随时可能在新版本里被调整。
 * 本测试程序刻意只依赖 SDK 的公共 API（{@code @AgentTool} / {@code @ToolParam} 等）。
 * <p>
 * 支持 {@code + - * / % ^}、括号与一元正负号，并容忍全角运算符。
 */
public final class Calculator {

    /** 运算符字符集，供调用方做输入校验与提示。 */
    private static final List<Character> FULL_WIDTH = List.of('×', '÷', '－');

    private Calculator() {
    }

    /**
     * 求值表达式。
     *
     * @throws IllegalArgumentException 表达式为空或含有无法解析的内容
     */
    public static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        String normalized = normalize(expression);
        Parser parser = new Parser(normalized);
        double value = parser.parseExpression();
        parser.skipSpaces();
        if (!parser.reachedEnd()) {
            throw new IllegalArgumentException("表达式存在无法解析的内容，位置 " + parser.position());
        }
        return value;
    }

    /**
     * 结果格式化：整数不显示多余的小数位，便于直接展示给模型。
     */
    public static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String normalize(String expression) {
        String result = expression;
        for (char fullWidth : FULL_WIDTH) {
            result = result.replace(fullWidth, switch (fullWidth) {
                case '×' -> '*';
                case '÷' -> '/';
                default -> '-';
            });
        }
        return result;
    }

    /**
     * 递归下降解析器。文法（由低到高优先级）：
     * <pre>
     * expression := term (('+' | '-') term)*
     * term       := power (('*' | '/' | '%') power)*
     * power      := unary ('^' power)?      // 右结合
     * unary      := ('+' | '-') unary | primary
     * primary    := number | '(' expression ')'
     * </pre>
     */
    private static final class Parser {

        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipSpaces();
                if (consume('+')) {
                    value += parseTerm();
                } else if (consume('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parsePower();
            while (true) {
                skipSpaces();
                if (consume('*')) {
                    value *= parsePower();
                } else if (consume('/')) {
                    double divisor = parsePower();
                    if (divisor == 0) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    value /= divisor;
                } else if (consume('%')) {
                    value %= parsePower();
                } else {
                    return value;
                }
            }
        }

        private double parsePower() {
            double base = parseUnary();
            skipSpaces();
            // 幂运算右结合：2^3^2 == 2^(3^2)
            if (consume('^')) {
                return Math.pow(base, parsePower());
            }
            return base;
        }

        private double parseUnary() {
            skipSpaces();
            if (consume('-')) {
                return -parseUnary();
            }
            if (consume('+')) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            skipSpaces();
            if (consume('(')) {
                double value = parseExpression();
                skipSpaces();
                if (!consume(')')) {
                    throw new IllegalArgumentException("括号不匹配，位置 " + pos);
                }
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipSpaces();
            int start = pos;
            while (pos < text.length()) {
                char current = text.charAt(pos);
                if (Character.isDigit(current) || current == '.' || current == 'e' || current == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("无法解析的数字，位置 " + pos);
            }
            try {
                return Double.parseDouble(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法数字：" + text.substring(start, pos));
            }
        }

        private boolean consume(char expected) {
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private boolean reachedEnd() {
            return pos >= text.length();
        }

        private int position() {
            return pos;
        }
    }
}
