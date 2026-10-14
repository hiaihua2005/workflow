package com.workflow.engine.logicflow.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.common.exception.BusinessException;
import com.workflow.engine.logic.parse.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionEvaluatorTest {

    private final VariableResolver resolver = new VariableResolver();

    /** JSON config → JsonNode（测试辅助）。 */
    private static JsonNode config(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // EQ / NE：数值可解析优先，否则字符串
    // ------------------------------------------------------------------

    @Test
    void eq_numericPriorityWhenBothParsable() {
        assertThat(ConditionEvaluator.evaluate("amount", "EQ", "100", Map.of("amount", 100), resolver)).isTrue();
        // "100" 与 "100.0" 数值相等
        assertThat(ConditionEvaluator.evaluate("amount", "EQ", "100.0", Map.of("amount", 100), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("amount", "EQ", "99", Map.of("amount", 100), resolver)).isFalse();
    }

    @Test
    void eq_stringFallbackWhenNotNumeric() {
        assertThat(ConditionEvaluator.evaluate("status", "EQ", "PASS", Map.of("status", "PASS"), resolver)).isTrue();
        // 字符串比较大小写敏感
        assertThat(ConditionEvaluator.evaluate("status", "EQ", "pass", Map.of("status", "PASS"), resolver)).isFalse();
    }

    @Test
    void ne_isNegationOfEq() {
        assertThat(ConditionEvaluator.evaluate("status", "NE", "PASS", Map.of("status", "FAIL"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("status", "NE", "PASS", Map.of("status", "PASS"), resolver)).isFalse();
        // 数值形态不相等
        assertThat(ConditionEvaluator.evaluate("amount", "NE", "100", Map.of("amount", 99), resolver)).isTrue();
    }

    // ------------------------------------------------------------------
    // GT / LT / GTE / LTE
    // ------------------------------------------------------------------

    @Test
    void numericCompare() {
        assertThat(ConditionEvaluator.evaluate("amount", "GT", "100", Map.of("amount", 150), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("amount", "GT", "100", Map.of("amount", 100), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("amount", "GTE", "100", Map.of("amount", 100), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("amount", "LT", "100", Map.of("amount", 99.5), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("amount", "LTE", "100", Map.of("amount", 100.0), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("amount", "LTE", "100", Map.of("amount", 101), resolver)).isFalse();
    }

    @Test
    void stringCompareWhenEitherSideNotNumeric() {
        assertThat(ConditionEvaluator.evaluate("name", "GT", "aaa", Map.of("name", "bbb"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("name", "LT", "zzz", Map.of("name", "bbb"), resolver)).isTrue();
        // 一侧数值一侧文本 → 退化为字符串比较（"10" < "abc"）
        assertThat(ConditionEvaluator.evaluate("v", "GT", "abc", Map.of("v", 10), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("v", "LT", "abc", Map.of("v", 10), resolver)).isTrue();
    }

    @Test
    void decimalPrecision() {
        assertThat(ConditionEvaluator.evaluate("price", "GTE", "10.50", Map.of("price", 10.5), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("price", "GT", "10.49", Map.of("price", 10.5), resolver)).isTrue();
    }

    // ------------------------------------------------------------------
    // value 支持 {{var}} 占位符
    // ------------------------------------------------------------------

    @Test
    void valuePlaceholderResolvedBeforeCompare() {
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("env", "prod");
        vars.put("expected", "prod");
        assertThat(ConditionEvaluator.evaluate("env", "EQ", "{{expected}}", vars, resolver)).isTrue();
        vars.put("expected", "test");
        assertThat(ConditionEvaluator.evaluate("env", "EQ", "{{expected}}", vars, resolver)).isFalse();
    }

    @Test
    void missingPlaceholderResolvesToEmptyString() {
        assertThat(ConditionEvaluator.evaluate("v", "EQ", "{{missing}}", Map.of("v", "x"), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("v", "NE", "{{missing}}", Map.of("v", "x"), resolver)).isTrue();
    }

    // ------------------------------------------------------------------
    // EMPTY / NOT_EMPTY：判 null 或空白串
    // ------------------------------------------------------------------

    @Test
    void emptyAndNotEmpty() {
        assertThat(ConditionEvaluator.evaluate("ghost", "EMPTY", null, Map.of(), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("blank", "EMPTY", null, Map.of("blank", "  "), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("blank", "EMPTY", null, Map.of("blank", ""), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("v", "EMPTY", null, Map.of("v", "x"), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("ghost", "NOT_EMPTY", null, Map.of(), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("v", "NOT_EMPTY", null, Map.of("v", "x"), resolver)).isTrue();
    }

    // ------------------------------------------------------------------
    // 缺失变量 / operator 大小写 / 未知 operator
    // ------------------------------------------------------------------

    @Test
    void missingVariableTreatedAsEmptyStringForCompare() {
        assertThat(ConditionEvaluator.evaluate("ghost", "EQ", "", Map.of(), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("ghost", "EQ", "x", Map.of(), resolver)).isFalse();
    }

    @Test
    void operatorCaseInsensitive() {
        assertThat(ConditionEvaluator.evaluate("amount", "eq", "100", Map.of("amount", 100), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("amount", "Gt", "100", Map.of("amount", 150), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("ghost", "not_empty", null, Map.of(), resolver)).isFalse();
    }

    @Test
    void unknownOperatorThrowsBusinessException() {
        assertThatThrownBy(() -> ConditionEvaluator.evaluate("v", "LIKE", "x", Map.of("v", "x"), resolver))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UNKNOWN_CONDITION_OPERATOR")
                .hasMessageContaining("LIKE");
        assertThatThrownBy(() -> ConditionEvaluator.evaluate("v", null, "x", Map.of("v", "x"), resolver))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void booleanVariableComparesAsString() {
        assertThat(ConditionEvaluator.evaluate("flag", "EQ", "true", Map.of("flag", true), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("flag", "EQ", "false", Map.of("flag", true), resolver)).isFalse();
    }

    // ------------------------------------------------------------------
    // 扩展算子：CONTAINS / NOT_CONTAINS / STARTS_WITH / ENDS_WITH / IN / NOT_IN / REGEX
    // ------------------------------------------------------------------

    @Test
    void containsOperators() {
        assertThat(ConditionEvaluator.evaluate("memo", "CONTAINS", "紧急", Map.of("memo", "非常紧急事项"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("memo", "CONTAINS", "普通", Map.of("memo", "非常紧急事项"), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("memo", "NOT_CONTAINS", "普通", Map.of("memo", "非常紧急事项"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("memo", "NOT_CONTAINS", "紧急", Map.of("memo", "非常紧急事项"), resolver)).isFalse();
        // 数值左值按字符串形态包含
        assertThat(ConditionEvaluator.evaluate("code", "CONTAINS", "10", Map.of("code", 1102), resolver)).isTrue();
    }

    @Test
    void startsEndsWithOperators() {
        assertThat(ConditionEvaluator.evaluate("name", "STARTS_WITH", "wf_", Map.of("name", "wf_order"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("name", "STARTS_WITH", "order", Map.of("name", "wf_order"), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("name", "ENDS_WITH", "order", Map.of("name", "wf_order"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("name", "ENDS_WITH", "wf_", Map.of("name", "wf_order"), resolver)).isFalse();
        // 右值支持 {{var}} 展开
        assertThat(ConditionEvaluator.evaluate("name", "STARTS_WITH", "{{prefix}}", Map.of("name", "wf_order", "prefix", "wf_"), resolver)).isTrue();
    }

    @Test
    void inAndNotInOperators() {
        assertThat(ConditionEvaluator.evaluate("status", "IN", "PASS,SUCCESS,OK", Map.of("status", "OK"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("status", "IN", "PASS,SUCCESS", Map.of("status", "FAIL"), resolver)).isFalse();
        assertThat(ConditionEvaluator.evaluate("status", "NOT_IN", "PASS,SUCCESS", Map.of("status", "FAIL"), resolver)).isTrue();
        // 元素支持 {{var}} 展开 + 数值 EQ 语义（"100" 与 "100.0" 相等）
        assertThat(ConditionEvaluator.evaluate("level", "IN", "{{high}},{{mid}}", Map.of("level", 100, "high", "100", "mid", "50"), resolver)).isTrue();
    }

    @Test
    void regexOperator() {
        assertThat(ConditionEvaluator.evaluate("phone", "REGEX", "1\\d{10}", Map.of("phone", "13800138000"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate("phone", "REGEX", "1\\d{10}", Map.of("phone", "23800138000"), resolver)).isFalse();
        assertThatThrownBy(() -> ConditionEvaluator.evaluate("v", "REGEX", "([unclosed", Map.of("v", "x"), resolver))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CONDITION_REGEX_INVALID");
    }

    // ------------------------------------------------------------------
    // 多条件形态：conditions[] + logic=ALL|ANY（缺省 ALL）；conditions 优先于 legacy
    // ------------------------------------------------------------------

    @Test
    void multiConditionAllLogic() {
        assertThat(ConditionEvaluator.evaluate(config(
                        "{\"conditions\":[{\"variable\":\"a\",\"operator\":\"EQ\",\"value\":\"1\"},"
                                + "{\"variable\":\"b\",\"operator\":\"NOT_EMPTY\"}],\"logic\":\"ALL\"}"),
                Map.of("a", 1, "b", "x"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate(config(
                        "{\"conditions\":[{\"variable\":\"a\",\"operator\":\"EQ\",\"value\":\"1\"},"
                                + "{\"variable\":\"b\",\"operator\":\"EQ\",\"value\":\"2\"}],\"logic\":\"ALL\"}"),
                Map.of("a", 1, "b", "x"), resolver)).isFalse();
    }

    @Test
    void multiConditionAnyLogic() {
        assertThat(ConditionEvaluator.evaluate(config(
                        "{\"conditions\":[{\"variable\":\"a\",\"operator\":\"GT\",\"value\":\"10\"},"
                                + "{\"variable\":\"b\",\"operator\":\"EQ\",\"value\":\"VIP\"}],\"logic\":\"ANY\"}"),
                Map.of("a", 5, "b", "VIP"), resolver)).isTrue();
        assertThat(ConditionEvaluator.evaluate(config(
                        "{\"conditions\":[{\"variable\":\"a\",\"operator\":\"GT\",\"value\":\"10\"},"
                                + "{\"variable\":\"b\",\"operator\":\"EQ\",\"value\":\"VIP\"}],\"logic\":\"ANY\"}"),
                Map.of("a", 5, "b", "STD"), resolver)).isFalse();
    }

    @Test
    void multiConditionDefaultsToAll() {
        assertThat(ConditionEvaluator.evaluate(config(
                        "{\"conditions\":[{\"variable\":\"a\",\"operator\":\"NOT_EMPTY\"},"
                                + "{\"variable\":\"b\",\"operator\":\"NOT_EMPTY\"}]}"),
                Map.of("a", "x"), resolver)).isFalse();
    }

    @Test
    void multiConditionTakesPrecedenceOverLegacy() {
        // conditions 非空时忽略 legacy variable/operator/value
        assertThat(ConditionEvaluator.evaluate(config(
                        "{\"variable\":\"legacy\",\"operator\":\"EQ\",\"value\":\"no\","
                                + "\"conditions\":[{\"variable\":\"a\",\"operator\":\"EQ\",\"value\":\"yes\"}]}"),
                Map.of("a", "yes", "legacy", "no"), resolver)).isTrue();
    }

    @Test
    void multiConditionInvalidLogicThrows() {
        assertThatThrownBy(() -> ConditionEvaluator.evaluate(config(
                        "{\"conditions\":[{\"variable\":\"a\",\"operator\":\"NOT_EMPTY\"}],\"logic\":\"XOR\"}"),
                Map.of("a", "x"), resolver))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UNKNOWN_CONDITION_LOGIC");
    }

    // ------------------------------------------------------------------
    // 扩展算子非法值
    // ------------------------------------------------------------------

    @Test
    void extendedOperatorCaseInsensitiveAndUnknownStillThrows() {
        assertThat(ConditionEvaluator.evaluate("name", "starts_with", "wf_", Map.of("name", "wf_x"), resolver)).isTrue();
        assertThatThrownBy(() -> ConditionEvaluator.evaluate("v", "SOUNDEX", "x", Map.of("v", "x"), resolver))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UNKNOWN_CONDITION_OPERATOR");
    }
}
