package com.minidb.parser;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.Expression;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.Literal;
import com.minidb.ast.OrderKey;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.SetClause;
import com.minidb.ast.Statement;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.ast.UpdateStmt;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5-A 五特性 Parser 测试：UPDATE → ORDER BY → NULL → GROUP BY → JOIN。
 *
 * <p>断言全部基于 AST record equals（位置取 Lexer 的真实 Token 位置）。
 */
class ParserD5Test {

    private static List<Token> lex(String sql) throws MiniDbException {
        return new Lexer().tokenize(sql);
    }

    private static Statement parse(String sql) throws MiniDbException {
        return new Parser().parse(lex(sql));
    }

    private static Position p(List<Token> ts, int i) {
        return ts.get(i).pos();
    }

    // ==================================================================
    // UPDATE：语法
    // ==================================================================

    @Test
    void updateSingleSetWithWhere() throws Exception {
        String sql = "UPDATE users SET score = 90 WHERE id = 1;";
        List<Token> ts = lex(sql);
        // 0 UPDATE,1 users,2 SET,3 score,4 =,5 90,6 WHERE,7 id,8 =,9 1
        UpdateStmt stmt = assertInstanceOf(UpdateStmt.class, parse(sql));
        assertEquals("users", stmt.tableName());
        assertEquals(p(ts, 1), stmt.pos()); // 约定：语句位置 = 表名 token

        assertEquals(1, stmt.sets().size());
        SetClause set = stmt.sets().get(0);
        assertEquals(new ColumnRef(null, "score", p(ts, 3)), set.column());
        assertEquals(new Literal(90, DataType.INT, p(ts, 5)), set.value());

        assertEquals(new BinaryExpr(new ColumnRef(null, "id", p(ts, 7)), BinaryOp.EQ,
                new Literal(1, DataType.INT, p(ts, 9)), p(ts, 8)), stmt.where());
    }

    @Test
    void updateMultipleSetsReferencingOwnColumn() throws Exception {
        String sql = "UPDATE users SET name = 'Tom', score = score + 5 WHERE id = 1;";
        List<Token> ts = lex(sql);
        // 0 UPDATE,1 users,2 SET,3 name,4 =,5 'Tom',6 ,,7 score,8 =,9 score,10 +,11 5
        UpdateStmt stmt = assertInstanceOf(UpdateStmt.class, parse(sql));
        assertEquals(2, stmt.sets().size());

        assertEquals(new ColumnRef(null, "name", p(ts, 3)), stmt.sets().get(0).column());
        assertEquals(new Literal("Tom", DataType.VARCHAR, p(ts, 5)), stmt.sets().get(0).value());

        // SET 右侧引用本行列：score = score + 5
        SetClause second = stmt.sets().get(1);
        assertEquals(new ColumnRef(null, "score", p(ts, 7)), second.column());
        assertEquals(new BinaryExpr(new ColumnRef(null, "score", p(ts, 9)), BinaryOp.ADD,
                new Literal(5, DataType.INT, p(ts, 11)), p(ts, 10)), second.value());
    }

    @Test
    void updateWithoutWhere() throws Exception {
        UpdateStmt stmt = assertInstanceOf(UpdateStmt.class,
                parse("UPDATE t SET a = 1;"));
        assertNull(stmt.where());
        assertEquals(1, stmt.sets().size());
    }

    @Test
    void updateInScriptKeepsOrder() throws Exception {
        List<Statement> stmts = new Parser().parseScript(lex(
                "UPDATE t SET a = 1; SELECT a FROM t; DELETE FROM t;"));
        assertEquals(3, stmts.size());
        assertInstanceOf(UpdateStmt.class, stmts.get(0));
        assertInstanceOf(SelectStmt.class, stmts.get(1));
        assertInstanceOf(DeleteStmt.class, stmts.get(2));
    }

