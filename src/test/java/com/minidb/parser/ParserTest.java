package com.minidb.parser;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.Expression;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.Literal;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.catalog.ColumnDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.lexer.TokenType;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser 测试：四类 SQL、表达式优先级结构、大小写、parseScript、错误报告、边界。
 * 期望 AST 尽量用 Lexer 的 Token 位置构造，保证与 Parser 完全一致后 assertEquals。
 */
class ParserTest {

    // ==================================================================
    // 辅助
    // ==================================================================

    private static List<Token> lex(String sql) throws MiniDbException {
        return new Lexer().tokenize(sql);
    }

    private static Statement parse(String sql) throws MiniDbException {
        return new Parser().parse(lex(sql));
    }

    private static List<Statement> parseScript(String sql) throws MiniDbException {
        return new Parser().parseScript(lex(sql));
    }

    private static Position p(List<Token> ts, int i) {
        return ts.get(i).pos();
    }

    private static ColumnRef col(List<Token> ts, int i) {
        Token t = ts.get(i);
        return new ColumnRef(null, t.text(), t.pos());
    }

    private static Literal intLit(List<Token> ts, int i) {
        Token t = ts.get(i);
        return new Literal(t.value(), DataType.INT, t.pos());
    }

    private static Literal floatLit(List<Token> ts, int i) {
        Token t = ts.get(i);
        return new Literal(t.value(), DataType.FLOAT, t.pos());
    }

    private static Literal strLit(List<Token> ts, int i) {
        Token t = ts.get(i);
        return new Literal(t.value(), DataType.VARCHAR, t.pos());
    }

    /** 比较表达式：Eq/Ne/...，op 位置取操作符 Token。 */
    private static BinaryExpr cmp(List<Token> ts, int leftIdx, BinaryOp op, int rightIdx) {
        // 操作符位于 left 与 right 之间
        return new BinaryExpr(
                col(ts, leftIdx), op,
                rightIsLiteral(ts, rightIdx) ? litOf(ts, rightIdx) : col(ts, rightIdx),
                ts.get(leftIdx + 1).pos());
    }

    private static boolean rightIsLiteral(List<Token> ts, int i) {
        TokenType type = ts.get(i).type();
        return type == TokenType.INT_LIT || type == TokenType.FLOAT_LIT || type == TokenType.STRING;
    }

    private static Expression litOf(List<Token> ts, int i) {
        switch (ts.get(i).type()) {
            case INT_LIT: return intLit(ts, i);
            case FLOAT_LIT: return floatLit(ts, i);
            default: return strLit(ts, i);
        }
    }

    private static void assertErrorPosition(String sql, int tokenIndexOfUnexpected) throws MiniDbException {
        List<Token> ts = lex(sql);
        Position expected = p(ts, tokenIndexOfUnexpected);
        MiniDbException e = assertThrows(MiniDbException.class, () -> new Parser().parse(ts));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertEquals(expected, e.pos());
        String msg = e.getMessage().toLowerCase();
        assertTrue(msg.contains("unexpected"), "message 应包含 unexpected: " + e.getMessage());
        assertTrue(msg.contains("expected"), "message 应包含 expected: " + e.getMessage());
        assertTrue(msg.contains("expected [") && !msg.contains("expected []"),
                "expected 集合不能为空: " + e.getMessage());
    }

    /** 将所有位置归一化后比较表达式结构（用于文本长度不同的等价输入，如 = 与 ==）。 */
    private static Expression stripPos(Expression e) {
        if (e instanceof BinaryExpr b) {
            return new BinaryExpr(stripPos(b.left()), b.op(), stripPos(b.right()),
                    new Position(0, 0));
        }
        if (e instanceof UnaryExpr u) {
            return new UnaryExpr(u.op(), stripPos(u.operand()), new Position(0, 0));
        }
        if (e instanceof Literal l) {
            return new Literal(l.value(), l.type(), new Position(0, 0));
        }
        if (e instanceof ColumnRef c) {
            return new ColumnRef(c.table(), c.column(), new Position(0, 0));
        }
        return e;
    }

    // ==================================================================
    // CREATE TABLE
    // ==================================================================

    @Test
    void createTableWithAllTypes() throws Exception {
        String sql = "CREATE TABLE users (id INT, name VARCHAR(32), score FLOAT);";
        List<Token> ts = lex(sql);
        CreateTableStmt stmt = (CreateTableStmt) parse(sql);
        assertEquals(new CreateTableStmt("users", List.of(
                        new ColumnDef("id", DataType.INT, 0),
                        new ColumnDef("name", DataType.VARCHAR, 32),
                        new ColumnDef("score", DataType.FLOAT, 0)),
                p(ts, 2)), stmt);
    }

