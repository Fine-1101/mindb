package com.minidb.integration;

import com.minidb.MiniDB;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 五大特性端到端集成测试：
 * UPDATE / ORDER BY / NULL 三值逻辑 / GROUP BY / JOIN
 * + 组合矩阵（JOIN+ORDER BY、GROUP BY+NULL、UPDATE 后聚合）
 */
class FiveFeaturesTest {

    private MiniDB db;

    @BeforeEach
    void setUp() {
        db = new MiniDB();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    // ============================================================
    // 辅助：捕获 stdout
    // ============================================================

    private String captureOutput(Runnable action) {
        PrintStream orig = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            action.run();
        } finally {
            System.setOut(orig);
        }
        return baos.toString();
    }

    // ============================================================
    // 1. UPDATE 基础
    // ============================================================

    @Test
    void testUpdateBasic() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");

        String out = captureOutput(() -> db.executeSql("UPDATE t SET val = 99 WHERE id = 2"));
        assertTrue(out.contains("更新 1 行"), "应反馈 '更新 1 行'，实际: " + out);

        // 验证更新结果
        out = captureOutput(() -> db.executeSql("SELECT val FROM t WHERE id = 2"));
        assertTrue(out.contains("99"), "id=2 的 val 应已更新为 99");
    }

    @Test
    void testUpdateAllRows() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, 10), (2, 20)");

        String out = captureOutput(() -> db.executeSql("UPDATE t SET val = 0"));
        assertTrue(out.contains("更新 2 行"), "无 WHERE 应更新全部 2 行");
    }

    @Test
    void testUpdateNoMatch() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, 10)");

        String out = captureOutput(() -> db.executeSql("UPDATE t SET val = 99 WHERE id = 999"));
        assertTrue(out.contains("更新 0 行"), "无匹配行应反馈 0 行");
    }

    // ============================================================
    // 2. ORDER BY
    // ============================================================

    @Test
    void testOrderByAsc() {
        db.executeSql("CREATE TABLE t (id INT, name VARCHAR(20))");
        db.executeSql("INSERT INTO t VALUES (3, 'C'), (1, 'A'), (2, 'B')");

        String out = captureOutput(() -> db.executeSql("SELECT id, name FROM t ORDER BY id ASC"));
        // 提取数据行顺序
        int idxA = out.indexOf("A");
        int idxB = out.indexOf("B");
        int idxC = out.indexOf("C");
        assertTrue(idxA < idxB && idxB < idxC, "ASC 排序应为 A,B,C");
    }

    @Test
    void testOrderByDesc() {
        db.executeSql("CREATE TABLE t (id INT, name VARCHAR(20))");
        db.executeSql("INSERT INTO t VALUES (1, 'A'), (2, 'B'), (3, 'C')");

        String out = captureOutput(() -> db.executeSql("SELECT id, name FROM t ORDER BY id DESC"));
        int idxA = out.indexOf("A");
        int idxB = out.indexOf("B");
        int idxC = out.indexOf("C");
        assertTrue(idxC < idxB && idxB < idxA, "DESC 排序应为 C,B,A");
    }

    @Test
    void testOrderByNullMinimum() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, 10), (2, NULL), (3, 30)");

        String out = captureOutput(() -> db.executeSql("SELECT val FROM t ORDER BY val ASC"));
        // NULL 应排最前
        int nullIdx = out.indexOf("NULL");
        int idx10 = out.indexOf("10");
        assertTrue(nullIdx >= 0 && nullIdx < idx10, "NULL 应排在 ASC 最前");
    }

    // ============================================================
    // 3. NULL 三值逻辑 + IS [NOT] NULL
    // ============================================================

    @Test
    void testIsNull() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, 10), (2, NULL), (3, 30)");

        String out = captureOutput(() -> db.executeSql("SELECT id FROM t WHERE val IS NULL"));
        assertTrue(out.contains("2"), "IS NULL 应找到 val=NULL 的行");
        assertFalse(out.contains(" 1 ") || out.contains(" 3 "), "非 NULL 行不应出现");
    }

    @Test
    void testIsNotNull() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, 10), (2, NULL), (3, 30)");

        String out = captureOutput(() -> db.executeSql("SELECT id FROM t WHERE val IS NOT NULL"));
        assertTrue(out.contains("1") && out.contains("3"), "IS NOT NULL 应找到非 NULL 行");
    }

    @Test
    void testNullArithmetic() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, NULL)");

        // NULL + 1 应为 NULL，WHERE 中 NULL 当 false → 无结果
        String out = captureOutput(() -> db.executeSql("SELECT id FROM t WHERE val + 1 > 0"));
        assertFalse(out.contains(" 1 "), "NULL 参与比较结果应为 NULL（过滤掉）");
    }

    @Test
    void testNullComparison() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, NULL)");

        // NULL = NULL 应为 NULL（非 TRUE）
        String out = captureOutput(() -> db.executeSql("SELECT id FROM t WHERE val = NULL"));
        assertFalse(out.contains(" 1 "), "NULL = NULL 应为 NULL（过滤掉）");
    }

    // ============================================================
    // 4. GROUP BY
    // ============================================================

    @Test
    void testGroupByBasic() {
        db.executeSql("CREATE TABLE sales (dept VARCHAR(10), amount INT)");
        db.executeSql("INSERT INTO sales VALUES ('A', 100), ('B', 200), ('A', 150), ('B', 50)");

        String out = captureOutput(() ->
                db.executeSql("SELECT dept, SUM(amount) FROM sales GROUP BY dept"));
        assertTrue(out.contains("A") && out.contains("B"), "应包含两个分组，实际输出: " + out);
        // A=250, B=250
        assertTrue(out.contains("250"), "A 组和 B 组总和均为 250");
    }

    @Test
    void testGroupByCount() {
        db.executeSql("CREATE TABLE t (grp VARCHAR(10), val INT)");
        db.executeSql("INSERT INTO t VALUES ('X', 1), ('X', 2), ('X', NULL), ('Y', 10)");

        String out = captureOutput(() ->
                db.executeSql("SELECT grp, COUNT(*) FROM t GROUP BY grp"));
        // X: 3 行（含 NULL），Y: 1 行
        assertTrue(out.contains("3"), "COUNT(*) 含 NULL 行，X 组应=3");
        assertTrue(out.contains("1"), "Y 组应=1");
    }

    @Test
    void testGroupByNullSemantics() {
        db.executeSql("CREATE TABLE t (grp VARCHAR(10), val INT)");
        db.executeSql("INSERT INTO t VALUES ('X', NULL), ('X', NULL), ('Y', 10)");

        // COUNT(val) 忽略 NULL
        String out = captureOutput(() ->
                db.executeSql("SELECT grp, COUNT(val) FROM t GROUP BY grp"));
        // X: COUNT(val)=0（全 NULL）→ 显示 0
        // Y: COUNT(val)=1
        assertTrue(out.contains("X") && out.contains("Y"), "应有 X 和 Y 两组");
    }

    @Test
    void testScalarAggregateEmptyTable() {
        db.executeSql("CREATE TABLE t (val INT)");
        // 空表
        String out = captureOutput(() ->
                db.executeSql("SELECT COUNT(*), SUM(val) FROM t"));
        // COUNT(*)=0, SUM=NULL
        assertTrue(out.contains("0"), "空表 COUNT(*) 应为 0");
        assertTrue(out.contains("NULL"), "空表 SUM 应为 NULL");
    }

    // ============================================================
    // 5. JOIN
    // ============================================================

    @Test
    void testJoinBasic() {
        db.executeSql("CREATE TABLE emp (id INT, name VARCHAR(20), dept_id INT)");
        db.executeSql("CREATE TABLE dept (id INT, dept_name VARCHAR(20))");
        db.executeSql("INSERT INTO emp VALUES (1, 'Alice', 1), (2, 'Bob', 2)");
        db.executeSql("INSERT INTO dept VALUES (1, 'Eng'), (2, 'Sales')");

        String out = captureOutput(() ->
                db.executeSql("SELECT * FROM emp JOIN dept ON emp.dept_id = dept.id"));
        assertTrue(out.contains("Alice") && out.contains("Eng"), "Alice-Eng 应匹配");
        assertTrue(out.contains("Bob") && out.contains("Sales"), "Bob-Sales 应匹配");
    }

    @Test
    void testJoinQualifiedColumns() {
        db.executeSql("CREATE TABLE a (id INT, val INT)");
        db.executeSql("CREATE TABLE b (id INT, score INT)");
        db.executeSql("INSERT INTO a VALUES (1, 10)");
        db.executeSql("INSERT INTO b VALUES (1, 99)");

        // SELECT * JOIN 表头应全限定名
        String out = captureOutput(() ->
                db.executeSql("SELECT * FROM a JOIN b ON a.id = b.id"));
        assertTrue(out.contains("a.id") && out.contains("b.id"),
                "SELECT * JOIN 表头应含全限定名，实际: " + out);
    }

    @Test
    void testJoinNoMatch() {
        db.executeSql("CREATE TABLE a (id INT)");
        db.executeSql("CREATE TABLE b (id INT)");
        db.executeSql("INSERT INTO a VALUES (1)");
        db.executeSql("INSERT INTO b VALUES (2)");

        String out = captureOutput(() ->
                db.executeSql("SELECT * FROM a JOIN b ON a.id = b.id"));
        assertTrue(out.contains("空") || out.contains("0 行"),
                "无匹配 JOIN 应返回空结果");
    }

    // ============================================================
    // 6. 组合矩阵
    // ============================================================

    @Test
    void testJoinPlusOrderBy() {
        db.executeSql("CREATE TABLE emp (id INT, name VARCHAR(20), dept_id INT)");
        db.executeSql("CREATE TABLE dept (id INT, dept_name VARCHAR(20))");
        db.executeSql("INSERT INTO emp VALUES (1, 'Alice', 1), (2, 'Bob', 2), (3, 'Charlie', 1)");
        db.executeSql("INSERT INTO dept VALUES (1, 'Eng'), (2, 'Sales')");

        String out = captureOutput(() ->
                db.executeSql("SELECT emp.name, dept.dept_name FROM emp JOIN dept ON emp.dept_id = dept.id ORDER BY emp.name ASC"));
        int aliceIdx = out.indexOf("Alice");
        int bobIdx = out.indexOf("Bob");
        int charlieIdx = out.indexOf("Charlie");
        assertTrue(aliceIdx < bobIdx && bobIdx < charlieIdx,
                "JOIN+ORDER BY 应按 name ASC 排列");
    }

    @Test
    void testGroupByPlusNull() {
        db.executeSql("CREATE TABLE t (grp VARCHAR(10), val INT)");
        db.executeSql("INSERT INTO t VALUES ('A', 10), ('A', NULL), ('B', NULL), ('B', NULL)");

        // AVG 忽略 NULL，B 组全 NULL → NULL
        String out = captureOutput(() ->
                db.executeSql("SELECT grp, AVG(val) FROM t GROUP BY grp"));
        assertTrue(out.contains("A"), "应有 A 组");
        assertTrue(out.contains("B"), "应有 B 组");
        // A 组 AVG=10.0
        assertTrue(out.contains("10"), "A 组 AVG 应为 10");
    }

    @Test
    void testUpdateThenAggregate() {
        db.executeSql("CREATE TABLE t (grp VARCHAR(10), val INT)");
        db.executeSql("INSERT INTO t VALUES ('A', 10), ('A', 20), ('B', 100)");

        // 先 UPDATE
        captureOutput(() -> db.executeSql("UPDATE t SET val = 50 WHERE grp = 'A'"));

        // 再聚合
        String out = captureOutput(() ->
                db.executeSql("SELECT grp, SUM(val) FROM t GROUP BY grp"));
        // A: 50+50=100, B: 100
        assertTrue(out.contains("100"), "A 组更新后 SUM=100，B 组=100");
    }

    @Test
    void testUpdateWithNullCondition() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, NULL), (2, 10), (3, NULL)");

        // UPDATE 命中 IS NULL 的行
        String out = captureOutput(() ->
                db.executeSql("UPDATE t SET val = 0 WHERE val IS NULL"));
        assertTrue(out.contains("更新 2 行"), "IS NULL 条件应命中 2 行");

        // 验证
        out = captureOutput(() -> db.executeSql("SELECT val FROM t WHERE val IS NOT NULL ORDER BY val"));
        assertTrue(out.contains("0"), "NULL 应已更新为 0");
    }

    // ============================================================
    // 7. .trace 验证 Aggregate/Sort/Join 节点
    // ============================================================

    @Test
    void testTraceAggregateNode() {
        db.executeSql("CREATE TABLE t (grp VARCHAR(10), val INT)");
        db.executeSql("INSERT INTO t VALUES ('A', 10)");

        String out = captureOutput(() ->
                db.traceSql("SELECT grp, SUM(val) FROM t GROUP BY grp"));
        assertTrue(out.contains("Aggregate"), ".trace 应显示 Aggregate 节点");
    }

    @Test
    void testTraceSortNode() {
        db.executeSql("CREATE TABLE t (id INT)");
        db.executeSql("INSERT INTO t VALUES (1)");

        String out = captureOutput(() ->
                db.traceSql("SELECT id FROM t ORDER BY id"));
        assertTrue(out.contains("Sort"), ".trace 应显示 Sort 节点");
    }

    @Test
    void testTraceJoinNode() {
        db.executeSql("CREATE TABLE a (id INT)");
        db.executeSql("CREATE TABLE b (id INT)");
        db.executeSql("INSERT INTO a VALUES (1)");
        db.executeSql("INSERT INTO b VALUES (1)");

        String out = captureOutput(() ->
                db.traceSql("SELECT * FROM a JOIN b ON a.id = b.id"));
        assertTrue(out.contains("Join"), ".trace 应显示 Join 节点");
    }

    @Test
    void testTraceUpdateNode() {
        db.executeSql("CREATE TABLE t (id INT, val INT)");
        db.executeSql("INSERT INTO t VALUES (1, 10)");

        String out = captureOutput(() ->
                db.traceSql("UPDATE t SET val = 99 WHERE id = 1"));
        assertTrue(out.contains("Update"), ".trace 应显示 Update 节点");
        assertTrue(out.contains("更新 1 行"), ".trace 应反馈更新行数");
    }
}
