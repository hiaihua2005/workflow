package com.workflow.engine.logicflow.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflow.common.exception.BusinessException;
import com.workflow.engine.logic.parse.VariableResolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * CONDITION 节点条件求值器（纯静态，无状态）。
 *
 * <p>求值入口 {@link #evaluate(JsonNode, Map, VariableResolver)}：config 支持
 * <ul>
 *   <li>多条件形态：{@code conditions:[{variable, operator, value}...]} +
 *       {@code logic: ALL|ANY}（缺省 ALL）——逐条求值后按逻辑组合；</li>
 *   <li>legacy 单条件形态：{@code {variable, operator, value}}（两者并存时以 conditions 为准）。</li>
 * </ul>
 *
 * <p>单条语义：
 * <ul>
 *   <li>变量值取 {@code vars.get(variable)}；</li>
 *   <li>value 先经 {@link VariableResolver#resolve} 展开 {@code {{var}}} 占位符；</li>
 *   <li>GT/LT/GTE/LTE：双方均可解析为数值则数值比较，否则字符串 compareTo；</li>
 *   <li>EQ/NE：数值可解析优先按数值（"100" 与 "100.0" 相等），否则字符串；</li>
 *   <li>EMPTY/NOT_EMPTY：判 null 或空白串（不含 {{}} 展开，直接看变量原始值）；</li>
 *   <li>CONTAINS/NOT_CONTAINS：字符串包含（String.valueOf 左值，{{var}} 展开后的右值）；</li>
 *   <li>STARTS_WITH/ENDS_WITH：字符串前/后缀匹配；</li>
 *   <li>IN/NOT_IN：value 为逗号分隔字面量列表（元素支持 {{var}} 展开后按 EQ 语义比对）；</li>
 *   <li>REGEX：value 为正则，对左值字符串做 full-match（非法正则抛 BusinessException）；</li>
 *   <li>operator 大小写不敏感；未知 operator 抛 {@link BusinessException}。</li>
 * </ul>
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /** 支持的全部算子（校验器/前端共用同一集合语义）。 */
    public static final Set<String> SUPPORTED_OPERATORS = Set.of(
            "EQ", "NE", "GT", "LT", "GTE", "LTE", "EMPTY", "NOT_EMPTY",
            "CONTAINS", "NOT_CONTAINS", "STARTS_WITH", "ENDS_WITH", "IN", "NOT_IN", "REGEX");

    /** 多条件组合逻辑：ALL=与（缺省），ANY=或。 */
    public static boolean evaluate(JsonNode config, Map<String, Object> vars, VariableResolver resolver) {
        if (config == null || config.isNull()) {
            throw new BusinessException("CONDITION_CONFIG_REQUIRED: config is missing");
        }
        JsonNode conditions = config.get("conditions");
        if (conditions != null && conditions.isArray() && !conditions.isEmpty()) {
            String logic = config.hasNonNull("logic") ? config.get("logic").asText().trim().toUpperCase(Locale.ROOT) : "ALL";
            if (!"ALL".equals(logic) && !"ANY".equals(logic)) {
                throw new BusinessException("UNKNOWN_CONDITION_LOGIC: " + logic);
            }
            List<Boolean> results = new ArrayList<>();
            for (int i = 0; i < conditions.size(); i++) {
                JsonNode item = conditions.get(i);
                if (item == null || item.isNull() || !item.isObject()) {
                    throw new BusinessException("CONDITION_ITEM_INVALID: conditions[" + i + "] 须为对象");
                }
                results.add(evaluateCore(
                        textOf(item, "variable"),
                        textOf(item, "operator"),
                        textOf(item, "value"),
                        vars, resolver, "conditions[" + i + "]"));
            }
            return "ANY".equals(logic)
                    ? results.stream().anyMatch(Boolean::booleanValue)
                    : results.stream().allMatch(Boolean::booleanValue);
        }
        // legacy 单条件形态
        return evaluateCore(
                textOf(config, "variable"),
                textOf(config, "operator"),
                textOf(config, "value"),
                vars, resolver, "config");
    }

    /** legacy 单条件入口（兼容既有调用方/测试）：语义同单条 condition。 */
    public static boolean evaluate(String variable, String operator, String value,
                                   Map<String, Object> vars, VariableResolver resolver) {
        return evaluateCore(variable, operator, value, vars, resolver, "condition");
    }

    private static boolean evaluateCore(String variable, String operator, String value,
                                        Map<String, Object> vars, VariableResolver resolver, String label) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException("CONDITION_OPERATOR_REQUIRED: " + label + " operator is blank");
        }
        if (variable == null || variable.isBlank()) {
            throw new BusinessException("CONDITION_VARIABLE_REQUIRED: " + label + " variable is blank");
        }
        String op = operator.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_OPERATORS.contains(op)) {
            throw new BusinessException("UNKNOWN_CONDITION_OPERATOR: " + operator);
        }
        Object left = vars != null ? vars.get(variable) : null;

        switch (op) {
            case "EMPTY", "NOT_EMPTY" -> {
                boolean empty = left == null || String.valueOf(left).isBlank();
                return "EMPTY".equals(op) == empty;
            }
            case "EQ", "NE" -> {
                boolean equals = compare(left, resolveValue(value, vars, resolver)) == 0;
                return "EQ".equals(op) == equals;
            }
            case "GT", "LT", "GTE", "LTE" -> {
                int cmp = compare(left, resolveValue(value, vars, resolver));
                return switch (op) {
                    case "GT" -> cmp > 0;
                    case "LT" -> cmp < 0;
                    case "GTE" -> cmp >= 0;
                    default -> cmp <= 0;
                };
            }
            case "CONTAINS", "NOT_CONTAINS" -> {
                boolean contains = textOf(left).contains(resolveText(value, vars, resolver));
                return "CONTAINS".equals(op) == contains;
            }
            case "STARTS_WITH", "ENDS_WITH" -> {
                String leftText = textOf(left);
                String right = resolveText(value, vars, resolver);
                boolean hit = "STARTS_WITH".equals(op) ? leftText.startsWith(right) : leftText.endsWith(right);
                return hit;
            }
            case "IN", "NOT_IN" -> {
                // value 为逗号分隔列表：逐元素 {{var}} 展开后按 EQ 语义比对，任一命中即 in
                boolean in = false;
                if (value != null) {
                    for (String raw : value.split(",")) {
                        if (compare(left, resolveValue(raw.trim(), vars, resolver)) == 0) {
                            in = true;
                            break;
                        }
                    }
                }
                return "IN".equals(op) == in;
            }
            case "REGEX" -> {
                String regex = resolveText(value, vars, resolver);
                Pattern pattern;
                try {
                    pattern = Pattern.compile(regex == null ? "" : regex);
                } catch (PatternSyntaxException e) {
                    throw new BusinessException("CONDITION_REGEX_INVALID: " + e.getMessage());
                }
                return pattern.matcher(textOf(left)).matches();
            }
            default -> throw new BusinessException("UNKNOWN_CONDITION_OPERATOR: " + operator);
        }
    }

    /** value 支持 {{var}} 占位符（先展开再比较）；null 视为 null 字面量。 */
    private static String resolveValue(String value, Map<String, Object> vars, VariableResolver resolver) {
        if (value == null) {
            return null;
        }
        return resolver != null ? resolver.resolve(value, vars) : value;
    }

    /** 字符串类算子的右值：{{var}} 展开后取字符串形态。 */
    private static String resolveText(String value, Map<String, Object> vars, VariableResolver resolver) {
        String resolved = resolveValue(value, vars, resolver);
        return resolved == null ? "" : resolved;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 比较规则：双方均可解析为数值 → 数值比较；否则字符串比较（null 归一为空串）。
     *
     * @return 负数/0/正数，语义同 {@link Comparable#compareTo}
     */
    private static int compare(Object left, String right) {
        String leftText = left == null ? "" : String.valueOf(left);
        String rightText = right == null ? "" : right;

        BigDecimal leftNumber = tryParse(leftText);
        BigDecimal rightNumber = tryParse(rightText);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        return leftText.compareTo(rightText);
    }

    /** 可解析数值（整数/小数/科学计数）返回 BigDecimal，否则 null。 */
    private static BigDecimal tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
