package com.minidb.planner;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.Literal;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.ast.UnaryExpr;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.lexer.Lexer;
import com.minidb.parser.Parser;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Optimizer 测试：常量折叠（算术/比较/NOT/NEG）、布尔化简四方向、
 * 含列引用子树不折叠、Filter(TRUE) 消除（P1）、幂等、DELETE 条件折叠。
 * 期望值尽量直接取原 AST 子树（化简返回原子树，位置天然一致）。
 */
class OptimizerTest {

    private final Planner planner = new Planner(new MemoryCatalog());
    private final Optimizer optimizer = new Optimizer();

    private static Statement parse(String sql) throws MiniDbException {
        return new Parser().parse(new Lexer().tokenize(sql));
    }

    private static Filter filterOf(PlanNode plan) {
        return (Filter) ((Project) plan).child();
    }

    @Test
    void foldConstantComparison() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE 1 = 1 AND a = 2;");
        BinaryExpr and = (BinaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(and.right(), filter.condition());
    }

    @Test
    void foldArithmeticInsideComparison() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE 1 + 2 = 3 AND a = 2;");
        BinaryExpr and = (BinaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(and.right(), filter.condition());
    }

    @Test
    void columnSubtreeNotFolded() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE a + 1 = 2;");
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertSame(((SelectStmt) stmt).where(), filter.condition());
    }

    @Test
    void andTrueSimplifiesToLeft() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE a AND 1 = 1;");
        BinaryExpr and = (BinaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(and.left(), filter.condition());
    }

    @Test
    void andFalseSimplifiesToFalseLiteral() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE a AND 1 = 2;");
        BinaryExpr and = (BinaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(new Literal(false, DataType.BOOLEAN, and.pos()), filter.condition());
    }

    @Test
    void orFalseSimplifiesToLeft() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE a OR 1 = 2;");
        BinaryExpr or = (BinaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(or.left(), filter.condition());
    }

    @Test
    void orTrueFoldsAwayFilter() throws MiniDbException {
        // a OR TRUE → TRUE，且整条件为 TRUE 时 Filter 被 P1 规则消除（规则串联）；
        // 规则4 标注 SeqScan 引用列集 [a]
        PlanNode plan = planner.plan(parse("SELECT a FROM t WHERE a OR 1 = 1;"));
        assertEquals(new Project(new SeqScan("t", List.of("a")), List.of("a")), optimizer.optimize(plan));
    }

    @Test
    void trueFilterRemoved() throws MiniDbException {
        PlanNode plan = planner.plan(parse("SELECT a FROM t WHERE 1 = 1;"));
        assertEquals(new Project(new SeqScan("t", List.of("a")), List.of("a")), optimizer.optimize(plan));
    }

    @Test
    void negLiteralFoldedThenFilterRemoved() throws MiniDbException {
        PlanNode plan = planner.plan(parse("SELECT a FROM t WHERE -1 < 0;"));
        assertEquals(new Project(new SeqScan("t", List.of("a")), List.of("a")), optimizer.optimize(plan));
    }

    @Test
    void notTrueFoldedToFalse() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE NOT 1 = 1;");
        UnaryExpr not = (UnaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(new Literal(false, DataType.BOOLEAN, not.pos()), filter.condition());
    }

    @Test
    void idempotentOnPlainCondition() throws MiniDbException {
        Statement stmt = parse("SELECT a FROM t WHERE a = 2;");
        PlanNode plan = planner.plan(stmt);
        // 条件本身无可折叠（assertEquals 比较），规则4 标注 SeqScan cols=[a] 为唯一差异
        assertEquals(new Project(new Filter(new SeqScan("t", List.of("a")), ((SelectStmt) stmt).where()),
                List.of("a")), optimizer.optimize(plan));
        assertSame(((SelectStmt) stmt).where(), filterOf(plan).condition());
    }

    @Test
    void deleteConditionFolded() throws MiniDbException {
        Statement stmt = parse("DELETE FROM t WHERE 1 = 1 AND a = 2;");
        BinaryExpr and = (BinaryExpr) ((DeleteStmt) stmt).where();
        DeletePlan optimized = (DeletePlan) optimizer.optimize(planner.plan(stmt));
        assertEquals(and.right(), optimized.condition());
    }

    // ==================================================================
    // P0 判定补测：除零不折叠 / NOT NOT 化简 / SELECT * 透传 Project 消除
    // ==================================================================

    @Test
    void divByZeroIntNotFoldedAndNotCrash() throws MiniDbException {
        // WHERE 1/0 = 1 AND a = 2 —— INT 除零不得抛 ArithmeticException，原样保留
        Statement stmt = parse("SELECT a FROM t WHERE 1 / 0 = 1 AND a = 2;");
        PlanNode optimized = assertDoesNotThrow(() -> optimizer.optimize(planner.plan(stmt)));
        assertSame(((SelectStmt) stmt).where(), filterOf(optimized).condition());
    }

    @Test
    void divByZeroFloatNotFolded() throws MiniDbException {
        // WHERE 1.0/0.0 = 1 —— FLOAT 除零保守不折叠（不产生 Infinity 字面量）
        Statement stmt = parse("SELECT a FROM t WHERE 1.0 / 0.0 = 1 AND a = 2;");
        PlanNode optimized = assertDoesNotThrow(() -> optimizer.optimize(planner.plan(stmt)));
        assertSame(((SelectStmt) stmt).where(), filterOf(optimized).condition());
    }

    @Test
    void notNotSimplifiesToOperand() throws MiniDbException {
        // WHERE NOT NOT a = 1 → a = 1（外层 NOT.operand() 是内层 NOT，化简取内层的操作数）
        Statement stmt = parse("SELECT a FROM t WHERE NOT NOT a = 1;");
        UnaryExpr outerNot = (UnaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(((UnaryExpr) outerNot.operand()).operand(), filter.condition());
    }

    @Test
    void notNotAndTrueSimplifiesToCondition() throws MiniDbException {
        // WHERE NOT NOT a = 1 AND 1 = 1 → a = 1（D3 判定原用例）
        Statement stmt = parse("SELECT a FROM t WHERE NOT NOT a = 1 AND 1 = 1;");
        BinaryExpr and = (BinaryExpr) ((SelectStmt) stmt).where();
        Filter filter = filterOf(optimizer.optimize(planner.plan(stmt)));
        assertEquals(((UnaryExpr) ((UnaryExpr) and.left()).operand()).operand(), filter.condition());
    }

    @Test
    void selectStarPassthroughProjectRemoved() throws MiniDbException {
        // SELECT * 且条件折叠为 TRUE：Filter 与透传 Project 一并消除，只剩 SeqScan
        PlanNode plan = planner.plan(parse("SELECT * FROM t WHERE 1 = 1;"));
        assertEquals(new SeqScan("t"), optimizer.optimize(plan));
    }

    @Test
    void selectStarWithoutWhereProjectRemoved() throws MiniDbException {
        // SELECT * 无 WHERE：透传 Project 消除
        PlanNode plan = planner.plan(parse("SELECT * FROM t;"));
        assertEquals(new SeqScan("t"), optimizer.optimize(plan));
    }

    @Test
    void selectStarWithColumnConditionKeepsFilter() throws MiniDbException {
        // SELECT * WHERE a = 1：Project 消除但 Filter 保留（条件非恒真）
        Statement stmt = parse("SELECT * FROM t WHERE a = 1;");
        PlanNode optimized = optimizer.optimize(planner.plan(stmt));
        assertEquals(new Filter(new SeqScan("t"), ((SelectStmt) stmt).where()), optimized);
    }
}