    @Test
    void createTableNamesKeepOriginalCase() throws Exception {
        CreateTableStmt stmt = (CreateTableStmt) parse("CREATE TABLE Student (ID INT, Name VARCHAR(10));");
        assertEquals("Student", stmt.tableName());
        assertEquals("ID", stmt.columns().get(0).name());
        assertEquals("Name", stmt.columns().get(1).name());
    }

    @Test
    void createTableTypesCaseInsensitive() throws Exception {
        // int/float/varchar 小写关键字也必须识别
        CreateTableStmt stmt = (CreateTableStmt) parse(
                "create table t (a int, b float, c varchar(8));");
        assertEquals(DataType.INT, stmt.columns().get(0).type());
        assertEquals(DataType.FLOAT, stmt.columns().get(1).type());
        assertEquals(DataType.VARCHAR, stmt.columns().get(2).type());
        assertEquals(8, stmt.columns().get(2).maxLength());
    }

    @Test
    void createTableErrors() throws Exception {
        assertErrorPosition("CREATE TABLE t (", 4);          // EOF
        assertErrorPosition("CREATE TABLE t (id)", 5);        // RPAREN 处期望类型
        assertErrorPosition("CREATE TABLE t (id INT", 6);     // EOF 处期望 COMMA/RPAREN
        assertErrorPosition("CREATE TABLE t (id VARCHAR)", 6); // VARCHAR 后需要 (
        assertErrorPosition("CREATE TABLE t (id VARCHAR())", 7); // ( ) 中间需要数字
        assertErrorPosition("CREATE TABLE t (id VARCHAR(abc))", 7); // 括号内需要 INT_LIT
    }

    // ==================================================================
    // INSERT
    // ==================================================================

    @Test
    void insertWithoutColumnsEscapedString() throws Exception {
        String sql = "INSERT INTO users VALUES (1, 'Tom''s book', 3.14);";
        List<Token> ts = lex(sql);
        InsertStmt stmt = (InsertStmt) parse(sql);
        assertNull(stmt.columns());
        assertEquals(1, stmt.rows().size());
        List<Expression> row = stmt.rows().get(0);
        // tokens: 0 INSERT, 1 INTO, 2 users, 3 VALUES, 4 (, 5 1, 6 ,,
        //          7 'Tom''s book', 8 ,, 9 3.14, 10 ), 11 ;, 12 EOF
        assertEquals(new Literal(1, DataType.INT, p(ts, 5)), row.get(0));
        assertEquals(new Literal("Tom's book", DataType.VARCHAR, p(ts, 7)), row.get(1));
        assertEquals(new Literal(3.14, DataType.FLOAT, p(ts, 9)), row.get(2));
        // stmt.pos 约定为表名 token 位置（token 2 = users），非语句首
        assertEquals(p(ts, 2), stmt.pos());
    }

    @Test
    void insertWithColumnsAndMultipleRows() throws Exception {
        String sql = "INSERT INTO users (id, name) VALUES (1, 'Tom'), (2, 'Alice'), (3, '');";
        InsertStmt stmt = (InsertStmt) parse(sql);
        assertEquals(List.of("id", "name"), stmt.columns().stream().map(ColumnRef::column).toList());
        assertEquals(3, stmt.rows().size());
        // 三行值各自独立
        assertEquals(2, stmt.rows().get(0).size());
        assertEquals("Tom", ((Literal) stmt.rows().get(0).get(1)).value());
        assertEquals("Alice", ((Literal) stmt.rows().get(1).get(1)).value());
        assertEquals("", ((Literal) stmt.rows().get(2).get(1)).value());
    }

    @Test
    void insertMultiLineValues() throws Exception {
        InsertStmt stmt = (InsertStmt) parse("INSERT INTO t VALUES\n(1, 'a'),\n(2, 'b'),\n(3, 'c');");
        assertNull(stmt.columns());
        assertEquals(3, stmt.rows().size());
        assertEquals(2, ((Literal) stmt.rows().get(1).get(0)).value());
    }

    @Test
    void insertErrors() throws Exception {
        assertErrorPosition("INSERT INTO t VALUES", 4);          // EOF，期望 LPAREN
        assertErrorPosition("INSERT INTO t VALUES (", 5);        // EOF，期望字面量
        assertErrorPosition("INSERT INTO t VALUES (1,", 7);      // EOF，期望字面量
        assertErrorPosition("INSERT INTO t (id,) VALUES (1)", 6); // RPAREN 处期望列名
        assertErrorPosition("INSERT INTO t (id) VALUES", 7);     // EOF，期望 LPAREN
    }

