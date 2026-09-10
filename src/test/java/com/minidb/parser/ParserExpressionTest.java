package com.minidb.parser;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.common.MiniDbException;
import com.minidb.lexer.Lexer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 复杂表达式加压测试：深层括号、算术与比较优先级、连续 NOT、运算符组合。
 * 目的是确认既有递归下降结构（优先级/结合性）没有回归，而不是修改实现。
 */
class ParserExpressionTest {

    private static Expression where(String sql) throws MiniDbException {
        SelectStmt stmt = (SelectStmt) new Parser().parse(
                new Lexer().tokenize("SELECT * FROM t WHERE " + sql + ";"));
        return stmt.where();
    }

    // ==================================================================
    // 1. 深层括号
    // ==================================================================

    @Test
    void deeplyNestedParentheses() throws Exception {
        // (((a + 1) * 2) > 5)
        Expression expr = where("(((a + 1) * 2) > 5)");
        BinaryExpr gt = assertInstanceOf(BinaryExpr.class, expr);
        assertEquals(BinaryOp.GT, gt.op());

        BinaryExpr mul = assertInstanceOf(BinaryExpr.class, gt.left());
        assertEquals(BinaryOp.MUL, mul.op());

        BinaryExpr add = assertInstanceOf(BinaryExpr.class, mul.left());
        assertEquals(BinaryOp.ADD, add.op());
        assertEquals("a", ((ColumnRef) add.left()).column());
        assertEquals(1, ((com.minidb.ast.Literal) add.right()).value());
        assertEquals(2, ((com.minidb.ast.Literal) mul.right()).value());
    }

    @Test
    void manyLevelsOfParenthesesDoNotBreak() throws Exception {
        // (((((a = 1)))))：多层括号只改变结合，不改变语义层
        Expression expr = where("(((((a = 1)))))");
        BinaryExpr eq = assertInstanceOf(BinaryExpr.class, expr);
        assertEquals(BinaryOp.EQ, eq.op());
        assertEquals("a", ((ColumnRef) eq.left()).column());
    }

    // ==================================================================
    // 2. 算术出现在比较表达式中：* / 优先于 + -，且先于比较
    // ==================================================================

    @Test
    void multiplicationBindsTighterThanAddition() throws Exception {
        // a + 1 * 2 > 5  =>  GT(ADD(a, MUL(1, 2)), 5)
        Expression expr = where("a + 1 * 2 > 5");
        BinaryExpr gt = assertInstanceOf(BinaryExpr.class, expr);
        assertEquals(BinaryOp.GT, gt.op());

        BinaryExpr add = assertInstanceOf(BinaryExpr.class, gt.left());
        assertEquals(BinaryOp.ADD, add.op());
        assertEquals("a", ((ColumnRef) add.left()).column());
        BinaryExpr mul = assertInstanceOf(BinaryExpr.class, add.right());
        assertEquals(BinaryOp.MUL, mul.op());
    }

    @Test
    void arithmeticBindsTighterThanComparison() throws Exception {
        // a * 2 >= b + 1  =>  GE(MUL(a,2), ADD(b,1))
        Expression expr = where("a * 2 >= b + 1");
        BinaryExpr ge = assertInstanceOf(BinaryExpr.class, expr);
        assertEquals(BinaryOp.GE, ge.op());
        assertEquals(BinaryOp.MUL, ((BinaryExpr) ge.left()).op());
        assertEquals(BinaryOp.ADD, ((BinaryExpr) ge.right()).op());
    }

    @Test
    void additiveAndMultiplicativeAreLeftAssociative() throws Exception {
        // a - b - c => SUB(SUB(a, b), c)（根是 = 比较，左孩子才是 SUB 链）
        BinaryExpr eq = assertInstanceOf(BinaryExpr.class, where("a - b - c = 0"));
        assertEquals(BinaryOp.EQ, eq.op());
        BinaryExpr sub = assertInstanceOf(BinaryExpr.class, eq.left());
        assertEquals(BinaryOp.SUB, sub.op());
        BinaryExpr sub2 = assertInstanceOf(BinaryExpr.class, sub.left());
        assertEquals(BinaryOp.SUB, sub2.op());
        assertEquals("a", ((ColumnRef) sub2.left()).column());
        assertEquals("b", ((ColumnRef) sub2.right()).column());

        // a / b / c => DIV(DIV(a, b), c)
        BinaryExpr eq2 = assertInstanceOf(BinaryExpr.class, where("a / b / c = 0"));
        BinaryExpr div = assertInstanceOf(BinaryExpr.class, eq2.left());
        assertEquals(BinaryOp.DIV, div.op());
        BinaryExpr div2 = assertInstanceOf(BinaryExpr.class, div.left());
        assertEquals(BinaryOp.DIV, div2.op());
    }

