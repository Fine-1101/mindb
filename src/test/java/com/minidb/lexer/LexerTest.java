package com.minidb.lexer;

import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lexer 单元测试（单文件）：
 *
 * <p>1) 前半部分：35 个详细断言测试，逐一验证 Token 的 type/text/value/line/column，
 *    覆盖任务要求的全部测试点（Test 1~17 等）；
 * <p>2) 后半部分：34 条演示用例（feedbackReport），把每条用例的 SQL 原文与逐 Token
 *    结果打印到控制台并写入 target/lexer-report.txt（UTF-8），同时做成功/失败断言；
 *    该类也带 main 入口：把每个命令行参数当作一段 SQL 单独切分验证。
 */
public class LexerTest {

    private static final Lexer LEXER = new Lexer();

    // ==================================================================
    // 详细断言测试使用的辅助方法
    // ==================================================================

    private static List<Token> lex(String sql) throws MiniDbException {
        return LEXER.tokenize(sql);
    }

    private static Token tok(List<Token> tokens, int i) {
        return tokens.get(i);
    }

    private static void assertToken(Token t, TokenType type, String text, int line, int col) {
        assertEquals(type, t.type());
        assertEquals(text, t.text());
        assertEquals(line, t.pos().line());
        assertEquals(col, t.pos().col());
    }

    private static void assertPos(Token t, int line, int col) {
        assertEquals(new Position(line, col), t.pos());
    }

    private static void assertPos(MiniDbException e, int line, int col) {
        assertNotNull(e.pos());
        assertEquals(new Position(line, col), e.pos());
    }

    private static void assertLastIsEof(List<Token> tokens, int line, int col) {
        assertToken(tokens.get(tokens.size() - 1), TokenType.EOF, "", line, col);
    }

    // ==================================================================
    // Test 1：基本 SELECT
    // ==================================================================

    @Test
    public void testBasicSelect() throws Exception {
        List<Token> tokens = lex("SELECT * FROM t;");
        assertEquals(6, tokens.size());
        assertToken(tok(tokens, 0), TokenType.KW_SELECT, "SELECT", 1, 1);
        assertToken(tok(tokens, 1), TokenType.STAR, "*", 1, 8);
        assertToken(tok(tokens, 2), TokenType.KW_FROM, "FROM", 1, 10);
        assertToken(tok(tokens, 3), TokenType.IDENT, "t", 1, 15);
        assertToken(tok(tokens, 4), TokenType.SEMI, ";", 1, 16);
        assertToken(tok(tokens, 5), TokenType.EOF, "", 1, 17);
    }

    // ==================================================================
    // Test 2：关键字大小写不敏感（text 保留原始大小写）
    // ==================================================================

    @Test
    public void testKeywordCaseInsensitive() throws Exception {
        String[] variants = {"select * from t;", "SELECT * FROM t;", "SeLeCt * FrOm t;"};
        for (String sql : variants) {
            List<Token> tokens = lex(sql);
            assertEquals(TokenType.KW_SELECT, tok(tokens, 0).type());
            assertEquals(TokenType.STAR, tok(tokens, 1).type());
            assertEquals(TokenType.KW_FROM, tok(tokens, 2).type());
            assertEquals(TokenType.IDENT, tok(tokens, 3).type());
            assertEquals(TokenType.SEMI, tok(tokens, 4).type());
            assertEquals(TokenType.EOF, tok(tokens, 5).type());
        }
        // 原始文本必须原样保留
        assertEquals("SeLeCt", tok(lex("SeLeCt * FrOm t;"), 0).text());
        assertEquals("select", tok(lex("select * from t;"), 0).text());
    }

    // ==================================================================
    // 15 个 KW_* 全部识别 + 关键字优先于标识符
    // ==================================================================

    @Test
    public void testAllFifteenKeywordsRecognized() throws Exception {
        Set<TokenType> seen = EnumSet.noneOf(TokenType.class);
        for (TokenType type : TokenType.values()) {
            if (type.name().startsWith("KW_")) {
                String word = type.name().substring("KW_".length()).toLowerCase();
                // 小写与大写都必须识别为同一个关键字类型
                for (String variant : new String[]{word, word.toUpperCase()}) {
                    List<Token> tokens = lex(variant);
                    assertEquals(2, tokens.size(),
                            "关键字 '" + variant + "' 应为 " + type);
                    assertEquals(type, tok(tokens, 0).type(),
                            "关键字 '" + variant + "' 应为 " + type);
                    assertEquals(TokenType.EOF, tok(tokens, 1).type());
                }
                seen.add(type);
            }
        }
        // 确保 15 个关键字确实被遍历到（防止以后枚举新增漏测）
        assertEquals(15, seen.size());
    }

