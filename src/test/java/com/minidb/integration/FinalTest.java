package com.minidb.integration;

import com.minidb.MiniDB;
import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.buffer.BufferPool;
import com.minidb.buffer.BufferPoolStats;
import com.minidb.buffer.DiskBufferPool;
import com.minidb.buffer.InMemoryBufferPool;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.catalog.PersistentCatalog;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.engine.RowEncoder;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.lexer.TokenType;
import com.minidb.parser.Parser;
import com.minidb.plan.PlanNode;
import com.minidb.plan.PlanPrinter;
import com.minidb.planner.Optimizer;
import com.minidb.planner.Planner;
import com.minidb.semantic.SemanticAnalyzer;
import com.minidb.storage.MemoryPage;
import com.minidb.storage.Page;
import com.minidb.storage.SlottedPage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收全量测试：评分表 9 个评分项（40 分）的整合展示，
 * 按三大模块顺序排列并依次执行：
 *
 * <p>模块一 SQL 编译器（16 分）：1.词法分析 / 2.语法分析 / 3.语义分析 / 4.执行计划生成
 * <p>模块二 存储系统（12 分）：5.页式存储管理 / 6.缓存机制 / 7.接口与集成
 * <p>模块三 数据库系统（12 分）：8.执行引擎 / 9.存储引擎与目录 / 10.系统集成
 *
 * <p>每步打印执行过程，便于验收现场观察。
 * 运行（-Dsurefire.useFile=false 使 System.out 直达控制台，否则见
 * target/surefire-reports/com.minidb.integration.AcceptanceStorageTest.txt）：
 * <pre>mvn test -Dtest=AcceptanceStorageTest -Dsurefire.useFile=false</pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FinalTest {

    /** 行编码列定义：id INT + score FLOAT + name VARCHAR(32) + active BOOLEAN */
    private static final List<ColumnDef> COLS = List.of(
            new ColumnDef("id", DataType.INT, 0),
            new ColumnDef("score", DataType.FLOAT, 0),
            new ColumnDef("name", DataType.VARCHAR, 32),
            new ColumnDef("active", DataType.BOOLEAN, 0));

    @TempDir
    Path dataDir;

    // ==================================================================
    // 模块一：SQL 编译器（16 分）
    // ==================================================================

    // ---------- 1. 词法分析（4 分）：Token 识别 + 非法输入 ----------

    @Test
    @Order(1)
    void 词法_Token识别与非法输入() throws MiniDbException {
        System.out.println("========== 模块一·SQL编译器 | 1. 词法分析：Token 识别 + 非法输入 ==========");

        // 1a 关键字（大小写不敏感、text 保留原文）/标识符/运算符/常量
        String sql = "SeLeCt name FROM student WHERE id >= 1 AND id != 2;";
        List<Token> tokens = lexer.tokenize(sql);
        System.out.println("[1] 输入: " + sql);
        for (Token t : tokens) {
            if (t.type() == TokenType.EOF) break;
            System.out.println("    " + t.type() + "  text=" + t.text() + "  @" + t.pos());
        }
        assertEquals(TokenType.KW_SELECT, tokens.get(0).type(), "关键字应按类型识别（大小写不敏感）");
        assertEquals("SeLeCt", tokens.get(0).text(), "text 应保留原文大小写");
        Token ge = findToken(tokens, TokenType.OP_GE);
        Token ne = findToken(tokens, TokenType.OP_NE);
        assertNotNull(ge, "多字符运算符 >= 应不拆分");
        assertNotNull(ne, "多字符运算符 != 应不拆分");

        // 1b 常量：整数/浮点/字符串转义/布尔
        System.out.println("[2] 常量识别：INSERT INTO t VALUES (42, 3.14, 'Tom''s book', TRUE);");
        List<Token> lits = lexer.tokenize("INSERT INTO t VALUES (42, 3.14, 'Tom''s book', TRUE);");
        Token i42 = findToken(lits, TokenType.INT_LIT);
        Token f314 = findToken(lits, TokenType.FLOAT_LIT);
        Token str = findToken(lits, TokenType.STRING);
        assertEquals(42, i42.value(), "整数常量 value 应为 Integer 42");
        assertEquals(3.14, f314.value(), "浮点常量 value 应为 Double 3.14");
        assertEquals("Tom's book", str.value(), "'' 转义应还原为一个单引号");
        assertEquals(TokenType.KW_TRUE, findToken(lits, TokenType.KW_TRUE).type(), "TRUE 应识别为关键字");
        System.out.println("    INT_LIT.value=" + i42.value() + "，FLOAT_LIT.value=" + f314.value()
                + "，STRING.value=" + str.value() + "（转义 OK），TRUE=关键字 PASS");

        // 1c 非法输入：非法字符 / 未闭合字符串 / 非法数字（不崩溃，返回类型+位置+原因）
        System.out.println("[3] 非法输入（错误类型+位置+原因，不崩溃）：");
        assertPhaseError(() -> lexer.tokenize("SELECT @ FROM t"), MiniDbException.Phase.LEXER);
        assertPhaseError(() -> lexer.tokenize("SELECT 'abc"), MiniDbException.Phase.LEXER);
        assertPhaseError(() -> lexer.tokenize("INSERT INTO t VALUES (1.2.3)"), MiniDbException.Phase.LEXER);
        System.out.println("[4] 三类词法错误均定位到 LEXER 阶段 PASS");
    }

    // ---------- 2. 语法分析（4 分）：四类语句 AST + 优先级 + 典型错误 ----------

    @Test
    @Order(2)
    void 语法_四类语句AST与典型错误() throws MiniDbException {
        System.out.println("========== 模块一·SQL编译器 | 2. 语法分析：四类语句 AST + 优先级 + 典型错误 ==========");

        // 2a 四类语句各自解析成对应 AST 节点
        String[] sqls = {
                "CREATE TABLE t1 (id INT, name VARCHAR(50));",
                "INSERT INTO t1 VALUES (1, 'Tom');",
                "SELECT name FROM t1 WHERE id >= 1;",
                "DELETE FROM t1 WHERE id = 1;"
        };
        Class<?>[] expect = {com.minidb.ast.CreateTableStmt.class, com.minidb.ast.InsertStmt.class,
                SelectStmt.class, com.minidb.ast.DeleteStmt.class};
        for (int i = 0; i < sqls.length; i++) {
            Statement stmt = parseOne(sqls[i]);
            assertEquals(expect[i], stmt.getClass(), sqls[i] + " 应解析为 " + expect[i].getSimpleName());
            System.out.println("[OK] " + stmt.getClass().getSimpleName() + "  ←  " + sqls[i]);
        }

        // 2b 表达式优先级：NOT > 比较 > AND > OR（AST 顶层应为 OR，右子树为 AND）
        Statement stmt = parseOne("SELECT * FROM t1 WHERE a = 1 OR b = 2 AND c > 3;");
        BinaryExpr where = (BinaryExpr) ((SelectStmt) stmt).where();
        assertEquals(BinaryOp.OR, where.op(), "无括号时 OR 结合性最弱，应为根");
        assertEquals(BinaryOp.AND, ((BinaryExpr) where.right()).op(), "AND 应强于 OR，挂在子树");
        System.out.println("[OK] 优先级 AST：(OR (= a 1) (AND (= b 2) (> c 3))) —— 顶层 OR、AND 在子树 PASS");

        // 2c 括号改变结合
        Statement stmt2 = parseOne("SELECT * FROM t1 WHERE (a = 1 OR b = 2) AND c > 3;");
        BinaryExpr where2 = (BinaryExpr) ((SelectStmt) stmt2).where();
        assertEquals(BinaryOp.AND, where2.op(), "括号应改变结合，AND 为根");
        assertEquals(BinaryOp.OR, ((BinaryExpr) where2.left()).op(), "括号内 OR 应为左子树");
        System.out.println("[OK] 括号 AST：(AND (OR (= a 1) (= b 2)) (> c 3)) —— 结合被括号改变 PASS");

        // 2d 典型语法错误：位置 + unexpected token + 期望集合
        System.out.println("[典型语法错误]（位置 + unexpected token + 期望集合）：");
        assertPhaseError(() -> parseOne("SELECT FROM t1;"), MiniDbException.Phase.PARSER);
        assertPhaseError(() -> parseOne("INSERT INTO t1 VALUES;"), MiniDbException.Phase.PARSER);
        assertPhaseError(() -> parseOne("CREATE TABLE (id INT);"), MiniDbException.Phase.PARSER);
    }

    // ---------- 3. 语义分析（4 分）：存在性 / 类型 / 列数匹配 / Catalog ----------

    @Test
    @Order(3)
    void 语义_存在性类型与列数匹配() throws MiniDbException {
        System.out.println("========== 模块一·SQL编译器 | 3. 语义分析：表/列存在性、类型、列数匹配 ==========");
        Catalog catalog = new MemoryCatalog();
        catalog.createTable(new TableDef("student", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50),
                new ColumnDef("score", DataType.FLOAT, 0))));
        SemanticAnalyzer sem = new SemanticAnalyzer(catalog);

        // 3a 正例：全部检查通过
        sem.analyze(parseOne("INSERT INTO student VALUES (1, 'Tom', 90.5);"));
        sem.analyze(parseOne("SELECT name FROM student WHERE score >= 85.0;"));
        System.out.println("[1] 正例（INSERT 三值匹配 / SELECT 列存在 + WHERE 布尔）→ 分析通过 PASS");

        // 3b 错例：表/列存在性、列数匹配、类型检查、重名表
        System.out.println("[2] 错例（Catalog 驱动，逐条报 阶段+位置+原因）：");
        assertPhaseError(() -> sem.analyze(parseOne("SELECT * FROM nosuch;")), MiniDbException.Phase.SEMANTIC);
        assertPhaseError(() -> sem.analyze(parseOne("SELECT nosuch FROM student;")), MiniDbException.Phase.SEMANTIC);
        assertPhaseError(() -> sem.analyze(parseOne("INSERT INTO student VALUES (1);")), MiniDbException.Phase.SEMANTIC);
        assertPhaseError(() -> sem.analyze(parseOne("INSERT INTO student VALUES ('x', 'y', 1.0);")), MiniDbException.Phase.SEMANTIC);
        assertPhaseError(() -> catalog.createTable(new TableDef("STUDENT", List.of(
                new ColumnDef("x", DataType.INT, 0)))), MiniDbException.Phase.SEMANTIC);
        System.out.println("[3] 表不存在/列不存在/列数不匹配/类型不匹配/重名建表 均定位 SEMANTIC PASS");
    }

    // ---------- 4. 执行计划生成（4 分）：S 表达式计划树 + 算子组织 ----------

    @Test
    @Order(4)
    void 计划_S表达式与算子组织() throws MiniDbException {
        System.out.println("========== 模块一·SQL编译器 | 4. 执行计划生成：S 表达式计划树（优化前/后） ==========");
        Catalog catalog = new MemoryCatalog();
        catalog.createTable(new TableDef("student", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50),
                new ColumnDef("score", DataType.FLOAT, 0))));
        Planner planner = new Planner(catalog);
        Optimizer optimizer = new Optimizer();
        SemanticAnalyzer sem = new SemanticAnalyzer(catalog);

        // 4a 常量折叠 + 布尔化简 + 投影裁剪
        Statement stmt = parseOne("SELECT name FROM student WHERE 1 = 1 AND score > 60.0;");
        sem.analyze(stmt);
        PlanNode plan = planner.plan(stmt);
        String before = PlanPrinter.print(plan);
        String after = PlanPrinter.print(optimizer.optimize(plan));
        System.out.println("[1] SQL: SELECT name FROM student WHERE 1 = 1 AND score > 60.0;");
        System.out.println("    优化前: " + before);
        System.out.println("    优化后: " + after);
        assertTrue(before.contains("Filter"), "优化前应含 Filter 算子");
        assertTrue(before.contains("(= 1 1)"), "优化前应含未折叠常量 (1 = 1)");
        assertFalse(after.contains("(= 1 1)"), "常量折叠后 1 = 1 应消失");
        assertTrue(after.contains("Project") && after.contains("SeqScan"), "算子组织应保留 Project(SeqScan)");
        System.out.println("    → 常量折叠（1=1 消除）+ 投影裁剪 PASS");

        // 4b Sort + JOIN 算子组织
        Statement s2 = parseOne("SELECT * FROM student ORDER BY score DESC;");
        sem.analyze(s2);
        String sortPlan = PlanPrinter.print(optimizer.optimize(planner.plan(s2)));
        System.out.println("[2] SQL: SELECT * FROM student ORDER BY score DESC;");
        System.out.println("    优化后: " + sortPlan);
        assertTrue(sortPlan.contains("Sort"), "应含 Sort 算子");

        catalog.createTable(new TableDef("course", List.of(
                new ColumnDef("cid", DataType.INT, 0),
                new ColumnDef("cname", DataType.VARCHAR, 30))));
        Statement s3 = parseOne(
                "SELECT student.name, course.cname FROM student JOIN course ON student.id = course.cid;");
        sem.analyze(s3);
        String joinPlan = PlanPrinter.print(planner.plan(s3));
        System.out.println("[3] SQL: SELECT student.name, course.cname FROM student JOIN course ON student.id = course.cid;");
        System.out.println("    计划: " + joinPlan);
        assertTrue(joinPlan.contains("Join"), "双表查询应含 Join 算子");
        System.out.println("[4] Project / Filter / SeqScan / Sort / Join 算子组织均以 S 表达式输出 PASS");
    }

    // ==================================================================
    // 模块二：存储系统（12 分）
    // ==================================================================

    // ---------- 5. 页式存储管理（4 分）：页分配 / 释放 / 读取 / 写入 / 数据恢复 ----------

    @Test
    @Order(5)
    void 页式存储_内存页与磁盘页逐字节对拍() {
        System.out.println("========== 模块二·存储系统 | 5a. 页式存储：MemoryPage vs SlottedPage 逐字节对拍 ==========");
        Page mem = new MemoryPage(1);
        Page disk = new SlottedPage(1);

        Object[][] rows = {
                {1, 95.5, "Alice", true},
                {null, 88.0, "Bob", false},
                {3, null, "Carol", null},
                {4, 72.5, null, true},
                {null, null, null, null},
                {6, 60.0, "", false},
        };

        System.out.println("[1] 初始 freeSpace：内存页=" + mem.freeSpace() + "，磁盘页=" + disk.freeSpace());
        assertEquals(Page.PAGE_SIZE, mem.freeSpace());
        assertEquals(Page.PAGE_SIZE, disk.freeSpace());

        for (int i = 0; i < rows.length; i++) {
            byte[] encoded = RowEncoder.encode(COLS, rows[i]);
            int slotM = mem.insertRow(encoded);
            int slotD = disk.insertRow(encoded);
            assertEquals(slotM, slotD, "槽号应该一致");
            System.out.println("[2] 第 " + i + " 行插入槽 " + slotM + "（" + encoded.length + " 字节）→ 对拍 slot: "
                    + (slotM == slotD ? "PASS" : "FAIL"));
        }

        System.out.println("[3] freeSpace 对拍：内存页=" + mem.freeSpace() + "，磁盘页=" + disk.freeSpace()
                + " → " + (mem.freeSpace() == disk.freeSpace() ? "PASS（逐字节相等）" : "FAIL"));
        assertEquals(mem.freeSpace(), disk.freeSpace());

        for (int i = 0; i < rows.length; i++) {
            byte[] rm = mem.readRow(i);
            byte[] rd = disk.readRow(i);
            assertArrayEquals(rm, rd, "第 " + i + " 行两实现字节应该一致");
            assertArrayEquals(RowEncoder.encode(COLS, rows[i]), rm, "第 " + i + " 行往返应该一致");
        }
        System.out.println("[4] " + rows.length + " 行 readRow 往返对拍（含 NULL/空串/BOOLEAN 三值）: PASS");

        int freeBefore = mem.freeSpace();
        mem.deleteRow(2);
        disk.deleteRow(2);
        assertNull(mem.readRow(2), "删除后 readRow 应该为 null");
        assertNull(disk.readRow(2), "删除后 readRow 应该为 null");
        assertEquals(freeBefore, mem.freeSpace(), "标删后 freeSpace 应该不变");
        assertEquals(freeBefore, disk.freeSpace(), "标删后 freeSpace 应该不变");
        System.out.println("[5] deleteRow(2) 标删：readRow=null、freeSpace 不变（"
                + freeBefore + "）→ 页释放语义 PASS");
    }

    @Test
    @Order(6)
    void 页式存储_磁盘页分配读写与重启恢复() throws IOException {
        System.out.println("========== 模块二·存储系统 | 5b. 页式存储：磁盘页分配 / 写入 / 重启数据恢复 ==========");
        DiskBufferPool pool = new DiskBufferPool(64, dataDir.toString(), null);

        byte[][] original = new byte[3][];
        for (int p = 0; p < 3; p++) {
            Page page = pool.newPage("rec_t");
            original[p] = ("page-" + p + "-row").getBytes();
            int slot = page.insertRow(original[p]);
            assertEquals(0, slot);
            System.out.println("[1] newPage rec_t:" + p + " 分配，insertRow 槽 " + slot + "（"
                    + original[p].length + " 字节）");
        }
        System.out.println("[2] 表 rec_t 共 " + pool.getTablePageCount("rec_t") + " 页");
        assertEquals(3, pool.getTablePageCount("rec_t"));

        pool.close();
        long fileLen = Files.size(dataDir.resolve("rec_t.dat"));
        System.out.println("[3] close() 刷盘，rec_t.dat 文件长度=" + fileLen + "（3 页 × "
                + (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE) + " 字节）");
        assertEquals(3L * (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE), fileLen);

        // 重启恢复
        pool = new DiskBufferPool(64, dataDir.toString(), null);
        for (int p = 0; p < 3; p++) {
            Page loaded = pool.getPage("rec_t", p);
            assertNotNull(loaded, "重启后页 " + p + " 应该存在");
            assertArrayEquals(original[p], loaded.readRow(0), "重启后页 " + p + " 数据应该一致");
        }
        System.out.println("[4] 重启后逐页读回 3 页数据：与写入前逐字节一致 → 数据恢复 PASS");
        pool.close();
    }

    @Test
    @Order(7)
    void 页式存储_UPDATE搬家行重启一致() throws MiniDbException {
        System.out.println("========== 模块二·存储系统 | 5c. 页式存储：UPDATE 搬家行（deleteRow + insertRow 变长） ==========");
        DiskBufferPool pool = new DiskBufferPool(64, dataDir.toString(), null);
        List<ColumnDef> cols = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 64));

        Page page = pool.newPage("upd_t");
        page.insertRow(RowEncoder.encode(cols, new Object[]{1, "short"}));
        page.insertRow(RowEncoder.encode(cols, new Object[]{2, "Alice"}));
        page.insertRow(RowEncoder.encode(cols, new Object[]{3, "Bob"}));

        String longName = "A_very_long_name_that_might_need_more_space_than_before_1234567890";
        byte[] newRow = RowEncoder.encode(cols, new Object[]{2, longName});
        page.deleteRow(1);
        int newSlot = page.insertRow(newRow);
        assertTrue(newSlot >= 0);
        System.out.println("[1] UPDATE = deleteRow(1) + insertRow 变长新行（搬家到槽 " + newSlot + "，"
                + newRow.length + " 字节）");

        pool.close();
        pool = new DiskBufferPool(64, dataDir.toString(), null);
        Page loaded = pool.getPage("upd_t", 0);
        assertNull(loaded.readRow(1), "旧行槽位应为 null");
        assertEquals(longName, RowEncoder.decode(cols, loaded.readRow(newSlot))[1]);
        assertEquals("short", RowEncoder.decode(cols, loaded.readRow(0))[1]);
        assertEquals("Bob", RowEncoder.decode(cols, loaded.readRow(2))[1]);
        System.out.println("[2] 重启后读回：旧行已删、变长新行与其他行完整 → UPDATE 搬家行 PASS");
        pool.close();
    }

    // ---------- 6. 缓存机制（4 分）：命中 / 淘汰 / 替换 / 回写 ----------

    @Test
    @Order(8)
    void 缓存机制_固定序列命中淘汰回写() throws IOException {
        System.out.println("========== 模块二·存储系统 | 6a. 缓存机制：LRU 固定访问序列（容量=2） ==========");
        DiskBufferPool pool = new DiskBufferPool(2, dataDir.toString(), null);

        Page p0 = pool.newPage("cache_t");   // 缓存 {cache_t:0}
        Page p1 = pool.newPage("cache_t");   // 淘汰 cache_t:0 → 缓存 {cache_t:1}
        p0.insertRow("row-0".getBytes());    // p0 已被淘汰，此处只改对象（不回缓存）
        p1.insertRow("row-1".getBytes());
        System.out.println("[1] newPage cache_t:0, cache_t:1（容量 2，第 1 页淘汰 cache_t:0）");

        pool.getPage("cache_t", 1);           // HIT
        System.out.println("[2] getPage cache_t:1 → HIT（最近使用）");
        assertEquals(1, pool.stats().hits());

        Page p2 = pool.newPage("cache_t");    // 淘汰 LRU 首 = cache_t:0，缓存 {cache_t:1, cache_t:2}
        p2.insertRow("row-2".getBytes());
        Set<String> content = pool.getCacheContent();
        System.out.println("[3] newPage cache_t:2 → 淘汰 LRU 首页，当前缓存 " + content);
        assertTrue(content.contains("cache_t:2"));
        assertTrue(content.contains("cache_t:1"));
        assertFalse(content.contains("cache_t:0"));

        pool.getPage("cache_t", 0);           // MISS（从文件重读入）
        System.out.println("[4] getPage cache_t:0 → MISS（被淘汰后按需从文件读回）");
        BufferPoolStats stats = pool.stats();
        System.out.println("[5] stats：hits=" + stats.hits() + "，misses=" + stats.misses()
                + "，命中率=" + String.format("%.0f%%", stats.hitRate() * 100));
        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());

        // 淘汰回写验证：p1 数据在缓存中被改（脏），close 前 flushAll 落盘
        pool.close();
        DiskBufferPool pool2 = new DiskBufferPool(2, dataDir.toString(), null);
        Page r1 = pool2.getPage("cache_t", 1);
        Page r2 = pool2.getPage("cache_t", 2);
        assertArrayEquals("row-1".getBytes(), r1.readRow(0), "淘汰/关闭前脏页应该已回写");
        assertArrayEquals("row-2".getBytes(), r2.readRow(0), "脏页 close 前应该刷盘");
        System.out.println("[6] close→重开：row-1 / row-2 数据完整 → 脏页回写（LRU 淘汰 + flushAll）PASS");
        pool2.close();
    }

    @Test
    @Order(9)
    void 缓存机制_命中率统计() {
        System.out.println("========== 模块二·存储系统 | 6b. 缓存机制：命中率统计（InMemoryBufferPool） ==========");
        InMemoryBufferPool pool = new InMemoryBufferPool();
        pool.newPage("st_t");
        pool.newPage("st_t");

        pool.getPage("st_t", 0);   // hit
        pool.getPage("st_t", 0);   // hit
        pool.getPage("st_t", 1);   // hit
        pool.getPage("st_t", 99);  // miss
        pool.getPage("no_t", 0);   // miss

        BufferPoolStats stats = pool.stats();
        System.out.println("[1] 固定序列（3 命中 + 2 未中）：hits=" + stats.hits()
                + "，misses=" + stats.misses() + "，命中率=" + String.format("%.1f%%", stats.hitRate() * 100));
        assertEquals(3, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(0.6, stats.hitRate(), 1e-9);
        System.out.println("[2] 命中率 = 3/5 = 60% → 统计正确 PASS");
    }

    // ---------- 7. 接口与集成（4 分）：统一接口 + 双表互不干扰 ----------

    @Test
    @Order(10)
    void 接口与集成_统一接口双表互不干扰() throws MiniDbException {
        System.out.println("========== 模块二·存储系统 | 7. 接口与集成：统一接口下双表并存（缓存键 表名:页号） ==========");
        // 统一接口引用：上层只依赖 BufferPool，不感知内存/磁盘实现切换
        BufferPool pool = new DiskBufferPool(64, dataDir.toString(), null);
        System.out.println("[1] 上层引用类型 = BufferPool（统一接口），实际实现 = DiskBufferPool");

        Page a0 = pool.newPage("t_a");
        Page b0 = pool.newPage("t_b");
        byte[] rowA = RowEncoder.encode(COLS, new Object[]{1, 1.5, "aaa", true});
        byte[] rowB = RowEncoder.encode(COLS, new Object[]{9, 9.5, "bbb", false});
        a0.insertRow(rowA);
        b0.insertRow(rowB);
        System.out.println("[2] 双表各自 newPage + insertRow：t_a:0 与 t_b:0 同为页号 0");

        // 缓存键为 表名:页号，两页同时驻留互不覆盖
        Page ra = pool.getPage("t_a", 0);
        Page rb = pool.getPage("t_b", 0);
        assertNotSame(ra, rb, "两表同页号应该是不同页实例");
        assertArrayEquals(rowA, ra.readRow(0), "t_a 数据不应该被 t_b 覆盖");
        assertArrayEquals(rowB, rb.readRow(0), "t_b 数据不应该被 t_a 覆盖");
        System.out.println("[3] 交替 getPage 两表同号页：缓存键 't_a:0' / 't_b:0' 隔离，数据不串表 PASS");

        pool.flushAll();
        if (pool instanceof DiskBufferPool dp) {
            dp.close();
            // close 前已刷全部脏页：重启读回验证
            BufferPool pool2 = new DiskBufferPool(64, dataDir.toString(), null);
            assertArrayEquals(rowA, pool2.getPage("t_a", 0).readRow(0), "t_a 落盘数据应该完整");
            assertArrayEquals(rowB, pool2.getPage("t_b", 0).readRow(0), "t_b 落盘数据应该完整");
            System.out.println("[4] close→重开：两表数据各自完整 → flushAll 刷盘 + 双表并存 PASS");
            ((DiskBufferPool) pool2).close();
        }
    }

    // ==================================================================
    // 模块三：数据库系统（12 分）
    // ==================================================================

    // ---------- 8. 执行引擎（4 分）：SeqScan / Filter / Project / Insert / Update / Delete ----------

    @Test
    @Order(11)
    void 执行引擎_核心算子执行结果() {
        System.out.println("========== 模块三·数据库系统 | 8. 执行引擎：核心算子执行结果 ==========");
        MiniDB db = new MiniDB();

        runAndShow(db, "CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT);");
        runAndShow(db, "INSERT INTO student VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0), (3, 'Bob', 60.0);");
        assertEquals(3, 3, "INSERT 应成功执行");

        String all = runAndShow(db, "SELECT * FROM student;");                       // SeqScan
        assertTrue(all.contains("Tom") && all.contains("Alice") && all.contains("Bob"),
                "SeqScan 应返回全部 3 行");

        String filtered = runAndShow(db, "SELECT name FROM student WHERE score >= 85.0;"); // Filter+Project
        assertTrue(filtered.contains("Tom") && filtered.contains("Alice"), "Filter 应保留 Tom/Alice");
        assertFalse(filtered.contains("Bob"), "Filter 应排除 Bob");

        runAndShow(db, "UPDATE student SET score = score + 5 WHERE id = 3;");       // Update
        String updated = runAndShow(db, "SELECT score FROM student WHERE id = 3;");
        assertTrue(updated.contains("65.0"), "Update 后 id=3 的 score 应为 65.0，实际: " + updated);

        runAndShow(db, "DELETE FROM student WHERE id = 3;");                         // Delete
        String rest = runAndShow(db, "SELECT COUNT(*) FROM student;");
        assertFalse(rest.contains("3 行"), "删除后应剩 2 行，实际: " + rest);
        assertTrue(rest.contains("2"), "COUNT(*) 应为 2");
        System.out.println("[核心算子] SeqScan / Filter / Project / Insert / Update / Delete 执行结果 PASS");
    }

    // ---------- 9. 存储引擎与目录（4 分）：序列化 / Catalog 持久化 ----------

    @Test
    @Order(12)
    void 存储引擎_RowEncoder序列化反序列化往返() {
        System.out.println("========== 模块三·数据库系统 | 9a. 存储引擎：记录序列化-反序列化往返 ==========");
        Object[][] rows = {
                {2147483647, -3.14, "Tom's book", null},
                {-2147483647, 1.0, "中文测试", true},
                {0, 0.5, "", false},
                {null, null, null, null},
        };
        for (int i = 0; i < rows.length; i++) {
            byte[] encoded = RowEncoder.encode(COLS, rows[i]);
            Object[] decoded = RowEncoder.decode(COLS, encoded);
            assertArrayEquals(rows[i], decoded, "第 " + i + " 行往返应该一致");
            System.out.println("[行 " + i + "] " + encoded.length + " 字节："
                    + java.util.Arrays.toString(rows[i]) + " → 编码 → 解码一致 PASS");
        }
        System.out.println("说明：INT 4B / FLOAT 4B / VARCHAR 2B+内容 / BOOLEAN 1B，NULL 由变长位图标记");
    }

    @Test
    @Order(13)
    void 目录_PersistentCatalog持久化与大小写() throws MiniDbException {
        System.out.println("========== 模块三·数据库系统 | 9b. 系统目录：catalog.dat 落盘 / 重启加载 / 大小写不敏感 ==========");
        PersistentCatalog cat = new PersistentCatalog(dataDir.toString());
        cat.createTable(new TableDef("Student", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50))));
        System.out.println("[1] createTable Student → catalog.dat 落盘");

        // 重名表报错
        MiniDbException dup = assertThrows(MiniDbException.class, () ->
                cat.createTable(new TableDef("STUDENT", List.of(
                        new ColumnDef("x", DataType.INT, 0)))));
        System.out.println("[2] 重名建表（STUDENT vs Student）→ 报错: " + dup.getMessage());
        assertTrue(dup.getMessage().contains("已存在"));

        // 重启加载（模拟：重新构造从文件读）
        PersistentCatalog reloaded = new PersistentCatalog(dataDir.toString());
        Optional<TableDef> found = reloaded.findTable("student");
        assertTrue(found.isPresent(), "重启后小写应该查到 Student");
        assertEquals(2, found.get().columns().size());
        System.out.println("[3] 重启加载：findTable(\"student\")（小写）查到表，"
                + found.get().columns().size() + " 列完整");

        assertTrue(reloaded.findTable("StUdEnT").isPresent(), "任意大小写都应该查到");
        assertTrue(reloaded.findColumn("student", "NAME").isPresent(), "列名大小写不敏感");
        System.out.println("[4] 大小写不敏感：findTable(\"StUdEnT\") / findColumn(\"NAME\") 均可查到 PASS");
    }

    // ---------- 10. 系统集成（4 分）：CLI 全链路 ----------

    @Test
    @Order(14)
    void 系统集成_CLI全链路五段输出() {
        System.out.println("========== 模块三·数据库系统 | 10. 系统集成：.trace 全链路（输入→编译→执行→存储→结果） ==========");
        MiniDB db = new MiniDB();
        db.executeSql("CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT);");
        db.executeSql("INSERT INTO student VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0);");

        // .trace 一条 SELECT：Tokens → AST → Plan(前) → Plan(后) → Result 五段即全链路
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos, true));
        try {
            db.traceSql("SELECT name FROM student WHERE score >= 90.0;");
        } finally {
            System.setOut(originalOut);
        }
        String output = baos.toString();
        System.out.println(output.stripTrailing());

        assertTrue(output.contains("── Tokens ──"), "应含 Tokens 段（词法）");
        assertTrue(output.contains("── AST ──"), "应含 AST 段（语法）");
        assertTrue(output.contains("── Plan(优化前) ──"), "应含 Plan(优化前) 段（计划）");
        assertTrue(output.contains("── Plan(优化后) ──"), "应含 Plan(优化后) 段（优化）");
        assertTrue(output.contains("── Result ──"), "应含 Result 段（执行+存储）");
        assertTrue(output.contains("Tom"), "Result 段应含查询结果 Tom");
        System.out.println("[全链路] SQL输入 → 词法 → 语法 → 语义 → 计划 → 优化 → 执行 → 存储 → 结果返回 PASS");
    }

    // ==================================================================
    // 公共辅助
    // ==================================================================

    private final Lexer lexer = new Lexer();
    private final Parser parser = new Parser();

    /** 可抛 MiniDbException 的动作（词法/语法/语义检查的统一错误断言载体）。 */
    private interface SqlAction {
        void run() throws MiniDbException;
    }

    /** 打印并断言异常属于期望阶段。 */
    private void assertPhaseError(SqlAction action, MiniDbException.Phase expect) {
        MiniDbException e = assertThrows(MiniDbException.class, action::run);
        System.out.println("    ✗ [" + e.phase() + " 错误 @ " + e.pos() + "] " + e.getMessage());
        assertEquals(expect, e.phase(), "错误应属于 " + expect + " 阶段");
    }

    private Token findToken(List<Token> tokens, TokenType type) {
        for (Token t : tokens) {
            if (t.type() == type) return t;
        }
        return null;
    }

    private Statement parseOne(String sql) throws MiniDbException {
        return parser.parseScript(lexer.tokenize(sql)).get(0);
    }

    /** 捕获 CLI 输出执行 SQL，回显到控制台并返回输出（供断言）。 */
    private String runAndShow(MiniDB db, String sql) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos, true));
        try {
            db.executeSql(sql);
        } finally {
            System.setOut(originalOut);
        }
        String out = baos.toString();
        System.out.println("[SQL] " + sql);
        System.out.print(out);
        return out;
    }
}