    // ==================================================================
    // SELECT
    // ==================================================================

    @Test
    void selectStarColumnsNull() throws Exception {
        String sql = "SELECT * FROM student;";
        List<Token> ts = lex(sql);
        SelectStmt stmt = (SelectStmt) parse(sql);
        assertNull(stmt.columns());
        assertEquals("student", stmt.tableName());
        assertNull(stmt.where());
        // stmt.pos 约定为表名 token 位置（token 3 = student），非语句首
        assertEquals(p(ts, 3), stmt.pos());
    }

    @Test
    void selectColumnListWithWhere() throws Exception {
        String sql = "SELECT id, name FROM student WHERE score >= 90.0;";
        SelectStmt stmt = (SelectStmt) parse(sql);
        assertEquals(List.of("id", "name"), stmt.columns().stream().map(ColumnRef::column).toList());
        assertEquals("student", stmt.tableName());
        // WHERE score >= 90.0
        assertEquals(BinaryOp.GE, ((BinaryExpr) stmt.where()).op());
        assertEquals("score", ((ColumnRef) ((BinaryExpr) stmt.where()).left()).column());
        assertEquals(90.0, ((Literal) ((BinaryExpr) stmt.where()).right()).value());
    }

    @Test
    void selectErrors() throws Exception {
        assertErrorPosition("SELECT FROM t", 1);              // FROM 处期望 STAR/IDENT
        assertErrorPosition("SELECT id", 2);                  // EOF 处期望 FROM
        assertErrorPosition("SELECT id, FROM t", 3);          // FROM 处期望列名
        assertErrorPosition("SELECT * users", 2);             // users 处期望 FROM
        assertErrorPosition("SELECT * FROM", 3);              // EOF 处期望表名
        assertErrorPosition("SELECT * FROM t WHERE", 5);      // EOF 处期望表达式
    }

    // ==================================================================
    // SELECT 聚合 + DISTINCT（D4）
    // ==================================================================

