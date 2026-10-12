package com.workflow.engine.logicflow.engine;

import com.workflow.engine.form.column.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DATA_UPDATE SET 值表达式翻译器纯单测：词法/语法/语义（列白名单、{{path}} 参数、函数白名单）、
 * 发布校验模式（columns=null）、错误定位消息。
 */
class UpdateExprTranslatorTest {

    /** 目标表列元数据（小写键 → 真实列名），模拟 columnsOf(table)。 */
    private static Map<String, ColumnInfo> cols(String... names) {
        Map<String, ColumnInfo> m = new LinkedHashMap<>();
        for (String n : names) {
            m.put(n.toLowerCase(), new ColumnInfo(n, "INT", true, false));
        }
        return m;
    }

    /** 占位符取值：qty → 3（Integer 保类型）、缺失路径 → null。 */
    private static final Function<String, Object> RESOLVER = path ->
            "formData.qty".equals(path) ? 3 : null;

    // ------------------------------------------------------------------
    // 基础翻译
    // ------------------------------------------------------------------

    @Test
    void translatesColumnArithmetic() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate("qty * 2 + 1", cols("qty"), null);
        assertThat(r.sql()).isEqualTo("qty * ? + ?");
        assertThat(r.params()).hasSize(2);
        assertThat(r.params().get(0)).isEqualTo(new BigDecimal("2"));
        assertThat(r.params().get(1)).isEqualTo(new BigDecimal("1"));
    }

    @Test
    void normalizesColumnNameCaseToRealKey() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate("QTY + qty", cols("qty"), null);
        assertThat(r.sql()).isEqualTo("qty + qty");
    }

    @Test
    void usesRealColumnKeyFromMetadata() {
        Map<String, ColumnInfo> m = new LinkedHashMap<>();
        m.put("stockqty", new ColumnInfo("stockQty", "INT", true, false));
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate("STOCKQTY * 2", m, null);
        assertThat(r.sql()).isEqualTo("stockQty * ?");
    }

    @Test
    void bindsPlaceholdersAsParamsInOrder() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate(
                "qty * {{formData.qty}} + {{dq_1.rows[0].sku}}", cols("qty"), RESOLVER);
        assertThat(r.sql()).isEqualTo("qty * ? + ?");
        assertThat(r.params()).containsExactly(3, null); // 类型保持（Integer），缺失路径 null
    }

    @Test
    void bindsStringAndNumberLiteralsAsParams() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate(
                "IFNULL(remark, '待审核')", cols("remark"), null);
        assertThat(r.sql()).isEqualTo("IFNULL(remark, ?)");
        assertThat(r.params()).containsExactly("待审核");
    }

    @Test
    void supportsUnaryMinusAndNestedParens() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate(
                "-(qty - 1) * (2 + 3)", cols("qty"), null);
        assertThat(r.sql()).isEqualTo("-(qty - ?) * (? + ?)");
        assertThat(r.params()).hasSize(3);
    }

    @Test
    void supportsPercentModulo() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate("qty % 7", cols("qty"), null);
        assertThat(r.sql()).isEqualTo("qty % ?");
    }

    @Test
    void whitespaceAroundPlaceholderPathIsIgnored() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate(
                "qty * {{ formData.qty }}", cols("qty"), RESOLVER);
        assertThat(r.sql()).isEqualTo("qty * ?");
        assertThat(r.params()).containsExactly(3);
    }

    // ------------------------------------------------------------------
    // 函数白名单
    // ------------------------------------------------------------------

    @Test
    void supportsWhitelistedFunctions() {
        Map<String, ColumnInfo> c = cols("qty", "price");
        assertThat(UpdateExprTranslator.translate("ROUND(price, 2)", c, null).sql())
                .isEqualTo("ROUND(price, ?)"); // price 是列（内联），2 是字面量（参数）
        assertThat(UpdateExprTranslator.translate("abs(qty)", c, null).sql()).isEqualTo("ABS(qty)");
        assertThat(UpdateExprTranslator.translate("CEIL(qty)", c, null).sql()).isEqualTo("CEIL(qty)");
        assertThat(UpdateExprTranslator.translate("COALESCE(qty, 0)", c, null).sql())
                .isEqualTo("COALESCE(qty, ?)");
        assertThat(UpdateExprTranslator.translate("GREATEST(qty, 1, 2)", c, null).sql())
                .isEqualTo("GREATEST(qty, ?, ?)");
        assertThat(UpdateExprTranslator.translate("LEAST(qty, 9)", c, null).sql())
                .isEqualTo("LEAST(qty, ?)");
        assertThat(UpdateExprTranslator.translate("FLOOR(price)", c, null).sql())
                .isEqualTo("FLOOR(price)");
    }

    @Test
    void rejectsUnknownFunction() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("NOW()", cols("qty"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOW")
                .hasMessageContaining("不受支持");
    }

    @Test
    void rejectsWrongArity() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("ROUND()", cols("qty"), null))
                .hasMessageContaining("ROUND")
                .hasMessageContaining("1-2");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("ROUND(qty, 1, 2)", cols("qty"), null))
                .hasMessageContaining("ROUND");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("IFNULL(qty)", cols("qty"), null))
                .hasMessageContaining("IFNULL")
                .hasMessageContaining("2 个参数");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("ABS(qty, 1)", cols("qty"), null))
                .hasMessageContaining("ABS");
    }

    // ------------------------------------------------------------------
    // 列白名单（运行模式）
    // ------------------------------------------------------------------

    @Test
    void rejectsUnknownColumn() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qtyy * 2", cols("qty"), null))
                .hasMessageContaining("qtyy")
                .hasMessageContaining("不在目标表列中");
    }

    // ------------------------------------------------------------------
    // 发布校验模式（columns = null，resolver = null）
    // ------------------------------------------------------------------

    @Test
    void validationModeSkipsColumnWhitelist() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate("any_ident * 2", null, null);
        assertThat(r.sql()).isEqualTo("any_ident * ?");
    }

    @Test
    void validationModeStillChecksFunctionsAndSyntax() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("NOW()", null, null))
                .hasMessageContaining("不受支持");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("(qty * 2", null, null))
                .hasMessageContaining("括号未闭合");
    }

    // ------------------------------------------------------------------
    // 错误用例（含位置定位）
    // ------------------------------------------------------------------

    @Test
    void rejectsIllegalCharacter() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qty * @x", cols("qty"), null))
                .hasMessageContaining("非法字符 '@'")
                .hasMessageContaining("第 7 个字符处");
    }

    @Test
    void rejectsUnclosedParenAndTrailingTokens() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("(qty + 1", cols("qty"), null))
                .hasMessageContaining("括号未闭合");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qty 2", cols("qty"), null))
                .hasMessageContaining("末尾存在多余内容");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("* qty", cols("qty"), null))
                .hasMessageContaining("此处需要");
    }

    @Test
    void rejectsUnclosedString() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("IFNULL(remark, 'abc)", cols("remark"), null))
                .hasMessageContaining("字符串字面量未闭合");
    }

    @Test
    void rejectsMalformedPlaceholder() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qty * {{}}", cols("qty"), null))
                .hasMessageContaining("占位符");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qty * {{formData.}}", cols("qty"), null))
                .hasMessageContaining("占位符");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qty * {{formData.qty", cols("qty"), null))
                .hasMessageContaining("占位符");
    }

    @Test
    void rejectsEmptyAndTooLong() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("   ", cols("qty"), null))
                .hasMessageContaining("表达式为空");
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qty * ".repeat(100) + "1", cols("qty"), null))
                .hasMessageContaining("超长");
    }

    // ------------------------------------------------------------------
    // SQL 文本防注入底线：字面量绝不内联
    // ------------------------------------------------------------------

    @Test
    void sqlTextNeverContainsInlineLiterals() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate(
                "qty * 1.5 + 'abc'", cols("qty"), RESOLVER);
        assertThat(r.sql()).doesNotContain("1.5");
        assertThat(r.sql()).doesNotContain("abc");
        assertThat(r.params()).contains(new BigDecimal("1.5"), "abc");
    }

    @Test
    void rejectsAdjacentStringLiterals() {
        // 设计：字符串无转义，'a''b' 分词为两个相邻串 → 串 'a' 后残留 'b' 报多余内容
        assertThatThrownBy(() -> UpdateExprTranslator.translate("'a''b'", cols("qty"), null))
                .hasMessageContaining("末尾存在多余内容");
    }

    // ------------------------------------------------------------------
    // 组合场景（对应真实配置）
    // ------------------------------------------------------------------

    @Test
    void realWorldExpression() {
        Map<String, ColumnInfo> c = cols("stockQty", "minQty");
        Map<String, Object> vars = new HashMap<>();
        vars.put("formData", Map.of("rate", 2));
        Function<String, Object> resolver = path -> {
            // 模拟引擎 resolvePath：仅支持 formData.rate
            if ("formData.rate".equals(path)) {
                return ((Map<?, ?>) vars.get("formData")).get("rate");
            }
            return null;
        };
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate(
                "stockQty * {{formData.rate}} + 10", c, resolver);
        assertThat(r.sql()).isEqualTo("stockQty * ? + ?");
        assertThat(r.params()).containsExactly(2, new BigDecimal("10"));
    }

    @Test
    void roundSingleArgColumnHasNoParams() {
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate("ROUND(qty)", cols("qty"), null);
        assertThat(r.params()).isEmpty(); // qty 是列引用，无字面量参数
        assertThat(r.sql()).isEqualTo("ROUND(qty)");
    }

    // ------------------------------------------------------------------
    // 多场景（INSERT 值 / DELETE·QUERY 条件值）：场景前缀 + 空白名单拒绝列引用
    // ------------------------------------------------------------------

    @Test
    void scenePrefixReplacesDefaultInError() {
        assertThatThrownBy(() -> UpdateExprTranslator.translate("qty *", Map.of(), null, "DATA_INSERT 值"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("DATA_INSERT 值表达式非法")
                .hasMessageNotContaining("DATA_UPDATE");
    }

    @Test
    void emptyColumnsRejectColumnRefs() {
        // 无行上下文场景（INSERT 值/条件值）：列引用无意义，空白名单须拒绝并给出指引
        assertThatThrownBy(() -> UpdateExprTranslator.translate("stockQty - 1", Map.of(), null, "DATA_INSERT 值"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持列引用")
                .hasMessageContaining("{{变量}}")
                .hasMessageStartingWith("DATA_INSERT 值");
    }

    @Test
    void emptyColumnsAllowsPlaceholdersLiteralsAndFunctions() {
        // 无行上下文但 {{变量}}/字面量/白名单函数可用（引擎 evalExprValue 的 SELECT 求值输入）
        Function<String, Object> resolver = path -> "formData.qty".equals(path) ? 3 : null;
        UpdateExprTranslator.Result r = UpdateExprTranslator.translate(
                "ROUND({{formData.qty}} * 2.5, 1) + 'ab'", Map.of(), resolver, "DATA_INSERT 值");
        assertThat(r.sql()).isEqualTo("ROUND(? * ?, ?) + ?");
        assertThat(r.params()).containsExactly(3, new BigDecimal("2.5"), new BigDecimal("1"), "ab");
    }

    @Test
    void sceneKeepsSyntaxChecksForEmptyColumns() {
        // 空白名单场景语法错误同样拦截（未闭合字符串）
        assertThatThrownBy(() -> UpdateExprTranslator.translate("'abc", Map.of(), null, "DATA_DELETE 条件值"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("DATA_DELETE 条件值表达式非法")
                .hasMessageContaining("未闭合");
    }
}
