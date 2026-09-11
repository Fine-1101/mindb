package com.minidb.parser;

import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3 Parser 加固测试：parseScript 单条语句错误恢复 + 诊断增强 + 边界回归。
 *
 * <p>parseScript 公共 API 不变：存在错误时仍抛 MiniDbException（第一条错误的 pos），
 * 但内部会跳过坏语句继续解析；成功解析出的语句可通过包内方法 recoveredStatements()
 * 取证（仅测试可见，不属于公共 API）。
 */
class ParserRecoveryTest {

    private static List<Token> lex(String sql) throws MiniDbException {
        return new Lexer().tokenize(sql);
    }

    private static MiniDbException expectScriptError(Parser parser, String sql)
            throws MiniDbException {
        return assertThrows(MiniDbException.class,
                () -> parser.parseScript(lex(sql)));
    }

    // ==================================================================
    // Test 1：多语句错误恢复 —— 中间坏语句不阻断后续
    // ==================================================================

    @Test
    void middleErrorRecoversAndLastStatementSurvives() throws Exception {
        Parser parser = new Parser();
        MiniDbException e = expectScriptError(parser,
                "SELECT * FROM t;\nCREATE TABLE (;\nDELETE FROM t;");

        // 抛 PARSER 错误，位置指向第二条（第一处错误）语句
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertEquals(new Position(2, 14), e.pos()); // 第二行 CREATE TABLE ( 的 '('
        // message 汇总了错误清单
        assertTrue(e.getMessage().contains("2:14"), e.getMessage());

        // 已成功解析的语句不丢失：第一条 + 恢复后的第三条
        List<Statement> recovered = parser.recoveredStatements();
        assertEquals(2, recovered.size());
        assertInstanceOf(SelectStmt.class, recovered.get(0));
        assertInstanceOf(DeleteStmt.class, recovered.get(1));
    }

    // ==================================================================
    // Test 2：恢复后正常语句不丢（中间 SELECT FROM t 坏掉，前后语句保留）
    // ==================================================================

    @Test
    void goodStatementsAroundErrorAreNotLost() throws Exception {
        Parser parser = new Parser();
        MiniDbException e = expectScriptError(parser,
                "CREATE TABLE t (id INT);\nSELECT FROM t;\nDELETE FROM t;");

        assertEquals(new Position(2, 8), e.pos()); // 第二条 SELECT 后的 FROM
        List<Statement> recovered = parser.recoveredStatements();
        assertEquals(2, recovered.size());
        assertInstanceOf(CreateTableStmt.class, recovered.get(0));
        assertInstanceOf(DeleteStmt.class, recovered.get(1));
        assertTrue(e.getMessage().contains("2:8"), e.getMessage());
    }

    // ==================================================================
    // 首条语句错误：后续语句仍被解析并保留
    // ==================================================================

    @Test
    void firstStatementErrorStillParsesFollowing() throws Exception {
        Parser parser = new Parser();
        MiniDbException e = expectScriptError(parser, "SELECT FROM t;\nDELETE FROM t;");

        assertEquals(new Position(1, 8), e.pos());
        List<Statement> recovered = parser.recoveredStatements();
        assertEquals(1, recovered.size());
        assertInstanceOf(DeleteStmt.class, recovered.get(0));
    }

    // ==================================================================
    // Test 5：连续多个错误全部收集；最终抛第一条；message 含全部位置
    // ==================================================================

    @Test
    void multipleErrorsAllCollectedAndFirstThrown() throws Exception {
        Parser parser = new Parser();
        MiniDbException e = expectScriptError(parser,
                "SELECT FROM t;\nCREATE TABLE ();\nDELETE t;\nINSERT INTO;");

        // 第一处错误在第一条语句（pos 对应抛出的异常）
        assertEquals(new Position(1, 8), e.pos());
        // 四处错误都记录
        assertEquals(4, parser.recordedIssueCount());
        // message 包含全部错误位置清单
        String msg = e.getMessage();
        assertTrue(msg.contains("1:8"), msg);
        assertTrue(msg.contains("2:14"), msg); // CREATE TABLE ( 的 '('
        assertTrue(msg.contains("3:8"), msg);  // DELETE t 的 t
        assertTrue(msg.contains("4:12"), msg); // INSERT INTO ; 的 ';'
        // 没有任何成功语句
        assertEquals(0, parser.recoveredStatements().size());
    }

