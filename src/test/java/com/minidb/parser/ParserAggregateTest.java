package com.minidb.parser;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.FuncCall;
import com.minidb.ast.Literal;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 聚合函数 Parser 测试：COUNT(*) / COUNT(col) / SUM / AVG / MIN / MAX。
 *
 * <p>契约：FuncCall(func 大写, arg, pos)；COUNT(*) 的 arg == null；
 * 普通标识符保持原拼写，不因函数语法改变行为。
 */
class ParserAggregateTest {

    private static List<Token> lex(String sql) throws MiniDbException {
        return new Lexer().tokenize(sql);
    }

    private static SelectStmt select(String sql) throws MiniDbException {
        return (SelectStmt) new Parser().parse(lex(sql));
    }

    // ==================================================================
    // COUNT(*)
    // ==================================================================

    @Test
    void countStarBuildsFuncCallWithNullArg() throws Exception {
        String sql = "SELECT COUNT(*) FROM t;";
        List<Token> ts = lex(sql); // 0 SELECT,1 COUNT,2 (,3 *,4 ),5 FROM,6 t,7 ;,8 EOF
        SelectStmt stmt = select(sql);

        // M0 契约：纯聚合时 columns == null，聚合项在 aggregates 中
        assertNull(stmt.columns());
        assertEquals(1, stmt.aggregates().size());
        FuncCall call = stmt.aggregates().get(0);
        assertEquals("COUNT", call.func());
        assertNull(call.arg()); // COUNT(*) 不是 ColumnRef("*")
        assertEquals(ts.get(1).pos(), call.pos());
        assertEquals("t", stmt.tableName());
        assertNull(stmt.where());
    }

    @Test
    void countStarArgIsNotNullColumnRefStar() throws Exception {
        SelectStmt stmt = select("SELECT COUNT(*) FROM t;");
        FuncCall call = stmt.aggregates().get(0);
        assertNull(call.arg());
        // 断言没有被误构造成 ColumnRef("*")
        assertEquals(false, call.arg() instanceof ColumnRef);
    }

    // ==================================================================
    // COUNT(col)
    // ==================================================================

    @Test
    void countColumnBuildsColumnRefArg() throws Exception {
        String sql = "SELECT COUNT(id) FROM t;";
        List<Token> ts = lex(sql); // 0 SELECT,1 COUNT,2 (,3 id,4 ),5 FROM,6 t
        SelectStmt stmt = select(sql);

        FuncCall call = stmt.aggregates().get(0);
        assertEquals("COUNT", call.func());
        ColumnRef arg = assertInstanceOf(ColumnRef.class, call.arg());
        assertEquals("id", arg.column());
        assertNull(arg.table());
        assertEquals(ts.get(3).pos(), arg.pos());
    }

    // ==================================================================
    // 函数名大小写不敏感（统一大写），普通标识符保留原拼写
    // ==================================================================

    @Test
    void functionNameCaseInsensitiveAndUppercased() throws Exception {
        assertEquals("COUNT", select("SELECT count(*) FROM t;").aggregates().get(0).func());
        assertEquals("COUNT", select("SELECT CoUnT(a) FROM t;").aggregates().get(0).func());
        assertEquals("SUM", select("SELECT sum(a) FROM t;").aggregates().get(0).func());
    }

    @Test
    void plainIdentifierIsNotTreatedAsFunction() throws Exception {
        SelectStmt stmt = select("SELECT count FROM t;");
        Expression item = stmt.columns().get(0);
        ColumnRef ref = assertInstanceOf(ColumnRef.class, item); // 无括号 => 普通列
        assertEquals("count", ref.column());

        SelectStmt multi = select("SELECT count, total FROM t;");
        assertEquals(2, multi.columns().size());
        assertEquals("count", ((ColumnRef) multi.columns().get(0)).column());
        assertEquals("total", ((ColumnRef) multi.columns().get(1)).column());
    }

    @Test
    void identifierSpellingPreserved() throws Exception {
        SelectStmt stmt = select("SELECT StudentName FROM Student;");
        assertEquals("StudentName", ((ColumnRef) stmt.columns().get(0)).column());
        assertEquals("Student", stmt.tableName());
    }

    // ==================================================================
    // SUM / AVG / MIN / MAX
    // ==================================================================

    @Test
    void sumWithArithmeticExpressionArg() throws Exception {
        String sql = "SELECT SUM(a + 1) FROM t;";
        List<Token> ts = lex(sql);
        // 0 SELECT,1 SUM,2 (,3 a,4 +,5 1,6 ),7 FROM,8 t
        SelectStmt stmt = select(sql);

        FuncCall call = stmt.aggregates().get(0);
        assertEquals("SUM", call.func());
        BinaryExpr arg = assertInstanceOf(BinaryExpr.class, call.arg());
        assertEquals(BinaryOp.ADD, arg.op());
        assertEquals("a", ((ColumnRef) arg.left()).column());
        assertEquals(1, ((Literal) arg.right()).value());
        assertEquals(DataType.INT, ((Literal) arg.right()).type());
        assertEquals(ts.get(4).pos(), arg.pos()); // 运算符位置
    }