    @Test
    void aggregateCountStar() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT COUNT(*) FROM student;");
        assertNull(stmt.columns());
        assertEquals(1, stmt.aggregates().size());
        assertEquals("COUNT", stmt.aggregates().get(0).func());
        assertNull(stmt.aggregates().get(0).arg());
        assertFalse(stmt.distinct());
    }

    @Test
    void aggregateListKeepsWrittenOrder() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT COUNT(*), SUM(score), AVG(score + 1), MIN(name), MAX(id) FROM student;");
        assertNull(stmt.columns());
        List<String> funcs = stmt.aggregates().stream().map(f -> f.func()).toList();
        assertEquals(List.of("COUNT", "SUM", "AVG", "MIN", "MAX"), funcs);
        // SUM(score) 参数为列引用
        assertEquals("score", ((ColumnRef) stmt.aggregates().get(1).arg()).column());
        // AVG(score + 1) 参数为算术表达式
        assertEquals(BinaryOp.ADD, ((BinaryExpr) stmt.aggregates().get(2).arg()).op());
    }

    @Test
    void aggregateFuncNameNormalizedUpperCase() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("select count(*), sum(score) from student;");
        assertEquals("COUNT", stmt.aggregates().get(0).func());
        assertEquals("SUM", stmt.aggregates().get(1).func());
    }

    @Test
    void distinctFlagParsed() throws Exception {
        SelectStmt distinct = (SelectStmt) parse("SELECT DISTINCT name FROM student;");
        assertTrue(distinct.distinct());
        assertEquals(List.of("name"), distinct.columns().stream().map(ColumnRef::column).toList());
        assertNull(distinct.aggregates());

        SelectStmt plain = (SelectStmt) parse("SELECT name FROM student;");
        assertFalse(plain.distinct());
    }

    @Test
    void mixedColumnsAndAggregatesParseable() throws Exception {
        // 混写 Parser 不拒绝（columns 与 aggregates 并存），错误归 Semantic"聚合函数不能与普通列混写"
        SelectStmt stmt = (SelectStmt) parse("SELECT name, COUNT(*) FROM student;");
        assertEquals(List.of("name"), stmt.columns().stream().map(ColumnRef::column).toList());
        assertEquals(1, stmt.aggregates().size());
        assertEquals("COUNT", stmt.aggregates().get(0).func());
    }

    @Test
    void aggregateInWhereParsesToFuncCall() throws Exception {
        // WHERE 中的聚合 Parser 也生成 FuncCall（错误归 Semantic"聚合函数不允许出现在 WHERE 中"）
        SelectStmt stmt = (SelectStmt) parse("SELECT name FROM student WHERE COUNT(id) > 1;");
        BinaryExpr where = (BinaryExpr) stmt.where();
        assertTrue(where.left() instanceof com.minidb.ast.FuncCall f && "COUNT".equals(f.func()));
    }

    // ==================================================================
    // DELETE
    // ==================================================================

    @Test
    void deleteWithoutWhere() throws Exception {
        String sql = "DELETE FROM users;";
        List<Token> ts = lex(sql);
        DeleteStmt stmt = (DeleteStmt) parse(sql);
        assertEquals(new DeleteStmt("users", null, p(ts, 2)), stmt);
    }

    @Test
    void deleteWithWhere() throws Exception {
        DeleteStmt stmt = (DeleteStmt) parse("DELETE FROM users WHERE id = 1;");
        assertEquals("users", stmt.tableName());
        assertNotNull(stmt.where());
        assertEquals(BinaryOp.EQ, ((BinaryExpr) stmt.where()).op());
    }

    @Test
    void deleteErrors() throws Exception {
        assertErrorPosition("DELETE t", 1);          // t 处期望 FROM
        assertErrorPosition("DELETE FROM", 2);       // EOF 处期望表名
        assertErrorPosition("DELETE FROM t WHERE", 4); // EOF 处期望表达式
    }

    // ==================================================================
    // 表达式优先级 —— 结构性 AST 断言
    // ==================================================================

    /** a = 1 OR b = 2 AND c = 3  =>  OR(Eq(a,1), AND(Eq(b,2), Eq(c,3))) */
    @Test
    void andBindsTighterThanOr() throws Exception {
        String sql = "a = 1 OR b = 2 AND c = 3";
        List<Token> ts = lex(sql);
        // tokens: 0 a, 1 =, 2 1, 3 OR, 4 b, 5 =, 6 2, 7 AND, 8 c, 9 =, 10 3, 11 EOF
        Expression eqA = cmp(ts, 0, BinaryOp.EQ, 2);
        Expression eqB = cmp(ts, 4, BinaryOp.EQ, 6);
        Expression eqC = cmp(ts, 8, BinaryOp.EQ, 10);
        Expression andBc = new BinaryExpr(eqB, BinaryOp.AND, eqC, p(ts, 7));
        Expression expected = new BinaryExpr(eqA, BinaryOp.OR, andBc, p(ts, 3));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    /** NOT a = 1 AND b = 2  =>  AND(NOT(Eq(a,1)), Eq(b,2))，NOT 只作用于比较 */
    @Test
    void notBindsOnlyComparison() throws Exception {
        String sql = "NOT a = 1 AND b = 2";
        List<Token> ts = lex(sql);
        // tokens: 0 NOT, 1 a, 2 =, 3 1, 4 AND, 5 b, 6 =, 7 2
        Expression notEqA = new UnaryExpr(UnaryOp.NOT,
                cmp(ts, 1, BinaryOp.EQ, 3), p(ts, 0));
        Expression eqB = cmp(ts, 5, BinaryOp.EQ, 7);
        Expression expected = new BinaryExpr(notEqA, BinaryOp.AND, eqB, p(ts, 4));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    /** (a = 1 OR b = 2) AND c = 3  =>  AND(OR(Eq(a,1), Eq(b,2)), Eq(c,3)) */
    @Test
    void parenthesesChangeGrouping() throws Exception {
        String sql = "(a = 1 OR b = 2) AND c = 3";
        List<Token> ts = lex(sql);
        // tokens: 0 (, 1 a, 2 =, 3 1, 4 OR, 5 b, 6 =, 7 2, 8 ), 9 AND, 10 c, 11 =, 12 3
        Expression orAb = new BinaryExpr(
                cmp(ts, 1, BinaryOp.EQ, 3), BinaryOp.OR,
                cmp(ts, 5, BinaryOp.EQ, 7), p(ts, 4));
        Expression eqC = cmp(ts, 10, BinaryOp.EQ, 12);
        Expression expected = new BinaryExpr(orAb, BinaryOp.AND, eqC, p(ts, 9));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    /** a = 1 AND NOT b = 2  =>  AND(Eq(a,1), NOT(Eq(b,2))) */
    @Test
    void notOnRightOfAnd() throws Exception {
        String sql = "a = 1 AND NOT b = 2";
        List<Token> ts = lex(sql);
        // tokens: 0 a, 1 =, 2 1, 3 AND, 4 NOT, 5 b, 6 =, 7 2
        Expression notEqB = new UnaryExpr(UnaryOp.NOT,
                cmp(ts, 5, BinaryOp.EQ, 7), p(ts, 4));
        Expression expected = new BinaryExpr(
                cmp(ts, 0, BinaryOp.EQ, 2), BinaryOp.AND, notEqB, p(ts, 3));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    /** 括号内的 OR：a = 1 AND (b = 2 OR c = 3) */
    @Test
    void parenthesesAroundOrInsideAnd() throws Exception {
        String sql = "a = 1 AND (b = 2 OR c = 3)";
        List<Token> ts = lex(sql);
        // tokens: 0 a,1 =,2 1,3 AND,4 (,5 b,6 =,7 2,8 OR,9 c,10 =,11 3,12 )
        Expression orBc = new BinaryExpr(
                cmp(ts, 5, BinaryOp.EQ, 7), BinaryOp.OR,
                cmp(ts, 9, BinaryOp.EQ, 11), p(ts, 8));
        Expression expected = new BinaryExpr(
                cmp(ts, 0, BinaryOp.EQ, 2), BinaryOp.AND, orBc, p(ts, 3));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    // ==================================================================
    // 比较运算符与 = / == 统一
    // ==================================================================

    @Test
    void eqAndEqeqBothMapToEq() throws Exception {
        // 注意：= 与 == 文本长度不同，后续字面量的列号会整体偏移，
        // 因此用 stripPos 归一化位置后断言“结构完全相同”。
        SelectStmt s1 = (SelectStmt) parse("SELECT * FROM t WHERE a = 1;");
        SelectStmt s2 = (SelectStmt) parse("SELECT * FROM t WHERE a == 1;");
        assertEquals(stripPos(s1.where()), stripPos(s2.where()));
        assertEquals(BinaryOp.EQ, ((BinaryExpr) s1.where()).op());
        assertEquals(BinaryOp.EQ, ((BinaryExpr) s2.where()).op());
    }

    @Test
    void otherComparisonOperators() throws Exception {
        SelectStmt ne = (SelectStmt) parse("SELECT * FROM t WHERE a != 1;");
        SelectStmt lt = (SelectStmt) parse("SELECT * FROM t WHERE a < 1;");
        SelectStmt le = (SelectStmt) parse("SELECT * FROM t WHERE a <= 1;");
        SelectStmt gt = (SelectStmt) parse("SELECT * FROM t WHERE a > 1;");
        SelectStmt ge = (SelectStmt) parse("SELECT * FROM t WHERE a >= 1;");
        assertEquals(BinaryOp.NE, ((BinaryExpr) ne.where()).op());
        assertEquals(BinaryOp.LT, ((BinaryExpr) lt.where()).op());
        assertEquals(BinaryOp.LE, ((BinaryExpr) le.where()).op());
        assertEquals(BinaryOp.GT, ((BinaryExpr) gt.where()).op());
        assertEquals(BinaryOp.GE, ((BinaryExpr) ge.where()).op());
    }

    // ==================================================================
    // 原子：括号、字面量、列
    // ==================================================================

    @Test
    void atoms() throws Exception {
        // 括号字面量/列
        SelectStmt withParens = (SelectStmt) parse("SELECT * FROM t WHERE (id = 1);");
        assertEquals(BinaryOp.EQ, ((BinaryExpr) withParens.where()).op());
        // (a = 1 OR b = 2) 作为整体参与比较两侧均可
        SelectStmt nested = (SelectStmt) parse(
                "SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3;");
        assertEquals(BinaryOp.AND, ((BinaryExpr) nested.where()).op());
    }

    @Test
    void stringLiteralInWhere() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE name = 'Tom''s book';");
        Expression right = ((BinaryExpr) stmt.where()).right();
        assertEquals(new Literal("Tom's book", DataType.VARCHAR,
                ((Literal) right).pos()), right);
    }

    // ==================================================================
    // P1：算术表达式
    // ==================================================================

    /** a + b * c => ADD(a, MUL(b, c)) */
    @Test
    void arithmeticMulBindsTighterThanAdd() throws Exception {
        String sql = "a + b * c";
        List<Token> ts = lex(sql);
        // tokens: 0 a, 1 +, 2 b, 3 *, 4 c
        Expression mul = new BinaryExpr(col(ts, 2), BinaryOp.MUL, col(ts, 4), p(ts, 3));
        Expression expected = new BinaryExpr(col(ts, 0), BinaryOp.ADD, mul, p(ts, 1));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    /** -a + b => ADD(NEG(a), b) */
    @Test
    void negBindsTighterThanAdd() throws Exception {
        String sql = "-a + b";
        List<Token> ts = lex(sql);
        // tokens: 0 -, 1 a, 2 +, 3 b
        Expression neg = new UnaryExpr(UnaryOp.NEG, col(ts, 1), p(ts, 0));
        Expression expected = new BinaryExpr(neg, BinaryOp.ADD, col(ts, 3), p(ts, 2));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    /** (a + b) * c => MUL(ADD(a, b), c) */
    @Test
    void arithmeticParentheses() throws Exception {
        String sql = "(a + b) * c";
        List<Token> ts = lex(sql);
        // tokens: 0 (, 1 a, 2 +, 3 b, 4 ), 5 *, 6 c
        Expression add = new BinaryExpr(col(ts, 1), BinaryOp.ADD, col(ts, 3), p(ts, 2));
        Expression expected = new BinaryExpr(add, BinaryOp.MUL, col(ts, 6), p(ts, 5));

        SelectStmt stmt = (SelectStmt) parse("SELECT * FROM t WHERE " + sql + ";");
        assertEquals(stripPos(expected), stripPos(stmt.where()));
    }

    // ==================================================================
    // 大小写
    // ==================================================================

    @Test
    void caseInsensitiveSameAstOriginalCasePreserved() throws Exception {
        String[] variants = {
                "select * from Student;",
                "SELECT * FROM Student;",
                "SeLeCt * FrOm Student;",
        };
        SelectStmt first = (SelectStmt) parse(variants[0]);
        for (String sql : variants) {
            assertEquals(first, parse(sql));
        }
        assertEquals("Student", first.tableName());

        // 列名保留原始拼写
        SelectStmt withCol = (SelectStmt) parse("SELECT ID FROM Student;");
        assertEquals(List.of("ID"), withCol.columns().stream().map(ColumnRef::column).toList());
        assertEquals("Student", withCol.tableName());
    }

    // ==================================================================
    // parseScript
    // ==================================================================

    @Test
    void scriptMultipleStatementsInOrder() throws Exception {
        List<Statement> stmts = parseScript(
                "CREATE TABLE t (id INT);\nINSERT INTO t VALUES (1);\nSELECT * FROM t;");
        assertEquals(3, stmts.size());
        assertTrue(stmts.get(0) instanceof CreateTableStmt);
        assertTrue(stmts.get(1) instanceof InsertStmt);
        assertTrue(stmts.get(2) instanceof SelectStmt);
    }

    @Test
    void scriptEmptyStatementsAndComments() throws Exception {
        assertEquals(0, parseScript("").size());
        assertEquals(0, parseScript(";;").size());
        assertEquals(0, parseScript("   ; ; ;  ").size());
        assertEquals(0, parseScript("-- hello\n/* test */").size());
        assertEquals(1, parseScript("; SELECT * FROM t;").size());
        assertEquals(2, parseScript("SELECT a FROM t;;SELECT b FROM t;").size());
    }

    @Test
    void scriptErrorPointsToSecondStatement() throws Exception {
        String sql = "SELECT * FROM t;\nSELECT FROM u;";
        List<Token> ts = lex(sql);
        // 第二个 SELECT 的 FROM 位于第 2 行；找到它并验证错误位置一致
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> new Parser().parseScript(ts));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        Token secondFrom = ts.stream()
                .filter(t -> t.type() == TokenType.KW_FROM && t.pos().line() == 2)
                .findFirst().orElseThrow();
        assertEquals(secondFrom.pos(), e.pos());
        assertTrue(e.getMessage().contains("FROM"));
    }

    @Test
    void parseSingleWithAndWithoutSemi() throws Exception {
        SelectStmt withSemi = (SelectStmt) parse("SELECT * FROM t;");
        SelectStmt withoutSemi = (SelectStmt) parse("SELECT * FROM t");
        assertEquals(withSemi, withoutSemi);
    }

    @Test
    void parseSingleRejectsTrailingJunk() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> parse("SELECT * FROM t junk"));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.getMessage().contains("unexpected"));
    }

    // ==================================================================
    // 边界：超长标识符 / Tom''s book / SELECT *
    // ==================================================================

    @Test
    void veryLongIdentifier() throws Exception {
        String longCol = "a" + "x".repeat(1000);
        SelectStmt stmt = (SelectStmt) parse("SELECT " + longCol + " FROM t;");
        assertEquals(List.of(longCol), stmt.columns().stream().map(ColumnRef::column).toList());

        CreateTableStmt create = (CreateTableStmt) parse(
                "CREATE TABLE " + longCol + " (id INT);");
        assertEquals(longCol, create.tableName());
    }

    @Test
    void tomEscapedStringInInsertAndWhere() throws Exception {
        InsertStmt insert = (InsertStmt) parse("INSERT INTO t VALUES ('Tom''s book');");
        assertEquals("Tom's book", ((Literal) insert.rows().get(0).get(0)).value());

        SelectStmt select = (SelectStmt) parse("SELECT * FROM t WHERE name = 'Tom''s book';");
        assertEquals("Tom's book",
                ((Literal) ((BinaryExpr) select.where()).right()).value());
    }

    // ==================================================================
    // samples.sql 集成
    // ==================================================================

    @Test
    void samplesSqlFullScriptParses() throws Exception {
        String sql;
        try (java.io.InputStream in = ParserTest.class.getResourceAsStream("/samples.sql")) {
            assertNotNull(in, "samples.sql 资源不存在");
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        List<Statement> stmts = new Parser().parseScript(new Lexer().tokenize(sql));
        // 11 条（D4 追加 SELECT DISTINCT name FROM student）
        assertEquals(11, stmts.size());
        assertEquals(2, stmts.stream().filter(s -> s instanceof CreateTableStmt).count());
        assertEquals(3, stmts.stream().filter(s -> s instanceof InsertStmt).count());
        assertEquals(4, stmts.stream().filter(s -> s instanceof SelectStmt).count());
        assertEquals(2, stmts.stream().filter(s -> s instanceof DeleteStmt).count());
    }

    // ==================================================================
    // 反馈 / 演示部分：逐条打印 SQL 原文 + 解析结果（AST 或 PARSER 错误）
    // ==================================================================

    /** 单条演示用例：名称 + SQL + 是否期望抛 PARSER 错误。 */
    private record ParserDemoCase(String name, String sql, boolean expectError) {
    }

    private record DemoReport(int pass, int fail) {
    }

    private static List<ParserDemoCase> parserDemoCases() {
        List<ParserDemoCase> cases = new ArrayList<>();
        cases.add(new ParserDemoCase("T1 基本 SELECT *", "SELECT * FROM t;", false));
        cases.add(new ParserDemoCase("T2 关键字大小写混写",
                "SeLeCt id, name FrOm Student WHERE score >= 90.5;", false));
        cases.add(new ParserDemoCase("T3 SELECT + WHERE 比较",
                "SELECT id FROM users WHERE age >= 18 AND name != 'Tom''s book';", false));
        cases.add(new ParserDemoCase("T4 NOT 优先级", "SELECT * FROM t WHERE NOT a = 1 AND b = 2;", false));
        cases.add(new ParserDemoCase("T5 括号改变结合", "SELECT * FROM t WHERE (a = 1 OR b = 2) AND c = 3;", false));
        cases.add(new ParserDemoCase("T6 == 与 = 一致", "SELECT * FROM t WHERE a == 1;", false));
        cases.add(new ParserDemoCase("T7 算术优先级", "SELECT * FROM t WHERE a + b * c > 10;", false));
        cases.add(new ParserDemoCase("C1 CREATE 全类型",
                "CREATE TABLE users (id INT, name VARCHAR(32), score FLOAT);", false));
        cases.add(new ParserDemoCase("C2 CREATE 小写类型/保留拼写",
                "create table Student (ID int, Name varchar(8));", false));
        cases.add(new ParserDemoCase("I1 INSERT 无列 + 转义字符串",
                "INSERT INTO student VALUES (1, 'Tom''s book', 90.5);", false));
        cases.add(new ParserDemoCase("I2 INSERT 指定列多行",
                "INSERT INTO users (id, name) VALUES (1, 'Tom'), (2, '你好');", false));
        cases.add(new ParserDemoCase("I3 INSERT 空字符串", "INSERT INTO t VALUES ('');", false));
        cases.add(new ParserDemoCase("D1 DELETE", "DELETE FROM users;", false));
        cases.add(new ParserDemoCase("D2 DELETE + WHERE", "DELETE FROM users WHERE id = 1;", false));
        cases.add(new ParserDemoCase("P1 parseScript 多语句",
                "CREATE TABLE t (id INT); INSERT INTO t VALUES (1); SELECT * FROM t;", false));
        cases.add(new ParserDemoCase("P2 纯注释脚本", "-- hello\n/* world */", false));
        cases.add(new ParserDemoCase("P3 空语句分隔", ";; SELECT * FROM t ;;", false));
        cases.add(new ParserDemoCase("E1 错误: SELECT FROM t", "SELECT FROM t", true));
        cases.add(new ParserDemoCase("E2 错误: CREATE 未闭合", "CREATE TABLE t (", true));
        cases.add(new ParserDemoCase("E3 错误: INSERT 缺值", "INSERT INTO t VALUES", true));
        cases.add(new ParserDemoCase("E4 错误: DELETE 少 FROM", "DELETE t", true));
        cases.add(new ParserDemoCase("E5 错误: WHERE 缺右值", "SELECT * FROM t WHERE a =", true));
        cases.add(new ParserDemoCase("E6 错误: 列缺类型", "CREATE TABLE t (id)", true));
        cases.add(new ParserDemoCase("B1 超长标识符列",
                "SELECT " + "a" + "x".repeat(1000) + " FROM t;", false));
        return cases;
    }

    private static DemoReport buildParserReport(StringBuilder sb) {
        List<String> failures = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        List<ParserDemoCase> cases = parserDemoCases();

        sb.append("================================================================\n");
        sb.append(" MiniDB Parser 反馈报告\n");
        sb.append(" 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append(" 用例总数: ").append(cases.size()).append('\n');
        sb.append("================================================================\n");

        for (int i = 0; i < cases.size(); i++) {
            ParserDemoCase c = cases.get(i);
            sb.append("\n------------ 用例 ").append(String.format("%02d", i + 1))
                    .append(" [").append(c.name()).append("] ------------\n");
            sb.append("输入 SQL: ").append(demoClip(demoEsc(c.sql()))).append('\n');

            List<Statement> stmts;
            try {
                stmts = new Parser().parseScript(new Lexer().tokenize(c.sql()));
            } catch (MiniDbException e) {
                if (c.expectError() && e.phase() == MiniDbException.Phase.PARSER) {
                    pass++;
                    sb.append("结果: 抛出 PARSER 错误（符合预期）  >>> 通过 <<<\n");
                } else {
                    fail++;
                    failures.add(c.name() + "（期望 " + (c.expectError() ? "PARSER 错误" : "成功")
                            + "，实际抛 " + e + "）");
                    sb.append("结果: 抛出错误  >>> 未通过 <<<\n");
                }
                sb.append("异常: [").append(e.phase()).append(" @ ").append(e.pos())
                        .append("] ").append(e.getMessage()).append('\n');
                continue;
            } catch (Exception other) {
                fail++;
                failures.add(c.name() + "（发生意外异常: " + other + "）");
                sb.append("结果: 发生意外异常  >>> 未通过 <<<\n");
                sb.append("异常: ").append(other).append('\n');
                continue;
            }

            if (c.expectError()) {
                fail++;
                failures.add(c.name() + "（期望抛 PARSER 错误，实际解析成功）");
                sb.append("结果: 期望抛错，但解析成功  >>> 未通过 <<<\n");
            } else {
                pass++;
                sb.append("结果: 成功（共 ").append(stmts.size())
                        .append(" 条语句）  >>> 通过 <<<\n");
            }
            for (int j = 0; j < stmts.size(); j++) {
                sb.append("   [").append(j + 1).append("] ")
                        .append(stmts.get(j).getClass().getSimpleName()).append('\n');
                sb.append("        ").append(stmts.get(j)).append('\n');
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
        return new DemoReport(pass, fail);
    }

    private static String demoEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String demoClip(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...(截断, 实际 " + s.length() + " 字符)";
    }

    private static void writeParserReport(String content) {
        try {
            Path p = Paths.get("target", "parser-report.txt").toAbsolutePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("[ParserTest] 报告已写入: " + p + " (UTF-8)");
        } catch (IOException e) {
            System.out.println("[ParserTest] 报告文件写入失败: " + e);
        }
    }

    /** 打印全部演示用例（SQL + AST/错误）并写 target/parser-report.txt，同时断言全部按预期。 */
    @Test
    void feedbackReport() {
        StringBuilder sb = new StringBuilder();
        DemoReport report = buildParserReport(sb);
        String text = sb.toString();
        System.out.print(text);
        writeParserReport(text);
        assertTrue(report.fail() == 0, "存在未通过的 Parser 演示用例: " + report.fail());
    }

    // ------------------------------------------------------------------
    // 命令行入口：java -cp "target/classes;target/test-classes" \
    //            com.minidb.parser.ParserTest ["SQL1" "SQL2" ...]
    // ------------------------------------------------------------------

    public static void main(String[] args) throws MiniDbException {
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder();
            buildParserReport(sb);
            String text = sb.toString();
            System.out.print(text);
            writeParserReport(text);
            return;
        }
        for (String sql : args) {
            System.out.println("================================================================\n"
                    + "输入 SQL: " + demoClip(demoEsc(sql)));
            try {
                List<Statement> stmts = new Parser().parseScript(new Lexer().tokenize(sql));
                System.out.println("结果: 成功（共 " + stmts.size() + " 条语句）");
                for (Statement stmt : stmts) {
                    System.out.println("  [" + stmt.getClass().getSimpleName() + "] " + stmt);
                }
            } catch (MiniDbException e) {
                System.out.println("结果: PARSER 错误 [" + e.phase() + " @ " + e.pos() + "] " + e.getMessage());
            }
        }
    }
}
