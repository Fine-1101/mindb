package com.minidb.planner;

import com.minidb.ast.InsertStmt;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.lexer.Lexer;
import com.minidb.parser.Parser;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
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
}
