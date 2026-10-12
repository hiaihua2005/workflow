package com.workflow.engine.logicflow.engine;

import com.workflow.engine.form.column.ColumnInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * DATA_UPDATE SET 值表达式翻译器（{@code =} 前缀表达式 → 单条 UPDATE 内 SQL 表达式）。
 *
 * <p>DSL 形态：{@code value} 以 {@code =} 开头（如 {@code "= qty * {{formData.rate}} + 10"}）时，
 * 引擎剥掉 {@code =} 后交由本类翻译；裸列名引用目标表该行当前值，{@code {{path}}} 与
 * 数字/字符串字面量一律转为 {@code ?} 参数绑定（SQL 文本绝不内联字面量，防注入底线）。
 *
 * <p>词法：{@code {{path}}}（path 语法与引擎 {@code DATA_VALUE_PATH} 一致：{@code \w+} 段点分隔、
 * 可带 {@code [n]} 列表索引）、数字、单引号字符串（内部不允许单引号）、标识符、
 * 运算符 {@code + - * / % ( ) ,}。语法：标准四则优先级的递归下降。
 *
 * <p>语义：
 * <ul>
 *   <li>标识符后随 {@code (} → 函数名，须在白名单（大小写不敏感）且参数个数匹配；</li>
 *   <li>其余标识符 → 列引用，{@code columns} 非 null 时必须命中（大小写不敏感），
 *       SQL 输出真实列名（{@link ColumnInfo#getKey()}）；</li>
 *   <li>生成 SQL 文本仅包含：白名单列名、白名单函数名、固定符号与 {@code ?}。</li>
 * </ul>
 *
 * <p>两种运行形态：
 * <ul>
 *   <li>运行期（引擎）：{@code columns} 传目标表列元数据、{@code resolver} 传
 *       {@code path -> resolvePath(path, vars)}；</li>
 *   <li>发布校验（Validator）：{@code columns} 与 {@code resolver} 均传 null —— 只做
 *       语法/函数白名单/path 格式校验，列存在性留给运行期（发布时表可能尚未发布）。</li>
 * </ul>
 *
 * <p>注意：{@code ==} 前缀转义（字面量 {@code =} 开头值）由引擎分支处理，不进入本类。
 */
public final class UpdateExprTranslator {

    /** 表达式最大长度（防超长配置；引擎与发布校验共用）。 */
    public static final int MAX_EXPR_LENGTH = 500;

    /** 翻译结果：SQL 片段（仅白名单标识符/符号/?) + 按序参数（不可变）。 */
    public record Result(String sql, List<Object> params) {
    }

    private UpdateExprTranslator() {
    }

    /** 函数白名单：大写名 → [minArgs, maxArgs]（maxArgs null = 不限）。 */
    private static final Map<String, int[]> FUNCTIONS = createFunctions();

    private static Map<String, int[]> createFunctions() {
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put("ROUND", new int[]{1, 2});
        m.put("ABS", new int[]{1, 1});
        m.put("FLOOR", new int[]{1, 1});
        m.put("CEIL", new int[]{1, 1});
        m.put("IFNULL", new int[]{2, 2});
        m.put("COALESCE", new int[]{1, -1});
        m.put("GREATEST", new int[]{1, -1});
        m.put("LEAST", new int[]{1, -1});
        return Collections.unmodifiableMap(m);
    }

    private static final String FUNC_HINT = String.join("/", FUNCTIONS.keySet());

    // ------------------------------------------------------------------
    // 词法
    // ------------------------------------------------------------------

    private enum TokType { NUMBER, STRING, IDENT, PLACEHOLDER, SYMBOL }

    private record Token(TokType type, String text, int pos) {
    }

    private static final java.util.regex.Pattern PATH_RE =
            java.util.regex.Pattern.compile("\\{\\{\\s*(\\w+(?:\\[\\d+\\])?(?:\\.\\w+(?:\\[\\d+\\])?)*)\\s*}}");
    private static final java.util.regex.Pattern NUM_RE = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?");
    private static final java.util.regex.Pattern IDENT_RE = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 分词：非法字符/未闭合字符串/非法占位符均抛 IllegalArgumentException（消息含位置）。 */
    private static List<Token> tokenize(String expr) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '{') {
                java.util.regex.Matcher m = PATH_RE.matcher(expr).region(i, n);
                if (!m.lookingAt()) {
                    throw err(expr, i, "占位符须为 {{合法变量路径}}（如 {{formData.qty}}、{{dq_1.rows[0].sku}}）");
                }
                out.add(new Token(TokType.PLACEHOLDER, m.group(1), i));
                i = m.end();
                continue;
            }
            if (c == '\'') {
                int end = expr.indexOf('\'', i + 1);
                if (end < 0) {
                    throw err(expr, i, "字符串字面量未闭合");
                }
                out.add(new Token(TokType.STRING, expr.substring(i + 1, end), i));
                i = end + 1;
                continue;
            }
            if (Character.isDigit(c)) {
                java.util.regex.Matcher m = NUM_RE.matcher(expr).region(i, n);
                if (!m.lookingAt()) {
                    throw err(expr, i, "数字字面量非法");
                }
                out.add(new Token(TokType.NUMBER, m.group(), i));
                i = m.end();
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                java.util.regex.Matcher m = IDENT_RE.matcher(expr).region(i, n);
                if (!m.lookingAt()) {
                    throw err(expr, i, "标识符非法");
                }
                out.add(new Token(TokType.IDENT, m.group(), i));
                i = m.end();
                continue;
            }
            if ("+-*/%(),".indexOf(c) >= 0) {
                out.add(new Token(TokType.SYMBOL, String.valueOf(c), i));
                i++;
                continue;
            }
            throw err(expr, i, "存在非法字符 '" + c + "'（可用: 数字/单引号字符串/列名/{{变量}}/+ - * / % ( ) ,）");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 语法（递归下降）+ 语义
    // ------------------------------------------------------------------

    /**
     * 翻译表达式为 SQL 片段 + 按序参数。
     *
     * @param expr     已剥 {@code =} 前缀的表达式
     * @param columns  目标表列元数据（小写列名 → 信息）；null = 发布校验模式（跳过列白名单）
     * @param resolver 占位符取值 {@code path -> value}；null = 校验模式（不取值）
     */
    public static Result translate(String expr, Map<String, ColumnInfo> columns, Function<String, Object> resolver) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("DATA_UPDATE SET 表达式为空");
        }
        if (expr.length() > MAX_EXPR_LENGTH) {
            throw new IllegalArgumentException(
                    "DATA_UPDATE SET 表达式超长(>" + MAX_EXPR_LENGTH + " 字符): " + expr.length());
        }
        List<Token> toks = tokenize(expr);
        Parser p = new Parser(expr, toks, columns, resolver);
        String sql = p.parseExpr();
        if (!p.atEnd()) {
            Token t = p.peek();
            throw err(expr, t.pos(), "表达式末尾存在多余内容: " + describe(t));
        }
        return new Result(sql, Collections.unmodifiableList(new ArrayList<>(p.params)));
    }

    private static final class Parser {
        private final String expr;
        private final List<Token> toks;
        private final Map<String, ColumnInfo> columns;
        private final Function<String, Object> resolver;
        private final List<Object> params = new ArrayList<>();
        private int idx = 0;

        Parser(String expr, List<Token> toks, Map<String, ColumnInfo> columns, Function<String, Object> resolver) {
            this.expr = expr;
            this.toks = toks;
            this.columns = columns;
            this.resolver = resolver;
        }

        boolean atEnd() {
            return idx >= toks.size();
        }

        Token peek() {
            return toks.get(idx);
        }

        boolean peekSymbol(String s) {
            return !atEnd() && peek().type() == TokType.SYMBOL && peek().text().equals(s);
        }

        Token expectSymbol(String s, String hint) {
            if (!peekSymbol(s)) {
                throw err(expr, atEnd() ? expr.length() : peek().pos(), hint);
            }
            return toks.get(idx++);
        }

        /** expr := term (('+'|'-') term)* */
        String parseExpr() {
            StringBuilder sb = new StringBuilder(parseTerm());
            while (peekSymbol("+") || peekSymbol("-")) {
                String op = toks.get(idx++).text();
                sb.append(' ').append(op).append(' ').append(parseTerm());
            }
            return sb.toString();
        }

        /** term := factor (('*'|'/'|'%') factor)* */
        String parseTerm() {
            StringBuilder sb = new StringBuilder(parseFactor());
            while (peekSymbol("*") || peekSymbol("/") || peekSymbol("%")) {
                String op = toks.get(idx++).text();
                sb.append(' ').append(op).append(' ').append(parseFactor());
            }
            return sb.toString();
        }

        /** factor := ('-'|'+') factor | primary */
        String parseFactor() {
            if (peekSymbol("-") || peekSymbol("+")) {
                String op = toks.get(idx++).text();
                return op + parseFactor();
            }
            return parsePrimary();
        }

        /** primary := number | string | placeholder | func(...) | column | '(' expr ')' */
        String parsePrimary() {
            if (atEnd()) {
                throw err(expr, expr.length(), "表达式意外结束（缺少数字/字符串/列名/{{变量}}或函数）");
            }
            Token t = toks.get(idx);
            switch (t.type()) {
                case NUMBER -> {
                    idx++;
                    return emitParam(new java.math.BigDecimal(t.text()));
                }
                case STRING -> {
                    idx++;
                    return emitParam(t.text());
                }
                case PLACEHOLDER -> {
                    idx++;
                    return emitParam(resolver != null ? resolver.apply(t.text()) : null);
                }
                case IDENT -> {
                    // 函数调用（后随 "("）或列引用
                    if (idx + 1 < toks.size()
                            && toks.get(idx + 1).type() == TokType.SYMBOL
                            && toks.get(idx + 1).text().equals("(")) {
                        return parseCall();
                    }
                    return parseColumn();
                }
                case SYMBOL -> {
                    if (t.text().equals("(")) {
                        idx++;
                        String inner = parseExpr();
                        expectSymbol(")", "第 " + (t.pos() + 1) + " 个字符处的括号未闭合");
                        return "(" + inner + ")";
                    }
                    throw err(expr, t.pos(), "此处需要数字/字符串/列名/{{变量}}/函数或 (，实际为 '" + t.text() + "'");
                }
                default -> throw err(expr, t.pos(), "此处需要数字/字符串/列名/{{变量}}/函数或 (");
            }
        }

        /** 函数调用：白名单 + 参数个数校验，SQL 输出大写函数名。 */
        private String parseCall() {
            Token fn = toks.get(idx++);
            String name = fn.text().toUpperCase(Locale.ROOT);
            int[] arity = FUNCTIONS.get(name);
            if (arity == null) {
                throw err(expr, fn.pos(), "函数 '" + fn.text() + "' 不受支持（可用: " + FUNC_HINT + "）");
            }
            expectSymbol("(", "函数 '" + fn.text() + "' 后缺少 (");
            List<String> args = new ArrayList<>();
            if (!peekSymbol(")")) {
                args.add(parseExpr());
                while (peekSymbol(",")) {
                    idx++;
                    args.add(parseExpr());
                }
            }
            expectSymbol(")", "函数 '" + fn.text() + "(...)' 括号未闭合");
            if (args.size() < arity[0] || (arity[1] >= 0 && args.size() > arity[1])) {
                String want = arity[1] < 0 ? "至少 " + arity[0] + " 个"
                        : arity[0] == arity[1] ? arity[0] + " 个"
                        : arity[0] + "-" + arity[1] + " 个";
                throw err(expr, fn.pos(), "函数 '" + name + "' 需要 " + want + "参数，实际 " + args.size() + " 个");
            }
            return name + "(" + String.join(", ", args) + ")";
        }

        /** 列引用：columns 非 null 时白名单校验，SQL 输出真实列名。 */
        private String parseColumn() {
            Token t = toks.get(idx++);
            if (columns == null) {
                return t.text(); // 发布校验模式：仅语法
            }
            ColumnInfo info = columns.get(t.text().toLowerCase(Locale.ROOT));
            if (info == null) {
                throw err(expr, t.pos(), "列名 '" + t.text() + "' 不在目标表列中（列名须为 UPDATE 目标表已有字段）");
            }
            return info.getKey();
        }

        private String emitParam(Object value) {
            params.add(value);
            return "?";
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static IllegalArgumentException err(String expr, int pos, String msg) {
        return new IllegalArgumentException("DATA_UPDATE SET 表达式非法: 第 " + (pos + 1) + " 个字符处" + msg
                + "（表达式: " + expr + "）");
    }

    private static String describe(Token t) {
        return switch (t.type()) {
            case STRING -> "'" + t.text() + "'";
            case PLACEHOLDER -> "{{" + t.text() + "}}";
            default -> t.text();
        };
    }
}
