package com.minidb.integration;

import com.minidb.MiniDB;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合 + DISTINCT 端到端测试（D4）：五函数 / 空表语义 / WHERE 过滤 / DISTINCT 去重 /
 * 语义错误经 CLI 反馈 / .trace 五段含聚合节点。
 */
class AggregateEndToEndTest {

    /** 捕获 stdout 执行一段 SQL，返回输出。 */
    private String run(MiniDB db, String sql) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            db.executeSql(sql);
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString();
    }

    /** 建表 student(id INT, name VARCHAR(50), score FLOAT) 并插 3 行。 */
    private MiniDB studentDb() {
        MiniDB db = new MiniDB();
        run(db, "CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT)");
        run(db, "INSERT INTO student VALUES (1, 'Tom', 90.5)");
        run(db, "INSERT INTO student VALUES (2, 'Alice', 85.0)");
        run(db, "INSERT INTO student VALUES (3, 'Bob', 90.0)");
        return db;
    }

    @Test
    void allScalarAggregatesEndToEnd() {
        MiniDB db = studentDb();
        String out = run(db, "SELECT COUNT(*), SUM(id), AVG(score), MIN(name), MAX(name) FROM student");
        // 表头 = 函数书写形式（CLI 拍板：AggregateExecutor + CLI 表头）
        assertTrue(out.contains("COUNT(*)"), "表头应含 COUNT(*)，实际: " + out);
        assertTrue(out.contains("SUM(id)"));
        assertTrue(out.contains("AVG(score)"));
        assertTrue(out.contains("MIN(name)"));
        assertTrue(out.contains("MAX(name)"));
        // 值：3 行 / 1+2+3=6 / (90.5+85.0+90.0)/3=88.5 / Alice / Tom
        assertTrue(out.contains("6"), "SUM(id)=6");
        assertTrue(out.contains("88.5"), "AVG(score)=88.5");
        assertTrue(out.contains("Alice"), "MIN(name)=Alice");
        assertTrue(out.contains("Tom"), "MAX(name)=Tom");
    }

    @Test
    void emptyTableAggregatesReturnZero() {
        MiniDB db = new MiniDB();
        run(db, "CREATE TABLE t (a INT)");
        // 空表拍板语义：COUNT/SUM/AVG/MIN/MAX → 0（无 NULL 支持，与标准 SQL 差异报告说明）
        String out = run(db, "SELECT COUNT(*), SUM(a), AVG(a), MIN(a), MAX(a) FROM t");
        assertTrue(out.contains("0"), "空表聚合应为 0，实际: " + out);
        assertFalse(out.contains("null"), "不应输出 null");
    }

    @Test
    void aggregateWithWhereOnlyCountsFilteredRows() {
        MiniDB db = studentDb();
        // score >= 90 过滤后剩 2 行（Tom 90.5 / Bob 90.0），SUM(score) = 180.5
        String out = run(db, "SELECT COUNT(*), SUM(score) FROM student WHERE score >= 90.0");
        assertTrue(out.contains("180.5"), "过滤后 SUM(score)=180.5，实际: " + out);
    }

    @Test
    void aggregateArithmeticArg() {
        MiniDB db = studentDb();
        // AVG(score + 1) = 89.5（每行 +1 后求均）
        String out = run(db, "SELECT AVG(score + 1) FROM student");
        assertTrue(out.contains("89.5"), "AVG(score+1)=89.5，实际: " + out);
    }

    @Test
    void distinctKeepsFirstOccurrenceOrder() {
        MiniDB db = new MiniDB();
        run(db, "CREATE TABLE t (name VARCHAR(50))");
        run(db, "INSERT INTO t VALUES ('Tom')");
        run(db, "INSERT INTO t VALUES ('Alice')");
        run(db, "INSERT INTO t VALUES ('Tom')");
        run(db, "INSERT INTO t VALUES ('Alice')");
        String out = run(db, "SELECT DISTINCT name FROM t");
        // 保序去重：Tom 在前 Alice 在后，各一次
        int tom = out.indexOf("Tom");
        int alice = out.indexOf("Alice");
        assertTrue(tom >= 0 && alice >= 0, "应包含两行去重值，实际: " + out);
        assertTrue(tom < alice, "去重保持输入序（Tom 先出现），实际: " + out);
        assertTrue(out.indexOf("Tom", tom + 3) < 0 || out.indexOf("Tom", tom + 3) >= out.lastIndexOf("Alice"),
                "Tom 不应重复出现在结果行区");
    }

    @Test
    void distinctMultiColumnDeduplicatesWholeRow() {
        MiniDB db = new MiniDB();
        run(db, "CREATE TABLE t (a INT, b INT)");
        run(db, "INSERT INTO t VALUES (1, 1)");
        run(db, "INSERT INTO t VALUES (1, 1)");
        run(db, "INSERT INTO t VALUES (1, 2)");
        String out = run(db, "SELECT DISTINCT a, b FROM t");
        assertTrue(out.contains("(2 行)"), "整行判重应剩 2 行，实际: " + out);
    }

    @Test
    void mixedColumnAndAggregateRejectedThroughCli() {
        MiniDB db = studentDb();
        String out = run(db, "SELECT name, COUNT(*) FROM student");
        assertTrue(out.contains("SEMANTIC"), "混写应报 SEMANTIC，实际: " + out);
        assertTrue(out.contains("混写"));
    }

    @Test
    void aggregateInWhereRejectedThroughCli() {
        MiniDB db = studentDb();
        String out = run(db, "SELECT name FROM student WHERE COUNT(id) > 1");
        assertTrue(out.contains("SEMANTIC"), "WHERE 聚合应报 SEMANTIC，实际: " + out);
        assertTrue(out.contains("WHERE"));
    }

    @Test
    void traceShowsAggregateInAllFivePhases() {
        MiniDB db = studentDb();
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            db.traceSql("SELECT COUNT(*), AVG(score) FROM student WHERE id > 1");
        } finally {
            System.setOut(originalOut);
        }
        String out = baos.toString();
        // 五段 + AST 聚合项 + 优化前/后 Aggregate 节点（Filter 保留、规则4 标注 SeqScan cols）
        assertTrue(out.contains("── Tokens ──"));
        assertTrue(out.contains("(agg COUNT(*))"), "AST 应含聚合项，实际: " + out);
        assertTrue(out.contains("Aggregate[COUNT(*),AVG(score)]"), "Plan 应含 Aggregate 节点");
        assertTrue(out.contains("── Result ──"));
        // WHERE id>1 过滤后剩 Alice(85.0)/Bob(90.0)：COUNT=2、AVG=87.5
        assertTrue(out.contains("87.5"), "过滤后 AVG(score)=87.5，实际: " + out);
    }
}
