package com.minidb.integration;

import com.minidb.ast.*;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.engine.ExpressionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表达式求值器测试：6 个比较 op × AND/OR/NOT 一正一反；短路求值。
 */
class ExpressionEvaluatorTest {

    // 列映射：a→0, b→1
    private static final Map<String, Integer> COLS = Map.of("a", 0, "b", 1);
    private static final Position POS = new Position(1, 1);

    // ============================================================
    // 6 个比较运算符
    // ============================================================

    @Test void testEQ_true() throws MiniDbException {
        Object[] row = {5, 5};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.EQ, col("b")), row));
    }
    @Test void testEQ_false() throws MiniDbException {
        Object[] row = {5, 6};
        assertEquals(false, eval(binExpr(col("a"), BinaryOp.EQ, col("b")), row));
    }

    @Test void testNE_true() throws MiniDbException {
        Object[] row = {1, 2};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.NE, col("b")), row));
    }
    @Test void testNE_false() throws MiniDbException {
        Object[] row = {3, 3};
        assertEquals(false, eval(binExpr(col("a"), BinaryOp.NE, col("b")), row));
    }

    @Test void testLT_true() throws MiniDbException {
        Object[] row = {1, 2};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.LT, col("b")), row));
    }
    @Test void testLT_false() throws MiniDbException {
        Object[] row = {2, 1};
        assertEquals(false, eval(binExpr(col("a"), BinaryOp.LT, col("b")), row));
    }

    @Test void testLE_true() throws MiniDbException {
        Object[] row = {2, 2};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.LE, col("b")), row));
    }
    @Test void testLE_false() throws MiniDbException {
        Object[] row = {3, 2};
        assertEquals(false, eval(binExpr(col("a"), BinaryOp.LE, col("b")), row));
    }

    @Test void testGT_true() throws MiniDbException {
        Object[] row = {10, 5};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.GT, col("b")), row));
    }
    @Test void testGT_false() throws MiniDbException {
        Object[] row = {5, 10};
        assertEquals(false, eval(binExpr(col("a"), BinaryOp.GT, col("b")), row));
    }

    @Test void testGE_true() throws MiniDbException {
        Object[] row = {5, 5};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.GE, col("b")), row));
    }
    @Test void testGE_false() throws MiniDbException {
        Object[] row = {4, 5};
        assertEquals(false, eval(binExpr(col("a"), BinaryOp.GE, col("b")), row));
    }

    // ============================================================
    // AND / OR / NOT 一正一反
    // ============================================================

    @Test void testAND_trueTrue() throws MiniDbException {
        // a > 0 AND b > 0, row = {1, 1}
        Expression cond = binExpr(
                binExpr(col("a"), BinaryOp.GT, lit(0)),
                BinaryOp.AND,
                binExpr(col("b"), BinaryOp.GT, lit(0)));
        assertEquals(true, eval(cond, new Object[]{1, 1}));
    }

    @Test void testAND_falseLeft() throws MiniDbException {
        // a > 0 AND b > 0, row = {0, 1}
        Expression cond = binExpr(
                binExpr(col("a"), BinaryOp.GT, lit(0)),
                BinaryOp.AND,
                binExpr(col("b"), BinaryOp.GT, lit(0)));
        assertEquals(false, eval(cond, new Object[]{0, 1}));
    }

    @Test void testOR_trueLeft() throws MiniDbException {
        Expression cond = binExpr(
                binExpr(col("a"), BinaryOp.EQ, lit(1)),
                BinaryOp.OR,
                binExpr(col("b"), BinaryOp.EQ, lit(2)));
        assertEquals(true, eval(cond, new Object[]{1, 99}));
    }

    @Test void testOR_falseBoth() throws MiniDbException {
        Expression cond = binExpr(
                binExpr(col("a"), BinaryOp.EQ, lit(1)),
                BinaryOp.OR,
                binExpr(col("b"), BinaryOp.EQ, lit(2)));
        assertEquals(false, eval(cond, new Object[]{0, 99}));
    }

    @Test void testNOT_true() throws MiniDbException {
        Expression cond = new UnaryExpr(UnaryOp.NOT,
                binExpr(col("a"), BinaryOp.EQ, lit(1)), POS);
        // a != 1 → NOT true → false
        assertEquals(false, eval(cond, new Object[]{1, 0}));
    }

    @Test void testNOT_false() throws MiniDbException {
        Expression cond = new UnaryExpr(UnaryOp.NOT,
                binExpr(col("a"), BinaryOp.EQ, lit(1)), POS);
        // a == 2 → NOT false → true
        assertEquals(true, eval(cond, new Object[]{2, 0}));
    }

    // ============================================================
    // 短路求值：AND 左假右不判
    // ============================================================

    @Test void testShortCircuit_AND_leftFalseRightNotEvaluated() throws MiniDbException {
        // a > 0 AND (b / 0 > 0) — 如果右被求值会除零报错
        // 但 b/0 需要 b 列，我们用 b 列除零
        // 简化：a > 0 AND b > 0, a=0 → 左侧 false，右侧不判
        // 我们用一个会抛异常的右侧来验证
        Expression right = binExpr(col("nonexistent"), BinaryOp.EQ, lit(1));  // 求值会抛异常
        Expression cond = binExpr(
                binExpr(col("a"), BinaryOp.GT, lit(10)),  // false when a=0
                BinaryOp.AND,
                right);
        // a=0 → 左假 → 短路 → 不判右 → 返回 false
        assertEquals(false, eval(cond, new Object[]{0, 0}));
    }

    @Test void testShortCircuit_OR_leftTrueRightNotEvaluated() throws MiniDbException {
        Expression right = binExpr(col("nonexistent"), BinaryOp.EQ, lit(1));
        Expression cond = binExpr(
                binExpr(col("a"), BinaryOp.EQ, lit(1)),  // true when a=1
                BinaryOp.OR,
                right);
        // a=1 → 左真 → 短路 → 不判右 → 返回 true
        assertEquals(true, eval(cond, new Object[]{1, 0}));
    }

    // ============================================================
    // 跨类型比较：INT vs FLOAT
    // ============================================================

    @Test void testCrossTypeComparison() throws MiniDbException {
        Object[] row = {5, 5.0};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.EQ, col("b")), row));
    }

    @Test void testVarcharComparison() throws MiniDbException {
        Object[] row = {"abc", "def"};
        assertEquals(true, eval(binExpr(col("a"), BinaryOp.LT, col("b")), row));
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private Object eval(Expression expr, Object[] row) throws MiniDbException {
        return ExpressionEvaluator.evaluate(expr, COLS, row);
    }

    private ColumnRef col(String name) {
        return new ColumnRef(null, name, POS);
    }

    private Literal lit(Object value) {
        DataType type = switch (value) {
            case Integer i -> DataType.INT;
            case Double d -> DataType.FLOAT;
            case String s -> DataType.VARCHAR;
            default -> DataType.INT;
        };
        return new Literal(value, type, POS);
    }

    private BinaryExpr binExpr(Expression left, BinaryOp op, Expression right) {
        return new BinaryExpr(left, op, right, POS);
    }
}
