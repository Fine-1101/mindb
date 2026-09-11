package com.minidb.integration;

import com.minidb.ast.Statement;
import com.minidb.buffer.BufferPool;
import com.minidb.buffer.DiskBufferPool;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.PersistentCatalog;
import com.minidb.common.MiniDbException;
import com.minidb.engine.Engine;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.parser.Parser;
import com.minidb.planner.Optimizer;
import com.minidb.planner.Planner;
import com.minidb.semantic.SemanticAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 磁盘路径端到端：DiskBufferPool(SlottedPage) 上 CREATE→INSERT→flushAll→关闭→重开→
 * recoverTablePages→SELECT/DELETE 与内存路径结果一致。
 * 此前 SeqScan 对 SlottedPage 恒返 0 槽（Page 契约缺 slotCount），磁盘路径查询/删除静默为空——本测试锁定该回归。
 */
class DiskEndToEndTest {

    private static final String TEST_TABLE = "disk_e2e";

    private Catalog catalog;
    private BufferPool pool;
    private Engine engine;
    private Lexer lexer;
    private Parser parser;
    private SemanticAnalyzer analyzer;
    private Planner planner;
    private Optimizer optimizer;

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Paths.get("data", TEST_TABLE + ".dat"));
        Files.deleteIfExists(Paths.get("data", TEST_TABLE + "_b.dat"));
        Files.deleteIfExists(Paths.get("data", "catalog.dat"));
        openFresh();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (pool instanceof DiskBufferPool dp) {
            dp.close();
        }
        Files.deleteIfExists(Paths.get("data", TEST_TABLE + ".dat"));
        Files.deleteIfExists(Paths.get("data", TEST_TABLE + "_b.dat"));
        Files.deleteIfExists(Paths.get("data", "catalog.dat"));
    }

    private void openFresh() {
        catalog = new PersistentCatalog();
        pool = new DiskBufferPool(4);
        engine = new Engine(catalog, pool);
        lexer = new Lexer();
        parser = new Parser();
        analyzer = new SemanticAnalyzer(catalog);
        planner = new Planner(catalog);
        optimizer = new Optimizer();
    }

    private List<Object[]> query(String sql) throws MiniDbException {
        Statement stmt = parse(sql);
        analyzer.analyze(stmt);
        return engine.executeQuery(optimizer.optimize(planner.plan(stmt)));
    }

    private void execute(String sql) throws MiniDbException {
        Statement stmt = parse(sql);
        analyzer.analyze(stmt);
        var plan = optimizer.optimize(planner.plan(stmt));
        if (stmt instanceof com.minidb.ast.SelectStmt) {
            engine.executeQuery(plan);
        } else {
            engine.execute(plan);
        }
    }

    private Statement parse(String sql) throws MiniDbException {
        List<Token> tokens = lexer.tokenize(sql);
        List<Statement> stmts = parser.parseScript(tokens);
        assertEquals(1, stmts.size());
        return stmts.get(0);
    }

    @Test
    void selectOnDiskPathReturnsRows() throws MiniDbException {
        execute("CREATE TABLE " + TEST_TABLE + " (id INT, name VARCHAR(50), score FLOAT)");
        execute("INSERT INTO " + TEST_TABLE + " VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0), (3, 'Bob', 60.0)");

        // 同一 pool 实例上直接查询（SlottedPage 扫描路径）
        List<Object[]> rows = query("SELECT name, score FROM " + TEST_TABLE + " WHERE score >= 80.0");
        assertEquals(2, rows.size(), "SlottedPage 路径应能扫出数据");
        assertEquals("Tom", rows.get(0)[0]);
        assertEquals("Alice", rows.get(1)[0]);
    }

    @Test
    void deleteOnDiskPathMarksDeleted() throws MiniDbException {
        execute("CREATE TABLE " + TEST_TABLE + " (id INT, name VARCHAR(50))");
        execute("INSERT INTO " + TEST_TABLE + " VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Charlie')");

        execute("DELETE FROM " + TEST_TABLE + " WHERE id = 2");

        List<Object[]> rows = query("SELECT id, name FROM " + TEST_TABLE);
        assertEquals(2, rows.size(), "DELETE 应在 SlottedPage 上生效");
        assertEquals("Alice", rows.get(0)[1]);
        assertEquals("Charlie", rows.get(1)[1]);
    }

    @Test
    void restartRecoveryEndToEnd() throws MiniDbException {
        execute("CREATE TABLE " + TEST_TABLE + " (id INT, name VARCHAR(50), score FLOAT)");
        execute("INSERT INTO " + TEST_TABLE + " VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0)");
        ((DiskBufferPool) pool).close();

        // "重启"：全新 pool/engine，从磁盘文件页数恢复页映射
        openFresh();
        engine.recoverTablePages(TEST_TABLE, ((DiskBufferPool) pool).getTablePageCount(TEST_TABLE.toLowerCase()));

        List<Object[]> rows = query("SELECT id, name FROM " + TEST_TABLE);
        assertEquals(2, rows.size(), "重启后应从磁盘恢复全部数据");
        assertEquals(1, rows.get(0)[0]);
        assertEquals(2, rows.get(1)[0]);

        // 重启后可继续写
        execute("INSERT INTO " + TEST_TABLE + " VALUES (3, 'Bob', 60.0)");
        assertEquals(3, query("SELECT id FROM " + TEST_TABLE).size());
    }

    @Test
    void multiTablePagesDoNotCrossContaminate() throws MiniDbException {
        // 回归：DiskBufferPool 缓存曾用全局 pageId 键——多表同号页互相覆盖/写错文件
        execute("CREATE TABLE " + TEST_TABLE + " (a INT)");
        execute("INSERT INTO " + TEST_TABLE + " VALUES (1), (2)");

        String other = TEST_TABLE + "_b";
        execute("CREATE TABLE " + other + " (b VARCHAR(20))");
        execute("INSERT INTO " + other + " VALUES ('x'), ('y'), ('z')");

        ((DiskBufferPool) pool).close();
        openFresh();
        engine.recoverTablePages(TEST_TABLE, ((DiskBufferPool) pool).getTablePageCount(TEST_TABLE.toLowerCase()));
        engine.recoverTablePages(other, ((DiskBufferPool) pool).getTablePageCount(other.toLowerCase()));

        assertEquals(2, query("SELECT a FROM " + TEST_TABLE).size(), "表 1 数据不得串入表 2 文件");
        List<Object[]> rowsB = query("SELECT b FROM " + other);
        assertEquals(3, rowsB.size(), "表 2 数据不得串入表 1 文件");
        assertEquals("x", rowsB.get(0)[0]);
        assertEquals("z", rowsB.get(2)[0]);
    }

    @Test
    void cliCloseFlushesRowData() {
        // 回归：CLI 退出路径（MiniDB.main）曾从不关闭池——newPage 只写空页、行数据全在缓存，
        // 重启后只剩表结构。锁定 MiniDB.close() 后行数据可恢复。
        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(baos));
        com.minidb.MiniDB db = null;
        com.minidb.MiniDB restarted = null;
        try {
            db = new com.minidb.MiniDB(true);
            db.executeSql("CREATE TABLE " + TEST_TABLE + " (id INT, name VARCHAR(50))");
            db.executeSql("INSERT INTO " + TEST_TABLE + " VALUES (1, 'Tom'), (2, 'Alice')");
            db.close();  // 模拟 exit/脚本结束的退出刷盘

            // "重启"：全新 MiniDB（构造器内 recoverFromDisk），行数据应从磁盘恢复
            restarted = new com.minidb.MiniDB(true);
            restarted.executeSql("SELECT id, name FROM " + TEST_TABLE);
        } finally {
            System.setOut(originalOut);
            if (db != null) {
                db.close();  // 幂等；Windows 下必须释放句柄否则 tearDown 删文件失败
            }
            if (restarted != null) {
                restarted.close();
            }
        }

        String out = baos.toString();
        assertTrue(out.contains("Tom"), "重启后应恢复行数据 Tom，实际输出: " + out);
        assertTrue(out.contains("Alice"), "重启后应恢复行数据 Alice，实际输出: " + out);
    }
}
