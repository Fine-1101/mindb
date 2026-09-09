package com.minidb.plan;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.Literal;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PlanPrinter 测试：输出与手写期望字符串精确相等，六种节点全覆盖。
 */
class PlanPrinterTest {

    private static final Position P = new Position(1, 1);

    private static Expression eq(Expression left, Expression right) {
        return new BinaryExpr(left, BinaryOp.EQ, right, P);
    }

    @Test
    void seqScan() {
        assertEquals("(SeqScan users)", PlanPrinter.print(new SeqScan("users")));
    }

    @Test
    void filter() {
        Expression condition = eq(
                new ColumnRef(null, "a", P),
                new Literal(2, DataType.INT, P));
        assertEquals("(Filter (SeqScan users) (= a 2))",
                PlanPrinter.print(new Filter(new SeqScan("users"), condition)));
    }

    @Test
    void projectWithColumns() {
        Expression condition = eq(
                new ColumnRef(null, "a", P),
                new Literal(2, DataType.INT, P));
        assertEquals("(Project (Filter (SeqScan users) (= a 2)) [a, b])",
                PlanPrinter.print(new Project(new Filter(new SeqScan("users"), condition),
                        List.of("a", "b"))));
    }

    @Test
    void projectStar() {
        assertEquals("(Project (SeqScan users) *)",
                PlanPrinter.print(new Project(new SeqScan("users"), null)));
    }

    @Test
    void createTable() {
        assertEquals("(CreateTable users)",
                PlanPrinter.print(new CreateTablePlan(new TableDef("users", List.of(
                        new ColumnDef("id", DataType.INT, 0))))));
    }

    @Test
    void insert() {
        List<List<Expression>> rows = List.of(
                List.of(new Literal(1, DataType.INT, P),
                        new Literal("Tom", DataType.VARCHAR, P)),
                List.of(new Literal(2, DataType.INT, P),
                        new Literal("Alice", DataType.VARCHAR, P)));
        assertEquals("(Insert users [id, name] [(1, 'Tom'), (2, 'Alice')])",
                PlanPrinter.print(new InsertPlan("users", List.of("id", "name"), rows)));
    }

    @Test
    void deleteWithCondition() {
        Expression condition = eq(
                new ColumnRef(null, "a", P),
                new Literal(2, DataType.INT, P));
        assertEquals("(Delete users (= a 2))",
                PlanPrinter.print(new DeletePlan("users", condition)));
    }

    @Test
    void deleteAll() {
        assertEquals("(Delete users *)", PlanPrinter.print(new DeletePlan("users", null)));
    }

    @Test
    void complexExpression() {
        Expression condition = new BinaryExpr(
                eq(new ColumnRef(null, "a", P), new Literal(2, DataType.INT, P)),
                BinaryOp.AND,
                new BinaryExpr(
                        new BinaryExpr(new ColumnRef(null, "b", P),
                                BinaryOp.LT, new Literal(3, DataType.INT, P), P),
                        BinaryOp.OR,
                        new UnaryExpr(UnaryOp.NOT,
                                eq(new ColumnRef(null, "c", P), new Literal(4, DataType.INT, P)), P),
                        P),
                P);
        assertEquals("(Filter (SeqScan t) (AND (= a 2) (OR (< b 3) (NOT (= c 4)))))",
                PlanPrinter.print(new Filter(new SeqScan("t"), condition)));
    }

    @Test
    void qualifiedColumnNegAndFloat() {
        Expression condition = new BinaryExpr(
                new ColumnRef("t", "a", P),
                BinaryOp.GE,
                new UnaryExpr(UnaryOp.NEG, new Literal(1.5, DataType.FLOAT, P), P),
                P);
        assertEquals("(Filter (SeqScan t) (>= t.a (NEG 1.5)))",
                PlanPrinter.print(new Filter(new SeqScan("t"), condition)));
    }

    @Test
    void foldedBooleanCondition() {
        assertEquals("(Filter (SeqScan t) true)",
                PlanPrinter.print(new Filter(new SeqScan("t"),
                        new Literal(true, DataType.BOOLEAN, P))));
    }
}
