package com.minidb.engine;

import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.Statement;
import com.minidb.buffer.BufferPool;
import com.minidb.buffer.InMemoryBufferPool;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.parser.Parser;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.InsertPlan;
import com.minidb.semantic.SemanticAnalyzer;
import com.minidb.storage.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Engine 集成测试：端到端 / 跨页 / 错误传播 / 全链冒烟。
 */
class EngineTest {

    // ============================================================
    // 辅助方法
    // ============================================================

    /** 全链处理一条 SQL：Lexer → Parser → Semantic → 返回 Statement。 */
    private Statement analyzeSql(Catalog catalog, String sql) throws MiniDbException {
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize(sql);
        Parser parser = new Parser();
        Statement stmt = parser.parse(tokens);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        analyzer.analyze(stmt);
        return stmt;
    }

    /** 将语义通过的 Statement 转为 PlanNode。 */
    private com.minidb.plan.PlanNode toPlan(Statement stmt) {
        return switch (stmt) {
            case CreateTableStmt s -> new CreateTablePlan(
                    new TableDef(s.tableName(), s.columns()));
            case InsertStmt s -> {
                List<String> targetCols = s.columns() != null
                        ? s.columns().stream().map(c -> c.column()).toList()
                        : List.of();
                yield new InsertPlan(s.tableName(), targetCols, s.rows());
            }
            default -> throw new IllegalArgumentException("不支持的语句类型");
        };
    }

    // ============================================================
    // 交付 2 测试：Engine 端到端（内存页）
    // ============================================================

