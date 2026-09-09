package com.minidb.integration;

import com.minidb.buffer.BufferPool;
import com.minidb.buffer.InMemoryBufferPool;
import com.minidb.catalog.*;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.engine.Engine;
import com.minidb.plan.*;
import com.minidb.storage.MemoryPage;
import com.minidb.storage.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DELETE 集成测试：WHERE 命中 2/3 行 → 剩余行断言；无 WHERE 全删。
 */
class DeleteIntegrationTest {

    private Engine setupWithData() throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);
        engine.execute(new CreateTablePlan(
                new TableDef("t", List.of(
                        new ColumnDef("id", DataType.INT, 0),
                        new ColumnDef("score", DataType.FLOAT, 0)))));
        engine.execute(new InsertPlan("t", List.of("id", "score"), List.of(
                List.of(litI(1), litF(90.0)),
                List.of(litI(2), litF(50.0)),
                List.of(litI(3), litF(60.0))
        )));
        return engine;
    }

    @Test
    void testDeleteWhereHits2Of3() throws MiniDbException {
        Engine engine = setupWithData();

        // DELETE WHERE score < 70 → 命中 id=2(50.0), id=3(60.0)
        engine.execute(new DeletePlan("t",
                binExpr(colRef("score"), com.minidb.ast.BinaryOp.LT, litF(70.0))));

        // 查询剩余
        List<Object[]> results = engine.executeQuery(new SeqScan("t"));
        assertEquals(1, results.size(), "应剩 1 行");
        assertEquals(1, results.get(0)[0], "剩余行 id=1");
    }

    @Test
    void testDeleteNoWhereAllGone() throws MiniDbException {
        Engine engine = setupWithData();

        // DELETE FROM t（无 WHERE → 全删）
        engine.execute(new DeletePlan("t", null));

        List<Object[]> results = engine.executeQuery(new SeqScan("t"));
        assertTrue(results.isEmpty(), "全删后应无行");
    }

    @Test
    void testDeleteWithEQCondition() throws MiniDbException {
        Engine engine = setupWithData();

        // DELETE WHERE id = 2
        engine.execute(new DeletePlan("t",
                binExpr(colRef("id"), com.minidb.ast.BinaryOp.EQ, litI(2))));

        List<Object[]> results = engine.executeQuery(new SeqScan("t"));
        assertEquals(2, results.size(), "删 1 行后应剩 2 行");
        assertEquals(1, results.get(0)[0]);
        assertEquals(3, results.get(1)[0]);
    }

    @Test
    void testDeleteWithORCondition() throws MiniDbException {
        Engine engine = setupWithData();

        // DELETE WHERE id = 1 OR id = 3
        engine.execute(new DeletePlan("t",
                binExpr(
                        binExpr(colRef("id"), com.minidb.ast.BinaryOp.EQ, litI(1)),
                        com.minidb.ast.BinaryOp.OR,
                        binExpr(colRef("id"), com.minidb.ast.BinaryOp.EQ, litI(3)))));

        List<Object[]> results = engine.executeQuery(new SeqScan("t"));
        assertEquals(1, results.size(), "删 2 行后应剩 1 行");
        assertEquals(2, results.get(0)[0]);
    }

    // ============================================================
    // 辅助
    // ============================================================

    private com.minidb.ast.Literal litI(int v) {
        return new com.minidb.ast.Literal(v, DataType.INT, null);
    }
    private com.minidb.ast.Literal litF(double v) {
        return new com.minidb.ast.Literal(v, DataType.FLOAT, null);
    }
    private com.minidb.ast.ColumnRef colRef(String name) {
        return new com.minidb.ast.ColumnRef(null, name, null);
    }
    private com.minidb.ast.BinaryExpr binExpr(
            com.minidb.ast.Expression l, com.minidb.ast.BinaryOp op, com.minidb.ast.Expression r) {
        return new com.minidb.ast.BinaryExpr(l, op, r, null);
    }
}
