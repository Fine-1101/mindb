package com.minidb.engine;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.Literal;
import com.minidb.buffer.DiskBufferPool;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 磁盘模式五特性回归（直构 Plan 调 Engine，绕开 Parser/Planner）。
 *
 * <p>拍板2：UPDATE = deleteRow + insertRow，复用 DELETE+INSERT 路径，Page 契约零变更。
 * <p>拍板4：NULL 可赋给任何列类型，落盘不丢。
 * <p>拍板10：JOIN 双表页并存，互不干扰。
 */
class EngineDiskIntegrationTest {

    private static final String STUDENT = "student";
    private static final String COURSE = "course";
    private static final Position POS = new Position(1, 1);

    private DiskBufferPool pool;
    private MemoryCatalog catalog;
    private Engine engine;

    @BeforeEach
    void setUp() throws IOException {
        // 清理磁盘文件
        Files.deleteIfExists(Paths.get("data", STUDENT + ".dat"));
        Files.deleteIfExists(Paths.get("data", COURSE + ".dat"));
        Files.deleteIfExists(Paths.get("data", "catalog.dat"));

        // 用 MemoryCatalog（元数据不持久化，只测页数据持久化）
        catalog = new MemoryCatalog();
        pool = new DiskBufferPool(16);
        engine = new Engine(catalog, pool);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        try {
            Files.deleteIfExists(Paths.get("data", STUDENT + ".dat"));
            Files.deleteIfExists(Paths.get("data", COURSE + ".dat"));
            Files.deleteIfExists(Paths.get("data", "catalog.dat"));
        } catch (IOException e) {
            // ignore
        }
    }

    // ------------------------------------------------------------------
    // 辅助：直构 Plan
    // ------------------------------------------------------------------