    @Test
    void updateErrors() throws Exception {
        // SET 后缺赋值
        assertUpdateError("UPDATE t SET", "identifier");
        // 赋值缺 '='
        assertUpdateError("UPDATE t SET a", "=");
        // '=' 右侧缺表达式
        assertUpdateError("UPDATE t SET a =", "expected [");
        // 多赋值中第二个缺 '='
        assertUpdateError("UPDATE t SET a = 1, b", "=");
        // 缺表名
        assertUpdateError("UPDATE SET a = 1", "identifier");
        // SET 后跟关键字而非列名
        assertUpdateError("UPDATE t SET WHERE a = 1", "identifier");
    }

    private static void assertUpdateError(String sql, String fragment) throws MiniDbException {
        List<Token> ts = lex(sql);
        MiniDbException e = assertThrows(MiniDbException.class, () -> new Parser().parse(ts));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.pos().line() >= 1 && e.pos().col() >= 1, "pos 必须合法: " + e.pos());
        assertTrue(e.getMessage().contains(fragment),
                "message 应包含 [" + fragment + "]: " + e.getMessage());
        assertTrue(e.getMessage().contains("expected ["), e.getMessage());
    }

    // ==================================================================
    // ORDER BY：语法（排序键只能是列引用）
    // ==================================================================

    @Test
    void orderByDefaultAscSingleKey() throws Exception {
        String sql = "SELECT id FROM t ORDER BY score;";
        List<Token> ts = lex(sql); // 0 SELECT,1 id,2 FROM,3 t,4 ORDER,5 BY,6 score
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertEquals(1, stmt.orderBy().size());
        assertEquals(new OrderKey(new ColumnRef(null, "score", p(ts, 6)), true),
                stmt.orderBy().get(0));
        assertNull(stmt.where());
        assertNull(stmt.groupBy());
    }

    @Test
    void orderByAscDescAndMultipleKeys() throws Exception {
        String sql = "SELECT * FROM t ORDER BY score DESC, id ASC, name;";
        List<Token> ts = lex(sql);
        // ... 6 score,7 DESC,8 ,,9 id,10 ASC,11 ,,12 name
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertEquals(3, stmt.orderBy().size());
        assertEquals(new OrderKey(new ColumnRef(null, "score", p(ts, 6)), false),
                stmt.orderBy().get(0));
        assertEquals(new OrderKey(new ColumnRef(null, "id", p(ts, 9)), true),
                stmt.orderBy().get(1));
        assertEquals(new OrderKey(new ColumnRef(null, "name", p(ts, 12)), true),
                stmt.orderBy().get(2)); // 缺省 ASC
    }

    @Test
    void orderByWithWhereKeepsClauseOrder() throws Exception {
        String sql = "SELECT id, name FROM users WHERE score > 60 ORDER BY score DESC, id ASC;";
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertNotNull(stmt.where());
        assertEquals(2, stmt.orderBy().size());
        assertEquals("score", stmt.orderBy().get(0).column().column());
        assertEquals(false, stmt.orderBy().get(0).asc());
        assertEquals("id", stmt.orderBy().get(1).column().column());
        assertEquals(List.of("id", "name"),
                stmt.columns().stream().map(ColumnRef::column).toList());
    }

    @Test
    void orderByErrors() throws Exception {
        // 缺列
        assertSelectError("SELECT * FROM t ORDER BY", "identifier");
        // 位置序号不允许
        assertSelectError("SELECT * FROM t ORDER BY 1", "identifier");
        // 表达式不允许（列后面直接跟运算符）
        assertSelectError("SELECT * FROM t ORDER BY score + 1", "unexpected");
        // ORDER BY 后缺 BY
        assertSelectError("SELECT * FROM t ORDER score", "expected [BY]");
        // 多键中第二个非法
        assertSelectError("SELECT * FROM t ORDER BY a, 1", "identifier");
    }

    private static void assertSelectError(String sql, String fragment) throws MiniDbException {
        List<Token> ts = lex(sql);
        MiniDbException e = assertThrows(MiniDbException.class, () -> new Parser().parse(ts));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.pos().line() >= 1 && e.pos().col() >= 1, "pos 必须合法: " + e.pos());
        assertTrue(e.getMessage().contains(fragment),
                "message 应包含 [" + fragment + "]: " + e.getMessage());
    }

    // ==================================================================
    // NULL 字面量 + IS [NOT] NULL
    // ==================================================================

    @Test
    void nullLiteralInWhereComparison() throws Exception {
        String sql = "SELECT * FROM t WHERE a = NULL;";
        List<Token> ts = lex(sql); // 0 SELECT,1 *,2 FROM,3 t,4 WHERE,5 a,6 =,7 NULL
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertEquals(new BinaryExpr(new ColumnRef(null, "a", p(ts, 5)), BinaryOp.EQ,
                new Literal(null, DataType.NULL, p(ts, 7)), p(ts, 6)), stmt.where());
    }

    @Test
    void isNullPredicateUsesUnaryExpr() throws Exception {
        String sql = "SELECT * FROM t WHERE score IS NULL;";
        List<Token> ts = lex(sql); // ... 5 score,6 IS,7 NULL
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        UnaryExpr expr = assertInstanceOf(UnaryExpr.class, stmt.where());
        assertEquals(UnaryOp.IS_NULL, expr.op());
        assertEquals(new ColumnRef(null, "score", p(ts, 5)), expr.operand());
        assertEquals(p(ts, 6), expr.pos()); // 位置取 IS token
    }

    @Test
    void isNotNullPredicateUsesUnaryExpr() throws Exception {
        String sql = "SELECT * FROM t WHERE score IS NOT NULL;";
        List<Token> ts = lex(sql); // ... 5 score,6 IS,7 NOT,8 NULL
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        UnaryExpr expr = assertInstanceOf(UnaryExpr.class, stmt.where());
        assertEquals(UnaryOp.IS_NOT_NULL, expr.op());
        assertEquals(new ColumnRef(null, "score", p(ts, 5)), expr.operand());
        assertEquals(p(ts, 6), expr.pos());
    }

    /** 层级：NOT < IS NULL < comparison —— NOT a IS NULL AND b IS NOT NULL。 */
    @Test
    void isPredicateSitsBetweenNotAndComparison() throws Exception {
        String sql = "SELECT * FROM t WHERE NOT a IS NULL AND b IS NOT NULL;";
        List<Token> ts = lex(sql);
        // 4 WHERE,5 NOT,6 a,7 IS,8 NULL,9 AND,10 b,11 IS,12 NOT,13 NULL
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        BinaryExpr and = assertInstanceOf(BinaryExpr.class, stmt.where());
        assertEquals(BinaryOp.AND, and.op());
        assertEquals(p(ts, 9), and.pos());

        UnaryExpr not = assertInstanceOf(UnaryExpr.class, and.left());
        assertEquals(UnaryOp.NOT, not.op());
        UnaryExpr isNull = assertInstanceOf(UnaryExpr.class, not.operand());
        assertEquals(UnaryOp.IS_NULL, isNull.op());
        assertEquals("a", ((ColumnRef) isNull.operand()).column());

        UnaryExpr isNotNull = assertInstanceOf(UnaryExpr.class, and.right());
        assertEquals(UnaryOp.IS_NOT_NULL, isNotNull.op());
        assertEquals("b", ((ColumnRef) isNotNull.operand()).column());
    }

    @Test
    void nullInInsertValues() throws Exception {
        String sql = "INSERT INTO t VALUES (1, NULL);";
        List<Token> ts = lex(sql); // 4 (,5 1,6 ,,7 NULL
        InsertStmt stmt = assertInstanceOf(InsertStmt.class, parse(sql));
        List<Expression> row = stmt.rows().get(0);
        assertEquals(new Literal(1, DataType.INT, p(ts, 5)), row.get(0));
        assertEquals(new Literal(null, DataType.NULL, p(ts, 7)), row.get(1));
    }

    @Test
    void nullInUpdateSetAndIsNotNullInWhere() throws Exception {
        String sql = "UPDATE t SET score = NULL WHERE id IS NOT NULL;";
        List<Token> ts = lex(sql);
        // 0 UPDATE,1 t,2 SET,3 score,4 =,5 NULL,6 WHERE,7 id,8 IS,9 NOT,10 NULL
        UpdateStmt stmt = assertInstanceOf(UpdateStmt.class, parse(sql));
        assertEquals(new SetClause(new ColumnRef(null, "score", p(ts, 3)),
                new Literal(null, DataType.NULL, p(ts, 5))), stmt.sets().get(0));

        UnaryExpr where = assertInstanceOf(UnaryExpr.class, stmt.where());
        assertEquals(UnaryOp.IS_NOT_NULL, where.op());
        assertEquals(new ColumnRef(null, "id", p(ts, 7)), where.operand());
        assertEquals(p(ts, 8), where.pos());
    }

    @Test
    void nullInsideArithmeticAndFunctionArg() throws Exception {
        // a + NULL > 1
        SelectStmt stmt = assertInstanceOf(SelectStmt.class,
                parse("SELECT * FROM t WHERE a + NULL > 1;"));
        BinaryExpr gt = assertInstanceOf(BinaryExpr.class, stmt.where());
        BinaryExpr add = assertInstanceOf(BinaryExpr.class, gt.left());
        assertEquals(BinaryOp.ADD, add.op());
        assertEquals(new Literal(null, DataType.NULL, add.right().pos()), add.right());

        // COUNT(NULL) 作为聚合参数
        SelectStmt agg = assertInstanceOf(SelectStmt.class,
                parse("SELECT COUNT(NULL) FROM t;"));
        assertEquals(1, agg.aggregates().size());
        assertEquals(new Literal(null, DataType.NULL, agg.aggregates().get(0).arg().pos()),
                agg.aggregates().get(0).arg());
    }

    @Test
    void isPredicateErrors() throws Exception {
        assertSelectError("SELECT * FROM t WHERE id IS;", "expected [NULL]");
        assertSelectError("SELECT * FROM t WHERE id IS NOT;", "expected [NULL]");
        assertSelectError("SELECT * FROM t WHERE id IS NULL NULL;", "unexpected");
    }

    /**
     * 契约说明：M0 冻结的 SelectStmt 只有 columns(List&lt;ColumnRef&gt;)+aggregates(List&lt;FuncCall&gt;)，
     * 没有可承载字面量投影的字段，因此 SELECT NULL 在 Parser 阶段报错。
     * 这是 AST 契约限制（需 B/D 参与才能扩展），不是 NULL 字面量未实现——
     * NULL 在 WHERE / JOIN ON / SET / VALUES / 函数参数等表达式位置均已支持。
     */
    @Test
    void selectNullProjectionNotRepresentableInFrozenContract() throws Exception {
        List<Token> ts = lex("SELECT NULL FROM t;");
        MiniDbException e = assertThrows(MiniDbException.class, () -> new Parser().parse(ts));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.getMessage().contains("expected [* / identifier]"), e.getMessage());
        assertEquals(p(ts, 1), e.pos()); // 指向 NULL token
    }

    // ==================================================================
    // GROUP BY：语法（分组键只能是列引用）
    // ==================================================================

    @Test
    void groupBySingleColumnWithAggregate() throws Exception {
        String sql = "SELECT name, COUNT(*) FROM users GROUP BY name;";
        List<Token> ts = lex(sql); // 0 SELECT,1 name,2 ,,3 COUNT,4 (,5 *,6 ),7 FROM,8 users,9 GROUP,10 BY,11 name
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertEquals(List.of("name"), stmt.columns().stream().map(ColumnRef::column).toList());
        assertEquals(1, stmt.aggregates().size());
        assertEquals("COUNT", stmt.aggregates().get(0).func());
        assertEquals(1, stmt.groupBy().size());
        assertEquals(new ColumnRef(null, "name", p(ts, 11)), stmt.groupBy().get(0));
        assertNull(stmt.orderBy());
    }

    @Test
    void groupByMultipleColumnsWithWhereAndOrderBy() throws Exception {
        String sql = "SELECT name, score, COUNT(*) FROM users WHERE score > 60"
                + " GROUP BY name, score ORDER BY score DESC;";
        List<Token> ts = lex(sql);
        // 0 SELECT,1 name,2 ,,3 score,4 ,,5 COUNT,6 (,7 *,8 ),9 FROM,10 users,
        // 11 WHERE,12 score,13 >,14 60,15 GROUP,16 BY,17 name,18 ,,19 score,
        // 20 ORDER,21 BY,22 score,23 DESC
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertNotNull(stmt.where());
        assertEquals(2, stmt.groupBy().size());
        assertEquals(new ColumnRef(null, "name", p(ts, 17)), stmt.groupBy().get(0));
        assertEquals(new ColumnRef(null, "score", p(ts, 19)), stmt.groupBy().get(1));
        assertEquals(1, stmt.orderBy().size());
        assertEquals(new OrderKey(new ColumnRef(null, "score", p(ts, 22)), false),
                stmt.orderBy().get(0));
    }

    @Test
    void groupByErrors() throws Exception {
        // 缺分组列
        assertSelectError("SELECT * FROM t GROUP BY", "identifier");
        // 位置序号不允许
        assertSelectError("SELECT * FROM t GROUP BY 1", "identifier");
        // 表达式不允许
        assertSelectError("SELECT * FROM t GROUP BY score + 1", "unexpected");
        // GROUP 后缺 BY
        assertSelectError("SELECT * FROM t GROUP score", "expected [BY]");
        // 本轮不支持 HAVING
        assertSelectError("SELECT name FROM t GROUP BY name HAVING COUNT(*) > 1", "unexpected");
    }

    // ==================================================================
    // JOIN（单层 INNER）+ 限定列引用
    // ==================================================================

    @Test
    void basicJoinOnQualifiedColumns() throws Exception {
        String sql = "SELECT * FROM users JOIN scores ON users.id = scores.id;";
        List<Token> ts = lex(sql);
        // 0 SELECT,1 *,2 FROM,3 users,4 JOIN,5 scores,6 ON,7 users,8 .,9 id,10 =,11 scores,12 .,13 id
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertNull(stmt.columns()); // SELECT * 不受 JOIN 影响
        assertEquals("users", stmt.tableName());
        assertEquals("scores", stmt.joinTable());
        assertEquals(new BinaryExpr(
                new ColumnRef("users", "id", p(ts, 7)), BinaryOp.EQ,
                new ColumnRef("scores", "id", p(ts, 11)), p(ts, 10)), stmt.joinOn());
        assertEquals(p(ts, 3), stmt.pos()); // 语句位置 = 左表
    }

    @Test
    void joinWithWhereAndClauseOrder() throws Exception {
        String sql = "SELECT * FROM users JOIN scores ON users.id = scores.id WHERE users.id > 1;";
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertEquals("scores", stmt.joinTable());
        assertNotNull(stmt.joinOn());
        assertNotNull(stmt.where());
    }

    @Test
    void qualifiedColumnInSelectList() throws Exception {
        String sql = "SELECT users.id FROM users;";
        List<Token> ts = lex(sql); // 0 SELECT,1 users,2 .,3 id
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertEquals(1, stmt.columns().size());
        assertEquals(new ColumnRef("users", "id", p(ts, 1)), stmt.columns().get(0));
        assertNull(stmt.joinTable());
    }

    @Test
    void qualifiedColumnsInGroupByAndOrderBy() throws Exception {
        String sql = "SELECT users.id, COUNT(*) FROM users JOIN scores ON users.id = scores.id"
                + " GROUP BY users.id ORDER BY users.id DESC;";
        List<Token> ts = lex(sql);
        // 1 users,2 .,3 id ... 23 users,24 .,25 id (GROUP BY) ... 28 users,29 .,30 id (ORDER BY)
        SelectStmt stmt = assertInstanceOf(SelectStmt.class, parse(sql));
        assertEquals(new ColumnRef("users", "id", p(ts, 1)), stmt.columns().get(0));
        assertEquals("COUNT", stmt.aggregates().get(0).func());
        assertEquals(new ColumnRef("users", "id", p(ts, 23)), stmt.groupBy().get(0));
        assertEquals(new OrderKey(new ColumnRef("users", "id", p(ts, 28)), false),
                stmt.orderBy().get(0));
    }

    @Test
    void joinErrors() throws Exception {
        // JOIN 后缺表
        assertSelectError("SELECT * FROM t1 JOIN", "expected [identifier]");
        // ON 后缺表达式
        assertSelectError("SELECT * FROM t1 JOIN t2 ON", "expected [");
        // 缺 ON
        assertSelectError("SELECT * FROM t1 JOIN t2", "expected [ON]");
        // 不支持链式 JOIN
        assertSelectError("SELECT * FROM t1 JOIN t2 ON t1.a = t2.a JOIN t3 ON t2.b = t3.b",
                "unexpected");
        // 不支持逗号连接
        assertSelectError("SELECT * FROM t1, t2", "unexpected");
        // 不支持 INNER 关键字（INNER 会被当作标识符，随后报 expected [;]）
        assertSelectError("SELECT * FROM t1 INNER JOIN t2 ON t1.a = t2.a", "unexpected");
        // 不支持表别名
        assertSelectError("SELECT * FROM t1 x JOIN t2 ON x.a = t2.a", "unexpected");
    }

    // ==================================================================
    // 测试后反馈：逐条打印 SQL 与解析结果（AST / 错误），并写报告文件
    // ==================================================================

    @Test
    void feedbackReport() {
        ParserTestFeedback.Result result = ParserTestFeedback.report(
                "MiniDB Parser D5 反馈报告（UPDATE / ORDER BY / NULL / GROUP BY / JOIN）",
                "parser-d5-report.txt",
                d5DemoCases());
        assertTrue(result.fail() == 0, "存在未通过的 D5 演示用例: " + result.fail());
    }

    private static List<ParserTestFeedback.DemoCase> d5DemoCases() {
        List<ParserTestFeedback.DemoCase> cases = new ArrayList<>();
        // UPDATE
        cases.add(stmt("U1 UPDATE 单字段", "UPDATE users SET score = 90 WHERE id = 1;", false));
        cases.add(stmt("U2 UPDATE 多字段 + 自引用", "UPDATE users SET name = 'Tom', score = score + 5 WHERE id = 1;", false));
        cases.add(stmt("U3 UPDATE 无 WHERE", "UPDATE t SET a = 1;", false));
        cases.add(stmt("E-U1 UPDATE 缺赋值", "UPDATE t SET", true));
        // ORDER BY
        cases.add(stmt("O1 ORDER BY 单列缺省 ASC", "SELECT id FROM t ORDER BY score;", false));
        cases.add(stmt("O2 ORDER BY DESC", "SELECT * FROM t ORDER BY score DESC;", false));
        cases.add(stmt("O3 ORDER BY 多列混合方向", "SELECT * FROM t ORDER BY score DESC, id ASC, name;", false));
        cases.add(stmt("O4 WHERE + ORDER BY", "SELECT id, name FROM users WHERE score > 60 ORDER BY score DESC, id ASC;", false));
        cases.add(stmt("E-O1 ORDER BY 缺列", "SELECT * FROM t ORDER BY", true));
        cases.add(stmt("E-O2 ORDER BY 位置序号非法", "SELECT * FROM t ORDER BY 1", true));
        cases.add(stmt("E-O3 ORDER BY 表达式非法", "SELECT * FROM t ORDER BY score + 1", true));
        // NULL / IS [NOT] NULL
        cases.add(stmt("N1 WHERE a = NULL", "SELECT * FROM t WHERE a = NULL;", false));
        cases.add(stmt("N2 IS NULL", "SELECT * FROM t WHERE score IS NULL;", false));
        cases.add(stmt("N3 IS NOT NULL", "SELECT * FROM t WHERE score IS NOT NULL;", false));
        cases.add(stmt("N4 NOT + IS NULL + AND", "SELECT * FROM t WHERE NOT a IS NULL AND b IS NOT NULL;", false));
        cases.add(stmt("N5 INSERT NULL", "INSERT INTO t VALUES (1, NULL);", false));
        cases.add(stmt("N6 UPDATE SET NULL", "UPDATE t SET score = NULL WHERE id IS NOT NULL;", false));
        cases.add(stmt("N7 NULL 参与算术", "SELECT * FROM t WHERE a + NULL > 1;", false));
        cases.add(stmt("E-N1 IS 后缺 NULL", "SELECT * FROM t WHERE id IS;", true));
        cases.add(stmt("E-N2 IS NOT 后缺 NULL", "SELECT * FROM t WHERE id IS NOT;", true));
        // GROUP BY
        cases.add(stmt("G1 GROUP BY 单列 + 聚合", "SELECT name, COUNT(*) FROM users GROUP BY name;", false));
        cases.add(stmt("G2 GROUP BY 多列 + WHERE + ORDER BY", "SELECT name, score, COUNT(*) FROM users WHERE score > 60 GROUP BY name, score ORDER BY score DESC;", false));
        cases.add(stmt("E-G1 GROUP BY 缺列", "SELECT * FROM t GROUP BY", true));
        cases.add(stmt("E-G2 GROUP BY 位置序号非法", "SELECT * FROM t GROUP BY 1", true));
        cases.add(stmt("E-G3 GROUP BY 表达式非法", "SELECT * FROM t GROUP BY score + 1", true));
        // JOIN + 限定列
        cases.add(stmt("J1 JOIN ON 限定列", "SELECT * FROM users JOIN scores ON users.id = scores.id;", false));
        cases.add(stmt("J2 JOIN + WHERE", "SELECT * FROM users JOIN scores ON users.id = scores.id WHERE users.id > 1;", false));
        cases.add(stmt("J3 SELECT 列表限定列", "SELECT users.id FROM users;", false));
        cases.add(stmt("J4 限定列 + GROUP BY + ORDER BY", "SELECT users.id, COUNT(*) FROM users JOIN scores ON users.id = scores.id GROUP BY users.id ORDER BY users.id DESC;", false));
        cases.add(stmt("E-J1 JOIN 缺表", "SELECT * FROM t1 JOIN", true));
        cases.add(stmt("E-J2 ON 缺表达式", "SELECT * FROM t1 JOIN t2 ON", true));
        cases.add(stmt("E-J3 缺 ON", "SELECT * FROM t1 JOIN t2", true));
        cases.add(stmt("E-J4 链式 JOIN 不支持", "SELECT * FROM t1 JOIN t2 ON t1.a = t2.a JOIN t3 ON t2.b = t3.b", true));
        cases.add(stmt("E-J5 逗号连接不支持", "SELECT * FROM t1, t2", true));
        return cases;
    }

    private static ParserTestFeedback.DemoCase stmt(String name, String sql, boolean expectError) {
        return new ParserTestFeedback.DemoCase(name, sql,
                ParserTestFeedback.Mode.STATEMENT, expectError);
    }
}