    @Test
    public void testKeywordBeatsIdentifier() throws Exception {
        // 完整词命中关键字
        List<Token> tokens = lex("select");
        assertEquals(2, tokens.size());
        assertEquals(TokenType.KW_SELECT, tok(tokens, 0).type());
        // 以关键字开头的更长词仍是普通标识符（整词匹配）
        List<Token> longer = lex("selector");
        assertEquals(2, longer.size());
        assertToken(tok(longer, 0), TokenType.IDENT, "selector", 1, 1);
        assertEquals(TokenType.EOF, tok(longer, 1).type());
    }

    // ==================================================================
    // Test 3：INT
    // ==================================================================

    @Test
    public void testIntLiteral() throws Exception {
        List<Token> tokens = lex("123");
        assertEquals(2, tokens.size());
        assertToken(tok(tokens, 0), TokenType.INT_LIT, "123", 1, 1);
        assertEquals(Integer.valueOf(123), tok(tokens, 0).value());
        assertEquals(Integer.valueOf(0), tok(lex("0"), 0).value());
        assertEquals(Integer.valueOf(99999), tok(lex("99999"), 0).value());
        assertLastIsEof(tokens, 1, 4);
    }

    // ==================================================================
    // Test 4：FLOAT
    // ==================================================================

    @Test
    public void testFloatLiteral() throws Exception {
        List<Token> tokens = lex("1.5");
        assertEquals(TokenType.FLOAT_LIT, tok(tokens, 0).type());
        assertEquals("1.5", tok(tokens, 0).text());
        assertEquals(Double.valueOf(1.5), tok(tokens, 0).value());
        assertEquals(Double.valueOf(0.5), tok(lex("0.5"), 0).value());
        assertEquals(Double.valueOf(123.456), tok(lex("123.456"), 0).value());
    }

    // ==================================================================
    // Test 5：字符串转义（'' -> '）
    // ==================================================================

    @Test
    public void testStringEscape() throws Exception {
        List<Token> tokens = lex("'Tom''s book'");
        assertEquals(2, tokens.size());
        assertEquals(TokenType.STRING, tok(tokens, 0).type());
        assertEquals("Tom's book", tok(tokens, 0).value());
        // 连续两个转义引号
        assertEquals("abc'def", tok(lex("'abc''def'"), 0).value());
        // 转义后字符串后仍能正确继续切分
        List<Token> after = lex("'Tom''s' AND x");
        assertEquals("Tom's", tok(after, 0).value());
        assertEquals(TokenType.KW_AND, tok(after, 1).type());
        assertEquals(TokenType.IDENT, tok(after, 2).type());
    }

    // ==================================================================
    // Test 6：中文字符串
    // ==================================================================

    @Test
    public void testChineseString() throws Exception {
        List<Token> tokens = lex("'你好世界'");
        assertEquals(TokenType.STRING, tok(tokens, 0).type());
        assertEquals("你好世界", tok(tokens, 0).value());
        assertEquals("'你好世界'", tok(tokens, 0).text());
    }

    // ==================================================================
    // Test 7：空字符串
    // ==================================================================

    @Test
    public void testEmptyString() throws Exception {
        List<Token> tokens = lex("''");
        assertEquals(TokenType.STRING, tok(tokens, 0).type());
        assertEquals("", tok(tokens, 0).value());
        assertEquals("''", tok(tokens, 0).text());
    }

    // ==================================================================
    // Test 8：单行注释
    // ==================================================================