    /** 直构建表 Plan 并执行。 */
    private void createStudentTable() throws MiniDbException {
        TableDef def = new TableDef(STUDENT, List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 64),
                new ColumnDef("score", DataType.FLOAT, 0)));
        engine.execute(new CreateTablePlan(def));
    }

    private void createCourseTable() throws MiniDbException {
        TableDef def = new TableDef(COURSE, List.of(
                new ColumnDef("cid", DataType.INT, 0),
                new ColumnDef("cname", DataType.VARCHAR, 32)));
        engine.execute(new CreateTablePlan(def));
    }

    /** 直构 Insert Plan（字面量行）。 */
    private void insertRow(String table, List<Expression> values) throws MiniDbException {
        // targetColumns 为空 = 按表定义序全列
        engine.execute(new InsertPlan(table, List.of(), List.of(values)));
    }

    /** 字面量快捷方法。 */
    private Literal intLit(int v) { return new Literal(v, DataType.INT, POS); }
    private Literal floatLit(double v) { return new Literal(v, DataType.FLOAT, POS); }
    private Literal strLit(String v) { return new Literal(v, DataType.VARCHAR, POS); }
    private Literal nullLit() { return new Literal(null, DataType.NULL, POS); }

    /** 直构 Select 查询（Project → SeqScan，可选 Filter）。 */
    private List<Object[]> select(String table, List<String> columns, Expression where) throws MiniDbException {
        PlanNode plan = new SeqScan(table);
        if (where != null) {
            plan = new Filter(plan, where);
        }
        plan = new Project(plan, columns);
        return engine.executeQuery(plan);
    }

    /** 直构 Delete Plan（带 WHERE）。 */
    private void deleteWhere(String table, Expression where) throws MiniDbException {
        engine.execute(new DeletePlan(table, where));
    }

    // ------------------------------------------------------------------
    // 回归1：UPDATE 后 close→重启→数据一致（含变长 VARCHAR 搬家行）
    // ------------------------------------------------------------------

    @Test
    void updatePersistsAcrossRestart() throws MiniDbException {
        createStudentTable();

        // 插入 3 行
        insertRow(STUDENT, List.of(intLit(1), strLit("short"), floatLit(90.0)));
        insertRow(STUDENT, List.of(intLit(2), strLit("Alice"), floatLit(85.0)));
        insertRow(STUDENT, List.of(intLit(3), strLit("Bob"), floatLit(60.0)));

        pool.flushAll();

        // 拍板2：UPDATE = DELETE + INSERT
        // 模拟 UPDATE student SET name='A_very_long_name...' WHERE id=2
        // 步骤1：DELETE WHERE id = 2
        Expression idEq2 = new BinaryExpr(
                new ColumnRef(null, "id", POS),
                BinaryOp.EQ,
                intLit(2),
                POS);
        deleteWhere(STUDENT, idEq2);

        // 步骤2：INSERT 新行（变长 VARCHAR 搬家行）
        String longName = "A_very_long_name_that_might_need_more_space_than_before_1234567890";
        insertRow(STUDENT, List.of(intLit(2), strLit(longName), floatLit(85.0)));

        pool.flushAll();
        pool.close();

        // 重启：新建 pool + engine，用 recoverTablePages 恢复页映射
        pool = new DiskBufferPool(16);
        engine = new Engine(catalog, pool);
        engine.recoverTablePages(STUDENT, pool.getTablePageCount(STUDENT));

        // 查询验证
        List<Object[]> rows = select(STUDENT, null, null);
        assertEquals(3, rows.size(), "重启后应该有 3 行");

        // 验证旧 id=2 行已删，新行可读
        boolean foundId2 = false;
        for (Object[] row : rows) {
            if (Integer.valueOf(2).equals(row[0])) {
                foundId2 = true;
                assertEquals(longName, row[1], "UPDATE 后变长 VARCHAR 应该完整");
                assertEquals(85.0, (Double) row[2], 0.0001);
            }
        }
        assertTrue(foundId2, "UPDATE 后的新行应该存在");

        // 验证其他行完整
        boolean foundId1 = false, foundId3 = false;
        for (Object[] row : rows) {
            if (Integer.valueOf(1).equals(row[0])) {
                foundId1 = true;
                assertEquals("short", row[1]);
            }
            if (Integer.valueOf(3).equals(row[0])) {
                foundId3 = true;
                assertEquals("Bob", row[1]);
            }
        }
        assertTrue(foundId1, "id=1 行应该完整");
        assertTrue(foundId3, "id=3 行应该完整");
    }

    // ------------------------------------------------------------------
    // 回归2：NULL 落盘不丢
    // ------------------------------------------------------------------

    @Test
    void nullPersistsAcrossRestart() throws MiniDbException {
        createStudentTable();

        // 插入含 NULL 的混合行（拍板4：NULL 可赋给任何列类型）
        insertRow(STUDENT, List.of(intLit(1), strLit("Alice"), floatLit(95.5)));
        insertRow(STUDENT, List.of(nullLit(), strLit("Bob"), floatLit(88.0)));      // id NULL
        insertRow(STUDENT, List.of(intLit(3), strLit("Carol"), nullLit()));         // score NULL
        insertRow(STUDENT, List.of(intLit(4), nullLit(), floatLit(72.5)));          // name NULL
        insertRow(STUDENT, List.of(nullLit(), nullLit(), nullLit()));               // 全 NULL

        pool.flushAll();
        pool.close();

        // 重启
        pool = new DiskBufferPool(16);
        engine = new Engine(catalog, pool);
        engine.recoverTablePages(STUDENT, pool.getTablePageCount(STUDENT));

        // 查询验证
        List<Object[]> rows = select(STUDENT, null, null);
        assertEquals(5, rows.size(), "重启后应该有 5 行");

        // 验证 NULL 不丢（按 id 排序，NULL id 排最后）
        // 由于无 ORDER BY，直接遍历验证
        int nullCount = 0;
        for (Object[] row : rows) {
            if (row[0] == null) nullCount++;
        }
        assertEquals(2, nullCount, "应该有 2 行的 id 为 NULL");

        // 验证特定行
        boolean foundBob = false;
        for (Object[] row : rows) {
            if ("Bob".equals(row[1])) {
                foundBob = true;
                assertNull(row[0], "Bob 的 id 应该为 NULL");
                assertEquals(88.0, (Double) row[2], 0.0001);
            }
        }
        assertTrue(foundBob, "Bob 行应该存在且 id 为 NULL");
    }

    // ------------------------------------------------------------------
    // 回归3：双表页并存
    // ------------------------------------------------------------------

    @Test
    void twoTablesCoexist() throws MiniDbException {
        createStudentTable();
        createCourseTable();

        // 两表各插数据
        insertRow(STUDENT, List.of(intLit(1), strLit("Tom"), floatLit(90.0)));
        insertRow(STUDENT, List.of(intLit(2), strLit("Alice"), floatLit(85.0)));
        insertRow(COURSE, List.of(intLit(101), strLit("Math")));
        insertRow(COURSE, List.of(intLit(102), strLit("Physics")));

        pool.flushAll();
        pool.close();

        // 重启
        pool = new DiskBufferPool(16);
        engine = new Engine(catalog, pool);
        engine.recoverTablePages(STUDENT, pool.getTablePageCount(STUDENT));
        engine.recoverTablePages(COURSE, pool.getTablePageCount(COURSE));

        // 验证 student 表
        List<Object[]> studentRows = select(STUDENT, null, null);
        assertEquals(2, studentRows.size(), "student 表应该有 2 行");

        // 验证 course 表
        List<Object[]> courseRows = select(COURSE, null, null);
        assertEquals(2, courseRows.size(), "course 表应该有 2 行");

        // 验证两表数据不混淆
        boolean foundMath = false;
        for (Object[] row : courseRows) {
            if ("Math".equals(row[1])) {
                foundMath = true;
                assertEquals(101, row[0]);
            }
        }
        assertTrue(foundMath, "course 表应该有 Math 课程");
    }

    // ------------------------------------------------------------------
    // 额外：UPDATE 存储路径对拍（走 Engine，验证拍板2）
    // ------------------------------------------------------------------

    @Test
    void updateViaDeleteInsertPathParity() throws MiniDbException {
        createStudentTable();

        // 插入 3 行
        insertRow(STUDENT, List.of(intLit(1), strLit("A"), floatLit(60.0)));
        insertRow(STUDENT, List.of(intLit(2), strLit("B"), floatLit(70.0)));
        insertRow(STUDENT, List.of(intLit(3), strLit("C"), floatLit(80.0)));

        // 记录初始 freeSpace（通过 pool 拿页）
        // 注：Engine 内部管理页，这里通过查询间接验证
        List<Object[]> before = select(STUDENT, null, null);
        assertEquals(3, before.size());

        // UPDATE = DELETE + INSERT
        Expression idEq2 = new BinaryExpr(
                new ColumnRef(null, "id", POS),
                BinaryOp.EQ,
                intLit(2),
                POS);
        deleteWhere(STUDENT, idEq2);
        insertRow(STUDENT, List.of(intLit(2), strLit("B_updated"), floatLit(70.0)));

        // 验证
        List<Object[]> after = select(STUDENT, null, null);
        assertEquals(3, after.size(), "UPDATE 后行数应该不变");

        boolean foundUpdated = false;
        for (Object[] row : after) {
            if (Integer.valueOf(2).equals(row[0])) {
                foundUpdated = true;
                assertEquals("B_updated", row[1], "UPDATE 后新值应该可读");
            }
        }
        assertTrue(foundUpdated, "UPDATE 后的行应该存在");
    }
}