    // ==================================================================
    // 3. 连续 NOT
    // ==================================================================

    @Test
    void chainedNotIsRightNested() throws Exception {
        // NOT NOT NOT a = 1  =>  NOT(NOT(NOT(EQ(a,1))))
        Expression expr = where("NOT NOT NOT a = 1");

        UnaryExpr n1 = assertInstanceOf(UnaryExpr.class, expr);
        assertEquals(UnaryOp.NOT, n1.op());
        UnaryExpr n2 = assertInstanceOf(UnaryExpr.class, n1.operand());
        assertEquals(UnaryOp.NOT, n2.op());
        UnaryExpr n3 = assertInstanceOf(UnaryExpr.class, n2.operand());
        assertEquals(UnaryOp.NOT, n3.op());

        BinaryExpr eq = assertInstanceOf(BinaryExpr.class, n3.operand());
        assertEquals(BinaryOp.EQ, eq.op());
        assertEquals("a", ((ColumnRef) eq.left()).column());
    }

    @Test
    void notStillBindsToComparisonOnly() throws Exception {
        // NOT a = 1 AND b = 2  =>  AND(NOT(EQ(a,1)), EQ(b,2))
        BinaryExpr and = assertInstanceOf(BinaryExpr.class, where("NOT a = 1 AND b = 2"));
        assertEquals(BinaryOp.AND, and.op());
        UnaryExpr not = assertInstanceOf(UnaryExpr.class, and.left());
        assertEquals(UnaryOp.NOT, not.op());
        assertEquals(BinaryOp.EQ, ((BinaryExpr) not.operand()).op());
        assertEquals(BinaryOp.EQ, ((BinaryExpr) and.right()).op());
    }

    // ==================================================================
    // 4. 运算符组合（* / + - < <= > >= = == != AND OR NOT 与括号混合）
    // ==================================================================

    @Test
    void mixedOperatorsKeepPrecedenceAndAssociativity() throws Exception {
        // (a * 2 + b / 3 - c >= 1) AND NOT (d <= 4 OR e > 5) OR f == 6 AND g != 7 OR h = 8
        Expression expr = where(
                "(a * 2 + b / 3 - c >= 1) AND NOT (d <= 4 OR e > 5)"
                        + " OR f == 6 AND g != 7 OR h = 8");

        // 最外层是 OR（左结合：Or(Or(...), h=8)）
        BinaryExpr or1 = assertInstanceOf(BinaryExpr.class, expr);
        assertEquals(BinaryOp.OR, or1.op());
        assertEquals(BinaryOp.EQ, ((BinaryExpr) or1.right()).op()); // h = 8

        BinaryExpr or2 = assertInstanceOf(BinaryExpr.class, or1.left());
        assertEquals(BinaryOp.OR, or2.op());
        assertEquals(BinaryOp.AND, ((BinaryExpr) or2.right()).op()); // f == 6 AND g != 7

        BinaryExpr and1 = assertInstanceOf(BinaryExpr.class, or2.left());
        assertEquals(BinaryOp.AND, and1.op());
        assertEquals(BinaryOp.GE, ((BinaryExpr) and1.left()).op()); // 括号内比较

        UnaryExpr not = assertInstanceOf(UnaryExpr.class, and1.right()); // NOT (...)
        assertEquals(UnaryOp.NOT, not.op());
        BinaryExpr innerOr = assertInstanceOf(BinaryExpr.class, not.operand());
        assertEquals(BinaryOp.OR, innerOr.op());
        assertEquals(BinaryOp.LE, ((BinaryExpr) innerOr.left()).op());
        assertEquals(BinaryOp.GT, ((BinaryExpr) innerOr.right()).op());
    }

    @Test
    void allComparisonOperatorsPositiveConfirmation() throws Exception {
        // 正向确认六种比较运算符都能解析为对应 BinaryOp（<= / >= / != / == 等）
        assertEquals(BinaryOp.LT, opOf("a < 1"));
        assertEquals(BinaryOp.LE, opOf("a <= 1"));
        assertEquals(BinaryOp.GT, opOf("a > 1"));
        assertEquals(BinaryOp.GE, opOf("a >= 1"));
        assertEquals(BinaryOp.EQ, opOf("a = 1"));
        assertEquals(BinaryOp.EQ, opOf("a == 1")); // = 与 == 都映射 EQ
        assertEquals(BinaryOp.NE, opOf("a != 1"));
    }

    private static BinaryOp opOf(String condition) throws MiniDbException {
        return ((BinaryExpr) where(condition)).op();
    }

    // ==================================================================
    // 括号必须真正改变结合关系
    // ==================================================================