    @Test
    public void testLineComment() throws Exception {
        List<Token> tokens = lex("SELECT * -- comment\nFROM student;");
        assertEquals(6, tokens.size());
        assertToken(tok(tokens, 0), TokenType.KW_SELECT, "SELECT", 1, 1);
        assertToken(tok(tokens, 1), TokenType.STAR, "*", 1, 8);
        assertToken(tok(tokens, 2), TokenType.KW_FROM, "FROM", 2, 1);
        assertToken(tok(tokens, 3), TokenType.IDENT, "student", 2, 6);
        assertToken(tok(tokens, 4), TokenType.SEMI, ";", 2, 13);
        assertLastIsEof(tokens, 2, 14);
        for (Token t : tokens) {
            assertTrue(!t.text().contains("comment"));
        }
    }

    // ==================================================================
    // Test 9：多行注释后 line/column 正确
    // ==================================================================

    @Test
    public void testBlockCommentAcrossLines() throws Exception {
        List<Token> tokens = lex("SELECT /*\nhello\nworld\n*/\n* FROM student;");
        assertEquals(6, tokens.size());
        assertToken(tok(tokens, 0), TokenType.KW_SELECT, "SELECT", 1, 1);
        assertToken(tok(tokens, 1), TokenType.STAR, "*", 5, 1);
        assertToken(tok(tokens, 2), TokenType.KW_FROM, "FROM", 5, 3);
        assertToken(tok(tokens, 3), TokenType.IDENT, "student", 5, 8);
        assertToken(tok(tokens, 4), TokenType.SEMI, ";", 5, 15);
        assertLastIsEof(tokens, 5, 16);
    }

    /** 同一行内的块注释：注释内容不产生 Token，位置连续推进。 */
    @Test
    public void testInlineBlockComment() throws Exception {
        List<Token> tokens = lex("SELECT/*x*/1");
        assertEquals(3, tokens.size());
        assertToken(tok(tokens, 0), TokenType.KW_SELECT, "SELECT", 1, 1);
        assertToken(tok(tokens, 1), TokenType.INT_LIT, "1", 1, 12);
        assertLastIsEof(tokens, 1, 13);
    }

    // ==================================================================
    // Test 10：双字符运算符（>= <= != ==）优先匹配
    // ==================================================================

    @Test
    public void testTwoCharOperatorsSingleToken() throws Exception {
        Object[][] cases = {
                {">=", TokenType.OP_GE},
                {"<=", TokenType.OP_LE},
                {"!=", TokenType.OP_NE},
                {"==", TokenType.OP_EQEQ},
        };
        for (Object[] c : cases) {
            String op = (String) c[0];
            List<Token> tokens = lex(op);
            assertEquals(2, tokens.size(), "单字符(" + op + ") 应整体作为一个 Token: " + tokens);
            assertEquals(c[1], tok(tokens, 0).type());
            assertEquals(op, tok(tokens, 0).text());
            assertEquals(TokenType.EOF, tok(tokens, 1).type());
        }
    }

    @Test
    public void testTwoCharOperatorsNotSplit() throws Exception {
        List<Token> tokens = lex("age >= 18");
        assertEquals(4, tokens.size());
        assertEquals(TokenType.IDENT, tok(tokens, 0).type());
        assertToken(tok(tokens, 1), TokenType.OP_GE, ">=", 1, 5);
        assertEquals(TokenType.INT_LIT, tok(tokens, 2).type());
        assertEquals(Integer.valueOf(18), tok(tokens, 2).value());
    }

    @Test
    public void testSingleCharOperators() throws Exception {
        Object[][] cases = {
                {"<", TokenType.OP_LT},
                {">", TokenType.OP_GT},
                {"=", TokenType.OP_EQ},
                {"+", TokenType.OP_ADD},
                {"-", TokenType.OP_SUB},
                {"/", TokenType.OP_DIV},
                {"(", TokenType.LPAREN},
                {")", TokenType.RPAREN},
                {",", TokenType.COMMA},
                {";", TokenType.SEMI},
                {".", TokenType.DOT},
                {"*", TokenType.STAR},
        };
        for (Object[] c : cases) {
            String s = (String) c[0];
            List<Token> tokens = lex(s);
            assertEquals(2, tokens.size());
            assertEquals(c[1], tok(tokens, 0).type(), "字符 '" + s + "' 类型错误");
            assertEquals(s, tok(tokens, 0).text());
        }
    }

    // ==================================================================
    // Test 11：空输入
    // ==================================================================