    @Test
    void testCreateTableAndInsert() throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);

        // CREATE TABLE users (id INT, name VARCHAR(50))
        engine.execute(new CreateTablePlan(
                new TableDef("users", List.of(
                        new ColumnDef("id", DataType.INT, 0),
                        new ColumnDef("name", DataType.VARCHAR, 50)))));

        // 验证 catalog 中存在 users 表
        assertTrue(catalog.findTable("users").isPresent(), "CREATE TABLE 后 catalog 应包含 users 表");

        // INSERT 3 行（指定列序 id, name）
        engine.execute(new InsertPlan("users", List.of("id", "name"), List.of(
                List.of(new com.minidb.ast.Literal(1, DataType.INT, null),
                        new com.minidb.ast.Literal("Alice", DataType.VARCHAR, null)),
                List.of(new com.minidb.ast.Literal(2, DataType.INT, null),
                        new com.minidb.ast.Literal("Bob", DataType.VARCHAR, null)),
                List.of(new com.minidb.ast.Literal(3, DataType.INT, null),
                        new com.minidb.ast.Literal("Charlie", DataType.VARCHAR, null))
        )));

        // 逐槽 readRow + decode 验证
        List<ColumnDef> cols = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50));

        List<Integer> pageIds = engine.getTablePageIds("users");
        assertEquals(1, pageIds.size(), "3 行数据应在 1 页内");

        Page page = pool.getPage("users", pageIds.get(0));
        assertNotNull(page);

        Object[] row0 = RowEncoder.decode(cols, page.readRow(0));
        assertEquals(1, row0[0]);
        assertEquals("Alice", row0[1]);

        Object[] row1 = RowEncoder.decode(cols, page.readRow(1));
        assertEquals(2, row1[0]);
        assertEquals("Bob", row1[1]);

        Object[] row2 = RowEncoder.decode(cols, page.readRow(2));
        assertEquals(3, row2[0]);
        assertEquals("Charlie", row2[1]);
    }

    // ============================================================
    // targetColumns 列序严格一致：(b, a) 的 INSERT 按 b,a 落盘
    // ============================================================

    @Test
    void testTargetColumnOrderStrict() throws MiniDbException {
        // 表定义序: (a INT, b VARCHAR(50))
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);

        engine.execute(new CreateTablePlan(
                new TableDef("t1", List.of(
                        new ColumnDef("a", DataType.INT, 0),
                        new ColumnDef("b", DataType.VARCHAR, 50)))));

        // INSERT 指定列序 (b, a)：值按 b=hello,a=42 书写
        engine.execute(new InsertPlan("t1", List.of("b", "a"), List.of(
                List.of(new com.minidb.ast.Literal("hello", DataType.VARCHAR, null),
                        new com.minidb.ast.Literal(42, DataType.INT, null)),
                List.of(new com.minidb.ast.Literal("world", DataType.VARCHAR, null),
                        new com.minidb.ast.Literal(99, DataType.INT, null))
        )));

        // 按 targetColumns 即 (b, a) 序解码 → 落盘列序必须与 targetColumns 严格一致
        List<ColumnDef> targetCols = List.of(
                new ColumnDef("b", DataType.VARCHAR, 50),
                new ColumnDef("a", DataType.INT, 0));

        List<Integer> pageIds = engine.getTablePageIds("t1");
        Page page = pool.getPage("t1", pageIds.get(0));

        Object[] row0 = RowEncoder.decode(targetCols, page.readRow(0));
        assertEquals("hello", row0[0], "第1列落盘应为 b='hello'");
        assertEquals(42, row0[1],      "第2列落盘应为 a=42");

        Object[] row1 = RowEncoder.decode(targetCols, page.readRow(1));
        assertEquals("world", row1[0], "第1列落盘应为 b='world'");
        assertEquals(99, row1[1],      "第2列落盘应为 a=99");

        // 反面验证：检查原始字节证明落盘序是 (b=VARCHAR, a=INT) 而非表定义序 (a=INT, b=VARCHAR)
        // 编码格式: [1B位图][VARCHAR: 2B长度+内容][INT: 4B]
        // 如果按表定义序(INT在前)编码，第2字节应该是 INT 的高位(0x00)；
        // 但实际是 VARCHAR 长度前缀的高字节
        byte[] raw = page.readRow(0);
        // bitmap=0(无非null), 接下来是 VARCHAR(5)"hello": 0x00,0x05,'h','e','l','l','o', 然后 INT(42): 0,0,0,42
        assertEquals(0x00, raw[1], "VARCHAR 长度高字节应为 0x00");
        assertEquals(0x05, raw[2], "VARCHAR 长度低字节应为 0x05(='hello'长度5)");
        assertEquals('h', (char) raw[3], "VARCHAR 内容首字节应为 'h'");
    }

    // ============================================================
    // DeletePlan 执行：无 WHERE 全删
    // ============================================================

    @Test
    void testDeletePlanExecutes() throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);

        // 建表 + 插入 3 行
        engine.execute(new CreateTablePlan(
                new TableDef("t1", List.of(
                        new ColumnDef("id", DataType.INT, 0)))));
        engine.execute(new InsertPlan("t1", List.of("id"), List.of(
                List.of(new com.minidb.ast.Literal(1, DataType.INT, null)),
                List.of(new com.minidb.ast.Literal(2, DataType.INT, null)),
                List.of(new com.minidb.ast.Literal(3, DataType.INT, null))
        )));

        // DELETE FROM t1（无 WHERE → 全删）
        engine.execute(new DeletePlan("t1", null));

        // 验证：扫描结果应为空
        List<ColumnDef> cols = List.of(new ColumnDef("id", DataType.INT, 0));
        List<Integer> pageIds = engine.getTablePageIds("t1");
        Page page = pool.getPage("t1", pageIds.get(0));
        com.minidb.storage.MemoryPage mp = (com.minidb.storage.MemoryPage) page;
        assertEquals(0, mp.getRowCount(), "全删后应无存活行");
    }

    // ============================================================
    // 跨页测试
    // ============================================================

    @Test
    void testCrossPage() throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);

        // CREATE TABLE t1 (id INT)
        engine.execute(new CreateTablePlan(
                new TableDef("t1", List.of(
                        new ColumnDef("id", DataType.INT, 0)))));

        // 计算跨页阈值：
        // 每行编码 = 1B(null位图) + 4B(INT) = 5B
        // 每行占用 = 5B + 4B(SLOT_ENTRY_SIZE) = 9B
        // PAGE_SIZE = 4096, 每页最多 floor(4096/9) = 455 行
        // 插入 500 行 → 需要 2 页
        int rowsPerPage = Page.PAGE_SIZE / (5 + Page.SLOT_ENTRY_SIZE); // 455
        int totalRows = 500;
        assertTrue(totalRows > rowsPerPage, "总行数应超过单页容量");

        List<List<com.minidb.ast.Expression>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < totalRows; i++) {
            rows.add(List.of(new com.minidb.ast.Literal(i, DataType.INT, null)));
        }

        engine.execute(new InsertPlan("t1", List.of("id"), rows));

        // 验证：两页产生
        List<Integer> pageIds = engine.getTablePageIds("t1");
        assertEquals(2, pageIds.size(), "500 行应产生 2 页");

        // 第一页满（455 行）
        Page page0 = pool.getPage("t1", pageIds.get(0));
        assertNotNull(page0);
        assertEquals(rowsPerPage, ((com.minidb.storage.MemoryPage) page0).getRowCount(),
                "第一页应满");

        // 第二页有剩余（45 行）
        Page page1 = pool.getPage("t1", pageIds.get(1));
        assertNotNull(page1);
        assertEquals(totalRows - rowsPerPage, ((com.minidb.storage.MemoryPage) page1).getRowCount(),
                "第二页应有剩余行");

        // 验证数据完整性：第一页首行和末行
        List<ColumnDef> cols = List.of(new ColumnDef("id", DataType.INT, 0));
        Object[] firstRow = RowEncoder.decode(cols, page0.readRow(0));
        assertEquals(0, firstRow[0]);

        Object[] lastRowPage0 = RowEncoder.decode(cols, page0.readRow(rowsPerPage - 1));
        assertEquals(rowsPerPage - 1, lastRowPage0[0]);

        // 第二页首行
        Object[] firstRowPage1 = RowEncoder.decode(cols, page1.readRow(0));
        assertEquals(rowsPerPage, firstRowPage1[0]);
    }

    // ============================================================
    // 错误传播测试
    // ============================================================

    @Test
    void testErrorPropagation() throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);

        // 先建表
        engine.execute(new CreateTablePlan(
                new TableDef("t1", List.of(
                        new ColumnDef("id", DataType.INT, 0)))));

        // 构造类型不匹配的 INSERT: 向 INT 列插入字符串
        InsertPlan badInsert = new InsertPlan("t1", List.of("id"), List.of(
                List.of(new com.minidb.ast.Literal("hello", DataType.VARCHAR, null))
        ));

        // Semantic 拒绝
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        // 需要先让 catalog 有表（已经 create 了），构造 AST
        InsertStmt badStmt = new InsertStmt("t1", null,
                List.of(List.of(new com.minidb.ast.Literal("hello", DataType.VARCHAR, null))),
                new com.minidb.common.Position(1, 1));

        // analyze 抛错——断言阶段为 SEMANTIC、消息含"类型不匹配"
        MiniDbException semanticEx = assertThrows(MiniDbException.class,
                () -> analyzer.analyze(badStmt),
                "Semantic 应拒绝类型不匹配的 INSERT");
        assertEquals(MiniDbException.Phase.SEMANTIC, semanticEx.phase(),
                "异常阶段应为 SEMANTIC");
        assertTrue(semanticEx.getMessage().contains("类型不匹配"),
                "错误消息应包含'类型不匹配'");

        // execute 未被调用 → 页计数不变
        List<Integer> pageIds = engine.getTablePageIds("t1");
        assertTrue(pageIds.isEmpty(), "Semantic 拒绝后不应产生任何页");

        // BufferPool 也无此表的数据页
        assertNull(pool.getPage("t1", 0), "BufferPool 不应有数据页");
    }

    // ============================================================
    // 全链冒烟测试（D2 里程碑预演）
    // ============================================================

    @Test
    void testFullChainSmoke() throws Exception {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);
        Lexer lexer = new Lexer();
        Parser parser = new Parser();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);

        // ---- 1. CREATE TABLE users (id INT, name VARCHAR(50)) ----
        String createSql = "CREATE TABLE users (id INT, name VARCHAR(50))";
        List<Token> tokens1 = lexer.tokenize(createSql);
        Statement stmt1 = parser.parse(tokens1);
        analyzer.analyze(stmt1);
        engine.execute(toPlan(stmt1));
        assertTrue(catalog.findTable("users").isPresent(), "全链: CREATE TABLE 后表应存在");

        // ---- 2. INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob') ----
        String insertSql = "INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob')";
        List<Token> tokens2 = lexer.tokenize(insertSql);
        Statement stmt2 = parser.parse(tokens2);
        analyzer.analyze(stmt2);
        engine.execute(toPlan(stmt2));

        // 验证数据
        List<ColumnDef> cols = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50));
        List<Integer> pageIds = engine.getTablePageIds("users");
        assertFalse(pageIds.isEmpty(), "INSERT 后应有数据页");

        Page page = pool.getPage("users", pageIds.get(0));
        assertNotNull(page);

        Object[] row0 = RowEncoder.decode(cols, page.readRow(0));
        assertEquals(1, row0[0]);
        assertEquals("Alice", row0[1]);

        Object[] row1 = RowEncoder.decode(cols, page.readRow(1));
        assertEquals(2, row1[0]);
        assertEquals("Bob", row1[1]);

        // ---- 3. 故意错的语句验证报错链 ----

        // 3a. Lexer 错误: 未闭合字符串——断言阶段 LEXER
        MiniDbException lexerEx = assertThrows(MiniDbException.class,
                () -> lexer.tokenize("SELECT * FROM t WHERE name = 'unterminated"),
                "全链: Lexer 应拒绝未闭合字符串");
        assertEquals(MiniDbException.Phase.LEXER, lexerEx.phase(),
                "全链: 词法错误阶段应为 LEXER");

        // 3b. Parser 错误: 缺少 FROM——断言阶段 PARSER
        MiniDbException parserEx = assertThrows(MiniDbException.class,
                () -> parser.parse(lexer.tokenize("SELECT * WHERE x = 1")),
                "全链: Parser 应拒绝缺少 FROM 的 SELECT");
        assertEquals(MiniDbException.Phase.PARSER, parserEx.phase(),
                "全链: 语法错误阶段应为 PARSER");

        // 3c. Semantic 错误: 表不存在——断言阶段 SEMANTIC
        SemanticAnalyzer analyzer2 = new SemanticAnalyzer(catalog);
        MiniDbException semanticEx2 = assertThrows(MiniDbException.class,
                () -> analyzer2.analyze(parser.parse(
                        lexer.tokenize("SELECT * FROM nonexistent"))),
                "全链: Semantic 应拒绝不存在的表");
        assertEquals(MiniDbException.Phase.SEMANTIC, semanticEx2.phase(),
                "全链: 语义错误阶段应为 SEMANTIC");
    }
}
