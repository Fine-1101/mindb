package com.minidb.parser;

import com.minidb.ast.ColumnRef;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 边界测试查漏：只补既有 ParserTest 未覆盖的 5 个边界，不重复已有用例。
 */
class ParserEdgeTest {

    private static List<Token> lex(String sql) throws MiniDbException {
        return new Lexer().tokenize(sql);
    }

    // 边界 1：只有 SELECT 关键字（EOF 处报错，expected 集合明确）
    @Test
    void bareSelectKeywordReportsExpectedSet() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT")));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertEquals(new Position(1, 7), e.pos());
        assertTrue(e.getMessage().contains("end of input (EOF)"), e.getMessage());
        assertTrue(e.getMessage().contains("expected [* / identifier]"), e.getMessage());
    }

    // 边界 2：语句中间的块注释、语句后的注释与 EOF 组合
    @Test
    void commentsInsideAndAfterStatements() throws Exception {
        SelectStmt stmt = (SelectStmt) new Parser().parse(lex("SELECT/*c*/id FROM t; -- tail"));
        assertEquals(1, stmt.columns().size());
        assertEquals("id", ((ColumnRef) stmt.columns().get(0)).column());

        List<Statement> script = new Parser().parseScript(
                lex("SELECT id FROM t; -- done\n/* end */"));
        assertEquals(1, script.size());
        assertEquals("t", ((SelectStmt) script.get(0)).tableName());
    }

    // 边界 3：三列 SELECT 与三列 INSERT（既有测试只覆盖两列）
    @Test
    void threeColumnSelectAndInsert() throws Exception {
        SelectStmt select = (SelectStmt) new Parser().parse(lex("SELECT a, b, c FROM t;"));
        assertEquals(3, select.columns().size());
        assertEquals("c", ((ColumnRef) select.columns().get(2)).column());

        InsertStmt insert = (InsertStmt) new Parser().parse(
                lex("INSERT INTO t (a, b, c) VALUES (1, 'x', 2.5);"));
        assertEquals(3, insert.columns().size());
        assertEquals(3, insert.rows().get(0).size());
        assertEquals("c", insert.columns().get(2).column());
    }

    // 边界 4：语句后连续多个分号（;;;）不产生额外语句、不死循环
    @Test
    void trailingMultipleSemicolonsAfterStatement() throws Exception {
        List<Statement> stmts = new Parser().parseScript(lex("SELECT * FROM t;;;;"));
        assertEquals(1, stmts.size());

        assertEquals(0, new Parser().parseScript(lex(";;;")).size());
        assertEquals(1, new Parser().parseScript(lex(";SELECT a FROM t;;")).size());
    }

    // 边界 5：比较运算符右侧缺操作数时，expected 集合具体且位置指向分号
    @Test
    void missingRightOperandAfterComparisonReportsExpectedSet() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> new Parser().parse(lex("SELECT * FROM t WHERE a <= ;")));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertEquals(new Position(1, 28), e.pos());
        assertTrue(e.getMessage().contains("unexpected token ;"), e.getMessage());
        assertTrue(e.getMessage().contains("identifier"), e.getMessage());
        assertTrue(e.getMessage().contains("expected ["), e.getMessage());
    }

    // ==================================================================
    // 测试后反馈：逐条打印边界 SQL 与解析结果，并写报告文件
    // ==================================================================

    @Test
    void feedbackReport() {
        ParserTestFeedback.Result result = ParserTestFeedback.report(
                "MiniDB Parser 边界测试反馈报告",
                "parser-edge-report.txt",
                edgeDemoCases());
        assertTrue(result.fail() == 0, "存在未通过的边界演示用例: " + result.fail());
    }

    private static List<ParserTestFeedback.DemoCase> edgeDemoCases() {
        List<ParserTestFeedback.DemoCase> cases = new java.util.ArrayList<>();
        cases.add(stmt("B1 仅 SELECT 关键字（EOF 报错）", "SELECT", true));
        cases.add(stmt("B2 语句内块注释 + 尾部注释", "SELECT/*c*/id FROM t; -- tail", false));
        cases.add(new ParserTestFeedback.DemoCase("B3 脚本尾部注释与 EOF",
                "SELECT id FROM t; -- done\n/* end */",
                ParserTestFeedback.Mode.SCRIPT, false));
        cases.add(stmt("B4 三列 SELECT", "SELECT a, b, c FROM t;", false));
        cases.add(stmt("B5 三列 INSERT", "INSERT INTO t (a, b, c) VALUES (1, 'x', 2.5);", false));
        cases.add(new ParserTestFeedback.DemoCase("B6 语句后连续分号",
                "SELECT * FROM t;;;;", ParserTestFeedback.Mode.SCRIPT, false));
        cases.add(new ParserTestFeedback.DemoCase("B7 纯分号脚本（空列表）",
                ";;;", ParserTestFeedback.Mode.SCRIPT, false));
        cases.add(stmt("E1 比较右侧缺操作数", "SELECT * FROM t WHERE a <= ;", true));
        return cases;
    }

    private static ParserTestFeedback.DemoCase stmt(String name, String sql, boolean expectError) {
        return new ParserTestFeedback.DemoCase(name, sql,
                ParserTestFeedback.Mode.STATEMENT, expectError);
    }
}