    @Test
    void sumWithNestedParenthesizedArithmeticArg() throws Exception {
        SelectStmt stmt = select("SELECT SUM((a + 1) * 2) FROM t;");
        FuncCall call = stmt.aggregates().get(0);
        BinaryExpr mul = assertInstanceOf(BinaryExpr.class, call.arg());
        assertEquals(BinaryOp.MUL, mul.op());
        BinaryExpr add = assertInstanceOf(BinaryExpr.class, mul.left());
        assertEquals(BinaryOp.ADD, add.op());
        assertEquals(2, ((Literal) mul.right()).value());
    }

    @Test
    void avgMinMaxBasicParsing() throws Exception {
        FuncCall avg = select("SELECT AVG(score) FROM t;").aggregates().get(0);
        assertEquals("AVG", avg.func());
        assertEquals("score", ((ColumnRef) avg.arg()).column());

        FuncCall min = select("SELECT MIN(id) FROM t;").aggregates().get(0);
        assertEquals("MIN", min.func());
        assertEquals("id", ((ColumnRef) min.arg()).column());

        FuncCall max = select("SELECT MAX(id) FROM t;").aggregates().get(0);
        assertEquals("MAX", max.func());
        assertEquals("id", ((ColumnRef) max.arg()).column());
    }

    // ==================================================================
    // 组合：多聚合、与 WHERE 组合、函数出现在表达式位置
    // ==================================================================

    @Test
    void multipleAggregatesInSelectList() throws Exception {
        SelectStmt stmt = select("SELECT COUNT(*), SUM(a), MAX(b) FROM t;");
        assertNull(stmt.columns());
        assertEquals(3, stmt.aggregates().size());
        assertEquals("COUNT", stmt.aggregates().get(0).func());
        assertEquals("SUM", stmt.aggregates().get(1).func());
        assertEquals("MAX", stmt.aggregates().get(2).func());
    }

    @Test
    void aggregateWithWhere() throws Exception {
        SelectStmt stmt = select("SELECT COUNT(*) FROM t WHERE a > 1;");
        FuncCall call = stmt.aggregates().get(0);
        assertEquals("COUNT", call.func());
        assertNull(call.arg());
        BinaryExpr where = assertInstanceOf(BinaryExpr.class, stmt.where());
        assertEquals(BinaryOp.GT, where.op());
    }

    /** 函数也可以出现在表达式位置（语义合法性由 B 判断，Parser 只保证语法）。 */
    @Test
    void functionCallInsideWhereExpression() throws Exception {
        SelectStmt stmt = select("SELECT * FROM t WHERE COUNT(*) > 1;");
        assertNull(stmt.columns()); // SELECT * 行为不变
        BinaryExpr where = assertInstanceOf(BinaryExpr.class, stmt.where());
        assertEquals(BinaryOp.GT, where.op());
        FuncCall call = assertInstanceOf(FuncCall.class, where.left());
        assertEquals("COUNT", call.func());
        assertNull(call.arg());
    }

    /** 未知函数：语法结构合法即由 Parser 构造 FuncCall，是否支持交给 Semantic。 */
    @Test
    void unknownFunctionIsParsedNotRejected() throws Exception {
        SelectStmt stmt = select("SELECT FOO(x) FROM t;");
        FuncCall call = stmt.aggregates().get(0);
        assertEquals("FOO", call.func());
        assertEquals("x", ((ColumnRef) call.arg()).column());
    }

    /** Parser 不做类型检查：SUM(name)（VARCHAR 列）语法合法即成功。 */
    @Test
    void sumOnAnyColumnParsesWithoutTypeCheck() throws Exception {
        SelectStmt stmt = select("SELECT SUM(name) FROM t;");
        FuncCall call = stmt.aggregates().get(0);
        assertEquals("SUM", call.func());
        assertEquals("name", ((ColumnRef) call.arg()).column());
    }

    // ==================================================================
    // 聚合相关语法错误
    // ==================================================================

