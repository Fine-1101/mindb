package com.minidb.integration;

import com.minidb.MiniDB;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * 现场复现：.trace 五段输出 + demo.sql 全链——验收时直接跑此测试即可重现。
 */
class LiveReproductionTest {

    @Test
    void liveReproduce_traceFiveSegments() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            MiniDB db = new MiniDB();
            db.executeSql("CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT)");
            db.executeSql("INSERT INTO student VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0)");
            db.traceSql("SELECT name FROM student WHERE score >= 90.0");
        } finally {
            System.setOut(originalOut);
        }
        String output = baos.toString();
        System.out.println("=== .trace 五段输出现场复现 ===");
        System.out.println(output);
        System.out.println("=== END ===");

        // 逐段断言（具体值，非"能跑"）
        var lines = output.split("\n");

        // ── Tokens ──
        assertSection(output, "── Tokens ──");
        assertContains(output, "KW_SELECT", "Tokens 段应含 KW_SELECT");
        assertContains(output, "KW_FROM", "Tokens 段应含 KW_FROM");
        assertContains(output, "KW_WHERE", "Tokens 段应含 KW_WHERE");

        // ── AST ──
        assertSection(output, "── AST ──");
        assertContains(output, "(select", "AST 段应含 S-expression");
        assertContains(output, "(from student)", "AST 段应含 from 子句");
        assertContains(output, "(where", "AST 段应含 where 子句");

        // ── Plan(优化前) ──
        assertSection(output, "── Plan(优化前) ──");
        assertContains(output, "Project[name]", "Plan 应含 Project[name]");
        assertContains(output, "Filter[", "Plan 应含 Filter");
        assertContains(output, "SeqScan(student)", "Plan 应含 SeqScan(student)");

        // ── Plan(优化后) ──
        assertSection(output, "── Plan(优化后) ──");

        // ── Result ──
        assertSection(output, "── Result ──");
        assertContains(output, "name", "Result 应含表头 name");
        assertContains(output, "Tom", "Result 应含 Tom (score=90.5 >= 90.0)");
        assertContains(output, "(1 行)", "Result 应为 1 行");
    }

    @Test
    void liveReproduce_errorPhaseLineCol() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            MiniDB db = new MiniDB();
            // 正常语句
            db.executeSql("CREATE TABLE t (id INT)");
            db.executeSql("INSERT INTO t VALUES (1)");
            // LEXER 错误
            db.executeSql("SELECT 'unterminated");
            // PARSER 错误
            db.executeSql("SELECT FROM t");
            // SEMANTIC 错误
            db.executeSql("SELECT * FROM nonexistent");
            // 继续执行正常语句（错误后不中断）
            db.executeSql("SELECT * FROM t");
        } finally {
            System.setOut(originalOut);
        }
        String output = baos.toString();
        System.out.println("=== 错误分类 + 继续执行现场复现 ===");
        System.out.println(output);
        System.out.println("=== END ===");

        // 断言三种错误阶段均按 [阶段 错误 @ 行:列] 格式打印
        assertContains(output, "[LEXER 错误", "应含 LEXER 错误");
        assertContains(output, "[PARSER 错误", "应含 PARSER 错误");
        assertContains(output, "[SEMANTIC 错误", "应含 SEMANTIC 错误");

        // 断言错误后继续执行：正常 SELECT 结果出现
        assertContains(output, "(1 行)", "错误后应继续执行，SELECT 出 1 行");
    }

    @Test
    void liveReproduce_isDirtyContract() {
        var page = new com.minidb.storage.MemoryPage(0);
        // 新建页干净
        org.junit.jupiter.api.Assertions.assertFalse(page.isDirty(), "新建页应干净");
        // insertRow 后置脏
        page.insertRow(new byte[]{1, 2, 3});
        org.junit.jupiter.api.Assertions.assertTrue(page.isDirty(), "insertRow 后应脏");
        // markClean 后干净
        page.markClean();
        org.junit.jupiter.api.Assertions.assertFalse(page.isDirty(), "markClean 后应干净");
        // deleteRow 后置脏
        page.deleteRow(0);
        org.junit.jupiter.api.Assertions.assertTrue(page.isDirty(), "deleteRow 后应脏");
        System.out.println("=== isDirty 契约现场复现 ===");
        System.out.println("new→clean, insert→dirty, clear→clean, delete→dirty ✓");
    }

    @Test
    void liveReproduce_seqScanSkipsNullSlots() {
        var catalog = new com.minidb.catalog.MemoryCatalog();
        var pool = new com.minidb.buffer.InMemoryBufferPool();
        var engine = new com.minidb.engine.Engine(catalog, pool);
        try {
            engine.execute(new com.minidb.plan.CreateTablePlan(
                    new com.minidb.catalog.TableDef("t", java.util.List.of(
                            new com.minidb.catalog.ColumnDef("id", com.minidb.common.DataType.INT, 0)))));
            engine.execute(new com.minidb.plan.InsertPlan("t", java.util.List.of("id"), java.util.List.of(
                    java.util.List.of(new com.minidb.ast.Literal(10, com.minidb.common.DataType.INT, null)),
                    java.util.List.of(new com.minidb.ast.Literal(20, com.minidb.common.DataType.INT, null)),
                    java.util.List.of(new com.minidb.ast.Literal(30, com.minidb.common.DataType.INT, null))
            )));
            // 删中间行
            engine.execute(new com.minidb.plan.DeletePlan("t",
                    new com.minidb.ast.BinaryExpr(
                            new com.minidb.ast.ColumnRef(null, "id", null),
                            com.minidb.ast.BinaryOp.EQ,
                            new com.minidb.ast.Literal(20, com.minidb.common.DataType.INT, null), null)));
            // scan
            var results = engine.executeQuery(new com.minidb.plan.SeqScan("t"));
            System.out.println("=== SeqScan 跳 null 槽现场复现 ===");
            System.out.println("插 3 行删中间 → scan 出 " + results.size() + " 行");
            for (Object[] row : results) {
                System.out.println("  id=" + row[0]);
            }
            org.junit.jupiter.api.Assertions.assertEquals(2, results.size(), "应跳过 null 槽剩 2 行");
            org.junit.jupiter.api.Assertions.assertEquals(10, results.get(0)[0], "第 1 行 id=10");
            org.junit.jupiter.api.Assertions.assertEquals(30, results.get(1)[0], "第 2 行 id=30");
        } catch (com.minidb.common.MiniDbException e) {
            org.junit.jupiter.api.Assertions.fail(e.getMessage());
        }
    }

    // ============================================================
    // 断言辅助
    // ============================================================

    private void assertSection(String output, String section) {
        org.junit.jupiter.api.Assertions.assertTrue(output.contains(section),
                "应包含段落: " + section);
    }

    private void assertContains(String output, String expected, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(output.contains(expected), msg);
    }
}