    @Test
    void parenthesesOverrideDefaultPrecedence() throws Exception {
        // 无括号：a + b * c = 0  =>  EQ(ADD(a, MUL(b, c)), 0)
        BinaryExpr eq1 = assertInstanceOf(BinaryExpr.class, where("a + b * c = 0"));
        assertEquals(BinaryOp.EQ, eq1.op());
        BinaryExpr noParen = assertInstanceOf(BinaryExpr.class, eq1.left());
        assertEquals(BinaryOp.ADD, noParen.op());
        assertEquals(BinaryOp.MUL, ((BinaryExpr) noParen.right()).op());

        // 有括号：(a + b) * c = 0  =>  EQ(MUL(ADD(a, b), c), 0)
        BinaryExpr eq2 = assertInstanceOf(BinaryExpr.class, where("(a + b) * c = 0"));
        assertEquals(BinaryOp.EQ, eq2.op());
        BinaryExpr withParen = assertInstanceOf(BinaryExpr.class, eq2.left());
        assertEquals(BinaryOp.MUL, withParen.op());
        assertEquals(BinaryOp.ADD, ((BinaryExpr) withParen.left()).op());

        // 逻辑：a = 1 OR b = 2 AND c = 3  =>  OR(Eq, AND)
        BinaryExpr or = assertInstanceOf(BinaryExpr.class, where("a = 1 OR b = 2 AND c = 3"));
        assertEquals(BinaryOp.OR, or.op());
        assertEquals(BinaryOp.AND, ((BinaryExpr) or.right()).op());
    }

    // ==================================================================
    // 复杂表达式中的错误仍报 PARSER
    // ==================================================================

    @Test
    void unbalancedParenthesesReportsParserError() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class, () -> where("(a = 1"));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.getMessage().contains("expected [)]"), e.getMessage());
        assertTrue(e.getMessage().contains("unexpected"), e.getMessage());
    }

    @Test
    void missingRightOperandInDeepExpressionReportsParserError() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class,
                () -> where("((a + 1) * 2) > "));
        assertEquals(MiniDbException.Phase.PARSER, e.phase());
        assertTrue(e.getMessage().contains("unexpected"), e.getMessage());
        assertTrue(e.getMessage().contains("expected ["), e.getMessage());
    }

    // ==================================================================
    // 测试后反馈：逐条打印 WHERE 条件与解析出的表达式 AST，并写报告文件
    // ==================================================================

    @Test
    void feedbackReport() {
        ParserTestFeedback.Result result = ParserTestFeedback.report(
                "MiniDB Parser 复杂表达式反馈报告（SELECT * FROM t WHERE <表达式>）",
                "parser-expression-report.txt",
                expressionDemoCases());
        assertTrue(result.fail() == 0, "存在未通过的表达式演示用例: " + result.fail());
    }

    private static List<ParserTestFeedback.DemoCase> expressionDemoCases() {
        List<ParserTestFeedback.DemoCase> cases = new java.util.ArrayList<>();
        cases.add(expr("X1 深层括号", "(((a + 1) * 2) > 5)", false));
        cases.add(expr("X2 五层括号", "(((((a = 1)))))", false));
        cases.add(expr("X3 算术优先于比较", "a + 1 * 2 > 5", false));
        cases.add(expr("X4 乘除先于加减", "a * 2 >= b + 1", false));
        cases.add(expr("X5 左结合减法", "a - b - c = 0", false));
        cases.add(expr("X6 左结合除法", "a / b / c = 0", false));
        cases.add(expr("X7 三连 NOT", "NOT NOT NOT a = 1", false));
        cases.add(expr("X8 NOT 只作用于比较", "NOT a = 1 AND b = 2", false));
        cases.add(expr("X9 括号改变结合", "(a + b) * c = 0", false));
        cases.add(expr("X10 必须加括号的 OR/AND", "a = 1 OR b = 2 AND c = 3", false));
        cases.add(expr("X11 运算符大混合",
                "(a * 2 + b / 3 - c >= 1) AND NOT (d <= 4 OR e > 5)"
                        + " OR f == 6 AND g != 7 OR h = 8", false));
        cases.add(expr("X12 六种比较运算符", "a < 1 OR a <= 2 OR a > 3 OR a >= 4 OR a = 5 OR a != 6",
                false));
        cases.add(expr("E1 未闭合括号", "(a = 1", true));
        cases.add(expr("E2 缺右操作数", "((a + 1) * 2) > ", true));
        cases.add(expr("E3 悬挂 AND", "a = 1 AND ", true));
        return cases;
    }

    private static ParserTestFeedback.DemoCase expr(String name, String condition,
                                                    boolean expectError) {
        return new ParserTestFeedback.DemoCase(name, condition,
                ParserTestFeedback.Mode.WHERE_EXPR, expectError);
    }
}