    @Test
    void countMissingArgumentReportsParserError() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> select("SELECT COUNT( FROM t;"));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertEquals(new Position(1, 15), e.pos()); // FROM 的位置（COUNT( 后直接空格）
        assertTrue(e.getMessage().contains("unexpected"), e.getMessage());
        assertTrue(e.getMessage().contains("expected ["), e.getMessage());
    }

    @Test
    void countMissingRightParenReportsParserError() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> select("SELECT COUNT(* FROM t;"));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertEquals(new Position(1, 16), e.pos()); // FROM 的位置
        assertTrue(e.getMessage().contains("expected [)]"), e.getMessage());
    }

    @Test
    void danglingOperatorInsideFunctionArgReportsParserError() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> select("SELECT SUM(a + ) FROM t;"));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.getMessage().contains("unexpected token )"), e.getMessage());
        assertTrue(e.getMessage().contains("expected ["), e.getMessage());
    }

    @Test
    void unclosedFunctionParenAtEofReportsParserError() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> select("SELECT COUNT(*) FROM t WHERE COUNT(a"));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.getMessage().contains("end of input (EOF)"), e.getMessage());
        assertTrue(e.getMessage().contains("expected [)]"), e.getMessage());
    }

    /** parseScript 里聚合语句的正常解析（不因新语法破坏脚本行为）。 */
    @Test
    void aggregatesInsideScript() throws Exception {
        List<Statement> stmts = new Parser().parseScript(lex(
                "SELECT COUNT(*) FROM t; SELECT AVG(a) FROM t WHERE a > 0;"));
        assertEquals(2, stmts.size());
        assertNull(((SelectStmt) stmts.get(0)).columns());
        assertEquals("COUNT", ((SelectStmt) stmts.get(0)).aggregates().get(0).func());
        assertEquals("AVG", ((SelectStmt) stmts.get(1)).aggregates().get(0).func());
    }

    // ==================================================================
    // 测试后反馈：逐条打印 SQL 与解析结果（AST / 错误），并写报告文件
    // ==================================================================

    @Test
    void feedbackReport() {
        ParserTestFeedback.Result result = ParserTestFeedback.report(
                "MiniDB Parser 聚合函数反馈报告",
                "parser-aggregate-report.txt",
                aggregateDemoCases());
        assertTrue(result.fail() == 0, "存在未通过的聚合演示用例: " + result.fail());
    }

    private static List<ParserTestFeedback.DemoCase> aggregateDemoCases() {
        List<ParserTestFeedback.DemoCase> cases = new java.util.ArrayList<>();
        cases.add(agg("A1 COUNT(*)", "SELECT COUNT(*) FROM t;", false));
        cases.add(agg("A2 count(*) 小写归一", "SELECT count(*) FROM t;", false));
        cases.add(agg("A3 CoUnT(a) 混合大小写", "SELECT CoUnT(a) FROM t;", false));
        cases.add(agg("A4 COUNT(id)", "SELECT COUNT(id) FROM t;", false));
        cases.add(agg("A5 SUM(a + 1)", "SELECT SUM(a + 1) FROM t;", false));
        cases.add(agg("A6 SUM((a + 1) * 2)", "SELECT SUM((a + 1) * 2) FROM t;", false));
        cases.add(agg("A7 AVG(score)", "SELECT AVG(score) FROM t;", false));
        cases.add(agg("A8 MIN(id)", "SELECT MIN(id) FROM t;", false));
        cases.add(agg("A9 MAX(id)", "SELECT MAX(id) FROM t;", false));
        cases.add(agg("A10 多聚合", "SELECT COUNT(*), SUM(a), MAX(b) FROM t;", false));
        cases.add(agg("A11 聚合 + WHERE", "SELECT COUNT(*) FROM t WHERE a > 1;", false));
        cases.add(agg("A12 函数出现在 WHERE", "SELECT * FROM t WHERE COUNT(*) > 1;", false));
        cases.add(agg("A13 未知函数 FOO(x)", "SELECT FOO(x) FROM t;", false));
        cases.add(agg("A14 SUM(name) 不做类型检查", "SELECT SUM(name) FROM t;", false));
        cases.add(agg("A15 普通标识符 count", "SELECT count FROM t;", false));
        cases.add(agg("A16 SELECT * 不受影响", "SELECT * FROM t;", false));
        cases.add(agg("E1 COUNT( 缺参数", "SELECT COUNT( FROM t;", true));
        cases.add(agg("E2 COUNT(* 缺右括号", "SELECT COUNT(* FROM t;", true));
        cases.add(agg("E3 SUM(a + ) 悬挂运算符", "SELECT SUM(a + ) FROM t;", true));
        cases.add(agg("E4 WHERE 内未闭合括号", "SELECT COUNT(*) FROM t WHERE COUNT(a", true));
        return cases;
    }

    private static ParserTestFeedback.DemoCase agg(String name, String sql, boolean expectError) {
        return new ParserTestFeedback.DemoCase(name, sql,
                ParserTestFeedback.Mode.STATEMENT, expectError);
    }
}