    @Test
    public void testEmptyInputOnlyEof() throws Exception {
        List<Token> tokens = lex("");
        assertEquals(1, tokens.size());
        assertEquals(TokenType.EOF, tok(tokens, 0).type());
        assertPos(tok(tokens, 0), 1, 1);
    }

    // ==================================================================
    // Test 12：纯空白
    // ==================================================================

    @Test
    public void testWhitespaceOnlyEof() throws Exception {
        List<Token> tokens = lex("   \n\t  ");
        assertEquals(1, tokens.size());
        assertEquals(TokenType.EOF, tok(tokens, 0).type());
        assertPos(tok(tokens, 0), 2, 4);
    }

    // ==================================================================
    // Test 13：纯注释
    // ==================================================================

    @Test
    public void testCommentOnlyEof() throws Exception {
        List<Token> tokens = lex("-- hello\n/* world */");
        assertEquals(1, tokens.size());
        assertEquals(TokenType.EOF, tok(tokens, 0).type());
    }

    // ==================================================================
    // Test 14：非法字符
    // ==================================================================

    @Test
    public void testIllegalCharThrows() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("@"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 1);
    }

    @Test
    public void testIllegalCharPositionInMiddle() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("abc@def"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 4);
    }

    /** 单独的 ! 不是任何 TokenType => 非法字符。 */
    @Test
    public void testBareBangIllegal() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("!"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 1);
    }

    // ==================================================================
    // Test 15：未闭合字符串
    // ==================================================================

    @Test
    public void testUnclosedStringThrows() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("'abc"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 1);
    }

    @Test
    public void testUnclosedStringOnSecondLine() {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> lex("SELECT 'a\nbc"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 8);
    }

    /** 未闭合块注释同样属于 LEXER 错误。 */
    @Test
    public void testUnclosedBlockCommentThrows() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("/* hello"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 1);
    }

    // ==================================================================
    // Test 16：非法数字（1.2.3 不能拆成 1.2 / . / 3）
    // ==================================================================

    @Test
    public void testInvalidNumberThrows() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("1.2.3"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 1);
    }

    /** 123abc 不能成为标识符，按非法数字处理。 */
    @Test
    public void testNumberFollowedByLetterThrows() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("123abc"));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 1);
    }

    /** 小数点后缺少数字（1.）按非法数字处理。 */
    @Test
    public void testTrailingDotNumberThrows() {
        MiniDbException e = assertThrows(MiniDbException.class, () -> lex("1."));
        assertEquals(MiniDbException.Phase.LEXER, e.phase());
        assertPos(e, 1, 2);
    }

    // ==================================================================
    // Test 17：超长标识符（>= 1000 字符）
    // ==================================================================

    @Test
    public void testVeryLongIdentifier() throws Exception {
        int n = 1000;
        StringBuilder sb = new StringBuilder(n + 1);
        sb.append('a');
        for (int i = 0; i < n; i++) {
            sb.append('x');
        }
        String id = sb.toString();
        List<Token> tokens = lex(id + ";");
        assertEquals(3, tokens.size());
        assertEquals(TokenType.IDENT, tok(tokens, 0).type());
        assertEquals(id, tok(tokens, 0).text());
        assertEquals(TokenType.SEMI, tok(tokens, 1).type());
        assertEquals(TokenType.EOF, tok(tokens, 2).type());
    }

    // ==================================================================
    // 标识符规则
    // ==================================================================

    @Test
    public void testIdentifiers() throws Exception {
        for (String id : new String[]{"student", "Student", "_student", "student1", "student_name"}) {
            List<Token> tokens = lex(id);
            assertEquals(2, tokens.size());
            assertToken(tok(tokens, 0), TokenType.IDENT, id, 1, 1);
            assertEquals(TokenType.EOF, tok(tokens, 1).type());
        }
    }

    @Test
    public void testUnderscoreStartAndDigitsInside() throws Exception {
        List<Token> tokens = lex("_s1");
        assertEquals(TokenType.IDENT, tok(tokens, 0).type());
        assertEquals("_s1", tok(tokens, 0).text());
    }

    // ==================================================================
    // 位置与文本保留
    // ==================================================================

    /** 两个 Token 相邻无空白时位置仍逐字符正确推进。 */
    @Test
    public void testPositionsWithoutWhitespace() throws Exception {
        List<Token> tokens = lex("id>=18;");
        assertToken(tok(tokens, 0), TokenType.IDENT, "id", 1, 1);
        assertToken(tok(tokens, 1), TokenType.OP_GE, ">=", 1, 3);
        assertToken(tok(tokens, 2), TokenType.INT_LIT, "18", 1, 5);
        assertToken(tok(tokens, 3), TokenType.SEMI, ";", 1, 7);
        assertLastIsEof(tokens, 1, 8);
    }

    /** 字符串内含分号不产生 SEMI Token；字符串后的分号正常切分。 */
    @Test
    public void testSemiInsideString() throws Exception {
        List<Token> tokens = lex("'a;b';");
        assertEquals(3, tokens.size());
        assertEquals(TokenType.STRING, tok(tokens, 0).type());
        assertEquals("a;b", tok(tokens, 0).value());
        assertToken(tok(tokens, 1), TokenType.SEMI, ";", 1, 6);
        assertEquals(TokenType.EOF, tok(tokens, 2).type());
    }

    /** 跨行字符串：value 保留换行，位置推进正确。 */
    @Test
    public void testStringAcrossLinesPosition() throws Exception {
        List<Token> tokens = lex("'ab\ncd'");
        assertEquals(2, tokens.size());
        assertEquals("ab\ncd", tok(tokens, 0).value());
        assertPos(tok(tokens, 0), 1, 1);
        // 第 2 行：c(1) d(2) 结束引号(3) 之后到行尾(4)
        assertLastIsEof(tokens, 2, 4);
    }

    /** CRLF 换行只算一行，列号从 1 重新计数。 */
    @Test
    public void testCrlfLineEnding() throws Exception {
        List<Token> tokens = lex("SELECT *\r\nFROM t;");
        assertToken(tok(tokens, 2), TokenType.KW_FROM, "FROM", 2, 1);
        assertToken(tok(tokens, 3), TokenType.IDENT, "t", 2, 6);
        assertToken(tok(tokens, 4), TokenType.SEMI, ";", 2, 7);
        assertLastIsEof(tokens, 2, 8);
    }

    // ==================================================================
    // samples.sql 集成测试（资源由 D1-A 创建）
    // ==================================================================

    @Test
    public void testSamplesSqlTokenizes() throws Exception {
        String sql = readResource("/samples.sql");
        assertNotNull(sql, "samples.sql 资源不存在");
        List<Token> tokens = lex(sql);

        // 整份文件可以切分：末尾为 EOF；恰好 10 条语句 => 10 个分号
        assertEquals(TokenType.EOF, tok(tokens, tokens.size() - 1).type());
        long semiCount = tokens.stream().filter(t -> t.type() == TokenType.SEMI).count();
        assertEquals(10, semiCount);

        // 15 个关键字在样例中全部出现且被正确识别
        Set<TokenType> keywordTypes = EnumSet.noneOf(TokenType.class);
        for (Token t : tokens) {
            if (t.type().name().startsWith("KW_")) {
                keywordTypes.add(t.type());
            }
        }
        assertEquals(15, keywordTypes.size());

        // 转义字符串、中文、空字符串的字面量值正确
        boolean sawEscaped = false;
        boolean sawChinese = false;
        boolean sawEmpty = false;
        for (Token t : tokens) {
            if (t.type() == TokenType.STRING) {
                sawEscaped |= "Tom's book".equals(t.value());
                sawChinese |= "你好世界".equals(t.value());
                sawEmpty |= "".equals(t.value());
            }
        }
        assertTrue(sawEscaped, "未发现 'Tom''s book' 转义样例");
        assertTrue(sawChinese, "未发现中文样例");
        assertTrue(sawEmpty, "未发现空字符串样例");
    }

    // ==================================================================
    // 演示 / 反馈部分：34 条用例（24 条固定 + samples.sql 10 条）
    // ==================================================================

    /** 单条演示用例：名称 + SQL + 是否期望抛 LEXER 错误。 */
    private record DemoCase(String name, String sql, boolean expectError) {
    }

    /** 报告汇总：通过 / 未通过用例数。 */
    private record Report(int pass, int fail) {
    }

    private static List<DemoCase> demoCases() {
        List<DemoCase> cases = new ArrayList<>();
        cases.add(new DemoCase("T1 基本 SELECT", "SELECT * FROM t;", false));
        cases.add(new DemoCase("T2 关键字大小写不敏感", "SeLeCt * FrOm t;", false));
        cases.add(new DemoCase("T3 15 个关键字全识别",
                "create table insert into values select from where delete and or not int float varchar",
                false));
        cases.add(new DemoCase("T4 标识符规则", "student _s1 Student student_name", false));
        cases.add(new DemoCase("T5 INT 字面量", "123", false));
        cases.add(new DemoCase("T6 FLOAT 字面量", "1.5", false));
        cases.add(new DemoCase("T7 字符串转义 '' -> '", "'Tom''s book'", false));
        cases.add(new DemoCase("T8 中文字符串", "'你好世界'", false));
        cases.add(new DemoCase("T9 空字符串", "''", false));
        cases.add(new DemoCase("T10 单行注释", "SELECT * -- comment\nFROM student;", false));
        cases.add(new DemoCase("T11 跨行块注释", "SELECT /*\nhello\nworld\n*/\n* FROM student;", false));
        cases.add(new DemoCase("T12 双字符运算符优先", "age >= 18 AND x <= 5 OR y != 3 OR z == 1", false));
        cases.add(new DemoCase("T13 单字符运算符/分隔符", "(1 + 2) * 3 - 4 / 5; a.b,c", false));
        cases.add(new DemoCase("T14 空输入", "", false));
        cases.add(new DemoCase("T15 纯空白", "   \n\t  ", false));
        cases.add(new DemoCase("T16 纯注释", "-- hello\n/* world */", false));
        cases.add(new DemoCase("E1 非法字符 @", "@", true));
        cases.add(new DemoCase("E2 裸感叹号 !", "!", true));
        cases.add(new DemoCase("E3 未闭合字符串", "'abc", true));
        cases.add(new DemoCase("E4 未闭合多行注释", "/* hello", true));
        cases.add(new DemoCase("E5 非法数字 1.2.3", "1.2.3", true));
        cases.add(new DemoCase("E6 数字后接字母 123abc", "123abc", true));
        cases.add(new DemoCase("E7 小数点后无数字 1.", "1.", true));
        cases.add(new DemoCase("S1 超长标识符(1000+ 字符)",
                "a" + "x".repeat(1000), false));

        // samples.sql 的 10 条语句（资源存在时逐条加入）
        String samples = readResource("/samples.sql");
        if (samples != null) {
            String[] parts = samples.split(";");
            int n = 0;
            for (String part : parts) {
                if (!part.trim().isEmpty()) {
                    n++;
                    cases.add(new DemoCase("samples.sql #" + n, part + ";", false));
                }
            }
        } else {
            cases.add(new DemoCase("samples.sql 资源缺失", "SELECT 1;", false));
        }
        return cases;
    }

    private static Report buildReport(StringBuilder sb) {
        List<String> failures = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        List<DemoCase> cases = demoCases();

        sb.append("================================================================\n");
        sb.append(" MiniDB Lexer 反馈报告\n");
        sb.append(" 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append(" 用例总数: ").append(cases.size()).append('\n');
        sb.append("================================================================\n");

        for (int i = 0; i < cases.size(); i++) {
            DemoCase c = cases.get(i);
            sb.append("\n------------ 用例 ").append(String.format("%02d", i + 1))
                    .append(" [").append(c.name()).append("] ------------\n");
            sb.append("输入 SQL: ").append(truncate(esc(c.sql()))).append('\n');

            boolean expectedError = c.expectError();
            try {
                List<Token> tokens = LEXER.tokenize(c.sql());
                if (expectedError) {
                    fail++;
                    failures.add(c.name() + "（期望抛 LEXER 错误，实际切分成功）");
                    sb.append("结果: 期望抛错，但切分成功（共 ").append(tokens.size())
                            .append(" 个 Token）  >>> 未通过 <<<\n");
                    appendTokens(sb, tokens, tokens.size());
                } else {
                    pass++;
                    sb.append("结果: 成功（共 ").append(tokens.size())
                            .append(" 个 Token，末尾为 EOF）  >>> 通过 <<<\n");
                    appendTokens(sb, tokens, tokens.size());
                }
            } catch (MiniDbException e) {
                if (expectedError) {
                    pass++;
                    sb.append("结果: 抛出 LEXER 错误（符合预期）  >>> 通过 <<<\n");
                } else {
                    fail++;
                    failures.add(c.name() + "（期望成功，实际抛错: " + e + "）");
                    sb.append("结果: 意外抛出 LEXER 错误  >>> 未通过 <<<\n");
                }
                sb.append("异常: [").append(e.phase()).append(" @ ")
                        .append(e.pos()).append("] ").append(e.getMessage()).append('\n');
            } catch (Exception other) {
                fail++;
                failures.add(c.name() + "（发生意外异常: " + other + "）");
                sb.append("结果: 发生意外异常  >>> 未通过 <<<\n");
                sb.append("异常: ").append(other).append('\n');
            }
        }

        sb.append("\n================================================================\n");
        sb.append(" 汇总: 共 ").append(cases.size()).append(" 条用例, 通过 ")
                .append(pass).append(" 条, 未通过 ").append(fail).append(" 条\n");
        if (fail > 0) {
            sb.append(" 未通过用例:\n");
            for (String f : failures) {
                sb.append("   - ").append(f).append('\n');
            }
        }
        sb.append("================================================================\n");
        return new Report(pass, fail);
    }

    private static void appendTokens(StringBuilder sb, List<Token> tokens, int limit) {
        int shown = Math.min(limit, tokens.size());
        for (int j = 0; j < shown; j++) {
            Token t = tokens.get(j);
            sb.append("   #").append(String.format("%02d", j + 1))
                    .append("  type=").append(pad(t.type().name(), 10))
                    .append("  text=").append(pad("\"" + esc(t.text()) + "\"", 28))
                    .append("  value=").append(pad(describeValue(t.value()), 24))
                    .append("  @(").append(t.pos().line()).append(':')
                    .append(t.pos().col()).append(")\n");
        }
    }

    private static String describeValue(Object v) {
        if (v == null) {
            return "-";
        }
        return "\"" + esc(String.valueOf(v)) + "\" (" + v.getClass().getSimpleName() + ")";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String truncate(String s) {
        if (s.length() <= 120) {
            return s;
        }
        return s.substring(0, 120) + "...(截断, 实际 " + s.length() + " 字符)";
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }

    private static String readResource(String name) {
        try (InputStream in = LexerTest.class.getResourceAsStream(name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path reportPath() {
        return Paths.get("target", "lexer-report.txt").toAbsolutePath();
    }

    private static void writeReportFile(String content) {
        try {
            Path p = reportPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("[LexerTest] 报告已写入: " + p + " (UTF-8)");
        } catch (IOException e) {
            System.out.println("[LexerTest] 报告文件写入失败: " + e);
        }
    }

    /** 打印 34 条演示用例明细并写 target/lexer-report.txt，同时断言全部按预期通过。 */
    @Test
    public void feedbackReport() {
        StringBuilder sb = new StringBuilder();
        Report report = buildReport(sb);
        String text = sb.toString();
        System.out.print(text);
        writeReportFile(text);
        assertTrue(report.fail() == 0, "存在未通过的演示用例");
    }

    // ------------------------------------------------------------------
    // 命令行入口：java -cp "target/classes;target/test-classes" \
    //            com.minidb.lexer.LexerTest ["SQL1" "SQL2" ...]
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        if (args.length == 0) {
            // 输出内置演示用例并写报告文件
            StringBuilder sb = new StringBuilder();
            buildReport(sb);
            String text = sb.toString();
            System.out.print(text);
            writeReportFile(text);
            return;
        }
        // 把每个参数当作一段独立 SQL 验证
        for (String sql : args) {
            System.out.println("================================================================\n"
                    + "输入 SQL: " + truncate(esc(sql)));
            try {
                List<Token> tokens = LEXER.tokenize(sql);
                StringBuilder sb = new StringBuilder();
                appendTokens(sb, tokens, tokens.size());
                System.out.println("结果: 成功（共 " + tokens.size() + " 个 Token）");
                System.out.print(sb);
            } catch (MiniDbException e) {
                System.out.println("结果: LEXER 错误 [" + e.phase() + " @ " + e.pos() + "] " + e.getMessage());
            }
        }
    }
}
