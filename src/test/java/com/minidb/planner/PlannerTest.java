package com.minidb.planner;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.FuncCall;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.Literal;
import com.minidb.ast.OrderKey;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.SetClause;
import com.minidb.ast.Statement;
import com.minidb.ast.UpdateStmt;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Lexer;
import com.minidb.parser.Parser;
import com.minidb.plan.AggregatePlan;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.JoinPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import com.minidb.plan.SortKey;
import com.minidb.plan.SortPlan;
import com.minidb.plan.UpdatePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Planner 测试：四类语句的 AST→Plan 结构断言（record equals 整树）。
 * SELECT * 走 null 透传；无 WHERE 无 Filter 节点；INSERT 列序展开。
 */
class PlannerTest {

    private final Catalog catalog = new MemoryCatalog();
    private final Planner planner = new Planner(catalog);

    private static Statement parse(String sql) throws MiniDbException {
        return new Parser().parse(new Lexer().tokenize(sql));
    }

    @Test
    void createTable() throws MiniDbException {
        PlanNode plan = planner.plan(parse("CREATE TABLE users (id INT, name VARCHAR(32));"));
        assertEquals(new CreateTablePlan(new TableDef("users", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 32)))), plan);
    }

    @Test
    void selectWithColumnsAndWhere() throws MiniDbException {
        String sql = "SELECT a, b FROM t WHERE a = 1;";
        Statement stmt = parse(sql);
        PlanNode plan = planner.plan(stmt);
        assertEquals(new Project(
                new Filter(new SeqScan("t"), ((SelectStmt) stmt).where()),
                List.of("a", "b")), plan);
    }

    @Test
    void selectStarPassesNullColumns() throws MiniDbException {
        PlanNode plan = planner.plan(parse("SELECT * FROM t;"));
        assertEquals(new Project(new SeqScan("t"), null), plan);
    }

    @Test
    void selectWithoutWhereHasNoFilter() throws MiniDbException {
        PlanNode plan = planner.plan(parse("SELECT a FROM t;"));
        assertEquals(new Project(new SeqScan("t"), List.of("a")), plan);
    }

    @Test
    void insertWithSpecifiedColumnsKeepsWrittenOrder() throws MiniDbException {
        String sql = "INSERT INTO t (b, a) VALUES (1, 2);";
        InsertStmt insert = (InsertStmt) parse(sql);
        PlanNode plan = planner.plan(insert);
        assertEquals(new InsertPlan("t", List.of("b", "a"), insert.rows()), plan);
    }

    @Test
    void insertMultipleRowsKeptInOrder() throws MiniDbException {
        // 多行 INSERT：rows 原样进计划，行序与行数不变
        String sql = "INSERT INTO t (b, a) VALUES (1, 'x'), (2, 'y'), (3, 'z');";
        InsertStmt insert = (InsertStmt) parse(sql);
        PlanNode plan = planner.plan(insert);
        assertEquals(new InsertPlan("t", List.of("b", "a"), insert.rows()), plan);
        assertEquals(3, ((InsertPlan) plan).rows().size());
    }

    @Test
    void insertWithoutColumnsUsesCatalogOrder() throws MiniDbException {
        catalog.createTable(new TableDef("t", List.of(
                new ColumnDef("a", DataType.INT, 0),
                new ColumnDef("b", DataType.VARCHAR, 8))));
        InsertStmt insert = (InsertStmt) parse("INSERT INTO t VALUES (1, 'x');");
        PlanNode plan = planner.plan(insert);
        assertEquals(new InsertPlan("t", List.of("a", "b"), insert.rows()), plan);
    }

    @Test
    void deleteWithWhere() throws MiniDbException {
        Statement stmt = parse("DELETE FROM t WHERE a = 1;");
        PlanNode plan = planner.plan(stmt);
        assertEquals(new DeletePlan("t", ((com.minidb.ast.DeleteStmt) stmt).where()), plan);
    }

    @Test
    void deleteWithoutWhereIsConditionNull() throws MiniDbException {
        PlanNode plan = planner.plan(parse("DELETE FROM t;"));
        assertEquals(new DeletePlan("t", null), plan);
    }

    // ==================================================================
    // D5：UPDATE / ORDER BY / GROUP BY / JOIN（直构 AST——Parser 语法由 A 线合入）
    // ==================================================================

    private static Position p(int line, int col) {
        return new Position(line, col);
    }

    @Test
    void updateWithWhere() throws MiniDbException {
        // UPDATE t SET a = 2 WHERE b = 3
        UpdateStmt stmt = new UpdateStmt("t",
                List.of(new SetClause(new ColumnRef(null, "a", p(1, 16)),
                        new Literal(2, DataType.INT, p(1, 21)))),
                new BinaryExpr(new ColumnRef(null, "b", p(1, 32)), BinaryOp.EQ,
                        new Literal(3, DataType.INT, p(1, 38)), p(1, 32)),
                p(1, 8));
        assertEquals(new UpdatePlan("t", stmt.sets(), stmt.where()), planner.plan(stmt));
    }

    @Test
    void updateWithoutWhereIsConditionNull() throws MiniDbException {
        // UPDATE t SET a = 2
        UpdateStmt stmt = new UpdateStmt("t",
                List.of(new SetClause(new ColumnRef(null, "a", p(1, 16)),
                        new Literal(2, DataType.INT, p(1, 21)))),
                null, p(1, 8));
        assertEquals(new UpdatePlan("t", stmt.sets(), null), planner.plan(stmt));
    }

    @Test
    void orderByWrapsOutermostOverProject() throws MiniDbException {
        // SELECT a FROM t WHERE b = 1 ORDER BY a DESC —— Sort 最外层（DISTINCT 之后输出前）
        SelectStmt stmt = new SelectStmt(List.of(new ColumnRef(null, "a", p(1, 8))), null,
                "t",
                new BinaryExpr(new ColumnRef(null, "b", p(1, 27)), BinaryOp.EQ,
                        new Literal(1, DataType.INT, p(1, 33)), p(1, 27)),
                false, null,
                List.of(new OrderKey(new ColumnRef(null, "a", p(1, 40)), false)),
                null, null, p(1, 18));
        assertEquals(new SortPlan(
                new Project(new Filter(new SeqScan("t"), stmt.where()), List.of("a")),
                List.of(new SortKey("a", false))), planner.plan(stmt));
    }

    @Test
    void groupByBuildsProjectOverAggregate() throws MiniDbException {
        // SELECT a, COUNT(*) FROM t GROUP BY a —— 拍板 12：Project(AggregatePlan) 重排形态，
        // AggregatePlan 输出行 = [组键...] ++ [聚合值...]，顶层 Project 按 SELECT 书写序重排命名
        SelectStmt stmt = new SelectStmt(
                List.of(new ColumnRef(null, "a", p(1, 8))),
                List.of(new FuncCall("COUNT", null, p(1, 12))),
                "t", null, false,
                List.of(new ColumnRef(null, "a", p(1, 34))),
                null, null, null, p(1, 24));
        assertEquals(new Project(
                new AggregatePlan(new SeqScan("t"), stmt.aggregates(), List.of("a")),
                List.of("a", "COUNT(*)"), false), planner.plan(stmt));
    }

    @Test
    void groupByWithWhereAndOrderByFullStack() throws MiniDbException {
        // SELECT a, COUNT(*) FROM t WHERE b = 1 GROUP BY a ORDER BY a ASC —— 五层全叠加
        SelectStmt stmt = new SelectStmt(
                List.of(new ColumnRef(null, "a", p(1, 8))),
                List.of(new FuncCall("COUNT", null, p(1, 12))),
                "t",
                new BinaryExpr(new ColumnRef(null, "b", p(1, 34)), BinaryOp.EQ,
                        new Literal(1, DataType.INT, p(1, 40)), p(1, 34)),
                false,
                List.of(new ColumnRef(null, "a", p(1, 49))),
                List.of(new OrderKey(new ColumnRef(null, "a", p(1, 62)), true)),
                null, null, p(1, 26));
        assertEquals(new SortPlan(
                new Project(
                        new AggregatePlan(new Filter(new SeqScan("t"), stmt.where()),
                                stmt.aggregates(), List.of("a")),
                        List.of("a", "COUNT(*)"), false),
                List.of(new SortKey("a", true))), planner.plan(stmt));
    }

    @Test
    void joinWithWhereStacksFilterOverJoin() throws MiniDbException {
        // SELECT a FROM t JOIN u ON t.id = u.id WHERE t.a = 1 —— JOIN 数据源 + WHERE 叠加
        SelectStmt stmt = new SelectStmt(List.of(new ColumnRef(null, "a", p(1, 8))), null,
                "t",
                new BinaryExpr(new ColumnRef("t", "a", p(1, 45)), BinaryOp.EQ,
                        new Literal(1, DataType.INT, p(1, 51)), p(1, 45)),
                false, null, null, "u",
                new BinaryExpr(new ColumnRef("t", "id", p(1, 27)), BinaryOp.EQ,
                        new ColumnRef("u", "id", p(1, 36)), p(1, 27)),
                p(1, 18));
        assertEquals(new Project(
                new Filter(new JoinPlan(new SeqScan("t"), new SeqScan("u"), stmt.joinOn()),
                        stmt.where()),
                List.of("a")), planner.plan(stmt));
    }
}
