package com.minidb.integration;

import com.minidb.buffer.BufferPool;
import com.minidb.buffer.InMemoryBufferPool;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.engine.Engine;
import com.minidb.plan.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SeqScan 集成测试：插 3 行删中间 1 行 → scan 出 2 行且顺序正确；空表返回空集。
 */
class SeqScanTest {

    private Engine setupTable() throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);
        engine.execute(new CreateTablePlan(
                new TableDef("t", List.of(
                        new ColumnDef("id", DataType.INT, 0),
                        new ColumnDef("val", DataType.VARCHAR, 50)))));
        return engine;
    }

    @Test
    void testScanAfterDeleteMiddle() throws MiniDbException {
        Engine engine = setupTable();

        // 插入 3 行
        engine.execute(new InsertPlan("t", List.of("id", "val"), List.of(
                List.of(lit(1), lit("A")),
                List.of(lit(2), lit("B")),
                List.of(lit(3), lit("C"))
        )));

        // 删除中间行 (id=2)
        engine.execute(new DeletePlan("t",
                new com.minidb.ast.BinaryExpr(
                        new com.minidb.ast.ColumnRef(null, "id", null),
                        com.minidb.ast.BinaryOp.EQ,
                        lit(2), null)));

        // 查询全部
        PlanNode plan = new SeqScan("t");
        List<Object[]> results = engine.executeQuery(plan);

        assertEquals(2, results.size(), "删中间行后应剩 2 行");
        assertEquals(1, results.get(0)[0], "第 1 行 id=1");
        assertEquals("A", results.get(0)[1]);
        assertEquals(3, results.get(1)[0], "第 2 行 id=3");
        assertEquals("C", results.get(1)[1]);
    }

    @Test
    void testEmptyTableScan() throws MiniDbException {
        Engine engine = setupTable();

        // 不插入任何行，直接查询
        PlanNode plan = new SeqScan("t");
        List<Object[]> results = engine.executeQuery(plan);

        assertTrue(results.isEmpty(), "空表应返回空集");
    }

    @Test
    void testScanPreservesOrder() throws MiniDbException {
        Engine engine = setupTable();

        engine.execute(new InsertPlan("t", List.of("id", "val"), List.of(
                List.of(lit(10), lit("X")),
                List.of(lit(20), lit("Y")),
                List.of(lit(30), lit("Z"))
        )));

        PlanNode plan = new SeqScan("t");
        List<Object[]> results = engine.executeQuery(plan);

        assertEquals(3, results.size());
        assertEquals(10, results.get(0)[0]);
        assertEquals(20, results.get(1)[0]);
        assertEquals(30, results.get(2)[0]);
    }

    private com.minidb.ast.Literal lit(Object v) {
        DataType type = v instanceof Integer ? DataType.INT : DataType.VARCHAR;
        return new com.minidb.ast.Literal(v, type, null);
    }
}