    // ==================================================================
    // Test 3：expected 集合具体、可读
    // ==================================================================

    @Test
    void expectedSetIsConcreteAndReadable() throws Exception {
        // SELECT FROM t：期待 * 或 identifier
        MiniDbException sel = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT FROM t")));
        assertTrue(sel.getMessage().contains("expected [* / identifier]"), sel.getMessage());

        // CREATE TABLE t (id)：id 后期待类型关键字
        MiniDbException create = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("CREATE TABLE t (id)")));
        String createMsg = create.getMessage();
        assertTrue(createMsg.contains("INT") && createMsg.contains("FLOAT")
                && createMsg.contains("VARCHAR"), createMsg);

        // DELETE t：期待 FROM
        MiniDbException del = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("DELETE t")));
        assertTrue(del.getMessage().contains("expected [FROM]"), del.getMessage());

        // WHERE a =（EOF）：期待原子表达式
        MiniDbException where = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT * FROM t WHERE a =")));
        String whereMsg = where.getMessage();
        assertTrue(whereMsg.contains("identifier") && whereMsg.contains("integer literal")
                && whereMsg.contains("string literal"), whereMsg);
    }

    // ==================================================================
    // Test 4：unexpected token 包含原文 + 类型
    // ==================================================================

    @Test
    void unexpectedTokenKeepsOriginalTextAndType() throws Exception {
        MiniDbException from = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT FROM t")));
        assertTrue(from.getMessage().contains("FROM (KW_FROM)"), from.getMessage());

        MiniDbException ident = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("DELETE t")));
        assertTrue(ident.getMessage().contains("t (IDENT)"), ident.getMessage());

        MiniDbException lit = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT 1 FROM t")));
        assertTrue(lit.getMessage().contains("1 (INT_LIT)"), lit.getMessage());

        MiniDbException eof = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT * FROM")));
        assertTrue(eof.getMessage().contains("end of input (EOF)"), eof.getMessage());
    }

    @Test
    void diagnosticContainsLineAndColumn() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT * FROM t\nWHERE a =")));
        // WHERE a = 之后的 EOF 位于第 2 行第 10 列
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
        assertTrue(e.getMessage().contains("column 10"), e.getMessage());
        assertEquals(new Position(2, 10), e.pos());
    }

    // ==================================================================
    // 边界回归：空脚本 / ;; / 纯注释 / 尾错 / 中错 / 连续错误 / 超长标识符
    // ==================================================================

    @Test
    void emptySemiAndCommentScriptsReturnEmptyWithoutHang() throws Exception {
        Parser parser = new Parser();
        for (String sql : List.of("", ";;", "   ; ; ;  ", "-- hello\n/* test */")) {
            assertEquals(0, parser.parseScript(lex(sql)).size(), "input=[" + sql + "]");
            assertEquals(0, parser.recordedIssueCount());
            assertEquals(0, parser.recoveredStatements().size());
        }
    }

    @Test
    void validScriptStillParsesNormally() throws Exception {
        Parser parser = new Parser();
        List<Statement> stmts = parser.parseScript(lex(
                "CREATE TABLE t (id INT); INSERT INTO t VALUES (1); SELECT * FROM t;"));
        assertEquals(3, stmts.size());
        assertEquals(0, parser.recordedIssueCount());
        assertEquals(3, parser.recoveredStatements().size());
    }

    @Test
    void lastStatementErrorReportedAndPreviousKept() throws Exception {
        Parser parser = new Parser();
        MiniDbException e = expectScriptError(parser, "SELECT * FROM t;\nSELECT FROM t");
        assertEquals(new Position(2, 8), e.pos());
        assertEquals(1, parser.recordedIssueCount());
        assertEquals(1, parser.recoveredStatements().size());
        assertInstanceOf(SelectStmt.class, parser.recoveredStatements().get(0));
    }

    @Test
    void consecutiveErrorsDoNotHangAndBothRecorded() throws Exception {
        Parser parser = new Parser();
        MiniDbException e = expectScriptError(parser, "SELECT FROM t;\nDELETE t;");
        assertEquals(new Position(1, 8), e.pos());
        assertEquals(2, parser.recordedIssueCount());
        assertTrue(e.getMessage().contains("1:8"), e.getMessage());
        assertTrue(e.getMessage().contains("2:8"), e.getMessage());
    }

    @Test
    void missingSemiBetweenStatementsIsAnErrorAndRecovers() throws Exception {
        Parser parser = new Parser();
        // 第一条语句后没有 ';'，直接把 DELETE 当新语句 => 缺少分号错误，随后重同步
        MiniDbException e = expectScriptError(parser, "SELECT * FROM t DELETE FROM t;");
        assertEquals(new Position(1, 17), e.pos()); // DELETE 的位置
        assertEquals(1, parser.recordedIssueCount());
        assertEquals(1, parser.recoveredStatements().size());
    }

    @Test
    void veryLongIdentifierInScript() throws Exception {
        String longName = "a" + "x".repeat(1200);
        Parser parser = new Parser();
        List<Statement> stmts = parser.parseScript(lex(
                "CREATE TABLE " + longName + " (id INT); SELECT " + longName + " FROM "
                        + longName + ";"));
        assertEquals(2, stmts.size());
        CreateTableStmt create = assertInstanceOf(CreateTableStmt.class, stmts.get(0));
        assertEquals(longName, create.tableName());
    }

    /** parse()（单条）行为不受错误恢复影响：仍立即抛错。 */
    @Test
    void singleParseStillFailsFast() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("CREATE TABLE (;")));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.getMessage().contains("unexpected token"), e.getMessage());
    }

    // ==================================================================
    // 反馈 / 演示：逐条打印 SQL + 恢复结果（错误位置 / 成功语句 / 诊断消息）
    // ==================================================================

    private record RecoveryDemoCase(String name, String sql, boolean expectError) {
    }

    private record RecoveryDemoReport(int pass, int fail) {
    }

    private static List<RecoveryDemoCase> recoveryDemoCases() {
        List<RecoveryDemoCase> cases = new ArrayList<>();
        cases.add(new RecoveryDemoCase("R1 中间坏语句恢复",
                "SELECT * FROM t;\nCREATE TABLE (;\nDELETE FROM t;", true));
        cases.add(new RecoveryDemoCase("R2 中错前后好句保留",
                "CREATE TABLE t (id INT);\nSELECT FROM t;\nDELETE FROM t;", true));
        cases.add(new RecoveryDemoCase("R3 首句错误仍解析后续",
                "SELECT FROM t;\nDELETE FROM t;", true));
        cases.add(new RecoveryDemoCase("R4 连续四错全收集",
                "SELECT FROM t;\nCREATE TABLE ();\nDELETE t;\nINSERT INTO;", true));
        cases.add(new RecoveryDemoCase("R5 尾句错误",
                "SELECT * FROM t;\nSELECT FROM t", true));
        cases.add(new RecoveryDemoCase("R6 缺分号连接两语句",
                "SELECT * FROM t DELETE FROM t;", true));
        cases.add(new RecoveryDemoCase("R7 纯错误脚本(空恢复)",
                "CREATE TABLE t (id VARCHAR());", true));
        cases.add(new RecoveryDemoCase("OK1 正常三语句脚本",
                "CREATE TABLE t (id INT); INSERT INTO t VALUES (1); SELECT * FROM t;", false));
        cases.add(new RecoveryDemoCase("OK2 空语句与纯注释",
                ";;\n-- 注释\nSELECT a FROM t;", false));
        cases.add(new RecoveryDemoCase("OK3 大小写混写脚本",
                "SeLeCt * FrOm Student WHERE id != 1;\ninsert into t values (2, 'Tom''s');", false));
        return cases;
    }

    private static RecoveryDemoReport buildRecoveryReport(StringBuilder sb) {
        List<String> failures = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        List<RecoveryDemoCase> cases = recoveryDemoCases();

        sb.append("================================================================\n");
        sb.append(" MiniDB Parser 错误恢复反馈报告\n");
        sb.append(" 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append(" 用例总数: ").append(cases.size()).append('\n');
        sb.append("================================================================\n");

        for (int i = 0; i < cases.size(); i++) {
            RecoveryDemoCase c = cases.get(i);
            sb.append("\n------------ 用例 ").append(String.format("%02d", i + 1))
                    .append(" [").append(c.name()).append("] ------------\n");
            sb.append("输入 SQL: ").append(clip(esc(c.sql()))).append('\n');

            Parser parser = new Parser();
            try {
                List<Statement> stmts = parser.parseScript(lex(c.sql()));
                if (c.expectError()) {
                    fail++;
                    failures.add(c.name() + "（期望抛 PARSER 错误，实际成功）");
                    sb.append("结果: 期望抛错但成功  >>> 未通过 <<<\n");
                } else {
                    pass++;
                    sb.append("结果: 成功（").append(stmts.size()).append(" 条语句）  >>> 通过 <<<\n");
                }
                for (Statement s : stmts) {
                    sb.append("   [").append(s.getClass().getSimpleName())
                            .append("] ").append(s).append('\n');
                }
            } catch (MiniDbException e) {
                if (c.expectError() && e.phase() == MiniDbException.Phase.PARSER) {
                    pass++;
                    sb.append("结果: 抛 PARSER 错误（符合预期）  >>> 通过 <<<\n");
                } else {
                    fail++;
                    failures.add(c.name() + "（实际异常: " + e + "）");
                    sb.append("结果: 异常类型不符  >>> 未通过 <<<\n");
                }
                sb.append("  首错位置: ").append(e.pos()).append('\n');
                List<Statement> recovered = parser.recoveredStatements();
                sb.append("  恢复后保留的正常语句: ").append(recovered.size()).append(" 条");
                if (!recovered.isEmpty()) {
                    sb.append(" -> [");
                    for (int j = 0; j < recovered.size(); j++) {
                        if (j > 0) {
                            sb.append(", ");
                        }
                        sb.append(recovered.get(j).getClass().getSimpleName());
                    }
                    sb.append(']');
                }
                sb.append('\n');
                sb.append("  诊断消息(首行): ").append(oneLine(e.getMessage())).append('\n');
                if (parser.recordedIssueCount() > 1) {
                    sb.append("  错误总数: ").append(parser.recordedIssueCount()).append('\n');
                }
            } catch (Exception other) {
                fail++;
                failures.add(c.name() + "（意外异常: " + other + "）");
                sb.append("结果: 意外异常 ").append(other).append("  >>> 未通过 <<<\n");
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
        return new RecoveryDemoReport(pass, fail);
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replace("\r", " ").replace("\n", " | ");
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }

    private static String clip(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...(截断, 实际 " + s.length() + " 字符)";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static void writeRecoveryReport(String content) {
        try {
            Path p = Paths.get("target", "parser-recovery-report.txt").toAbsolutePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("[ParserRecoveryTest] 报告已写入: " + p + " (UTF-8)");
        } catch (IOException e) {
            System.out.println("[ParserRecoveryTest] 报告文件写入失败: " + e);
        }
    }

    /** 打印全部恢复演示用例（SQL + 恢复结果）并断言全部按预期。 */
    @Test
    void feedbackReport() {
        StringBuilder sb = new StringBuilder();
        RecoveryDemoReport report = buildRecoveryReport(sb);
        String text = sb.toString();
        System.out.print(text);
        writeRecoveryReport(text);
        assertTrue(report.fail() == 0, "存在未通过的恢复演示用例: " + report.fail());
    }
}
