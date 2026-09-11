package com.minidb.integration;

import com.minidb.ast.*;
import com.minidb.buffer.BufferPool;
import com.minidb.buffer.InMemoryBufferPool;
import com.minidb.catalog.*;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.engine.Engine;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.parser.Parser;
import com.minidb.plan.*;
import com.minidb.semantic.SemanticAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 聚合函数模块全面测试：Parser → Semantic → Planner → Engine 端到端。
 *
 * <p>覆盖：
 * <ul>
 *   <li>Parser：COUNT(*)/COUNT(col)/SUM/AVG/MIN/MAX 解析；IDENT( 模式；列名 count 不带括号时仍为标识符</li>
 *   <li>Semantic：聚合函数合法性检查；聚合与普通列混写拒绝；SUM/AVG 非数值列拒绝；WHERE 中出现聚合拒绝</li>
 *   <li>Planner：AggregatePlan 生成；SeqScan → Filter → AggregatePlan 结构</li>
 *   <li>Engine/AggregateExecutor：五函数累计；空表语义（COUNT→0, SUM→0, AVG/MIN/MAX→0）</li>
 *   <li>WHERE + 聚合组合</li>
 *   <li>DISTINCT 去重</li>
 *   <li>端到端：MiniDB CLI 全链</li>
 * </ul>
 */
class AggregateFunctionTest {

    private Lexer lexer;
    private Parser parser;
    private Catalog catalog;
    private SemanticAnalyzer semantic;
    private Planner planner;
    private Engine engine;
    private BufferPool pool;

    @BeforeEach
    void setUp() throws MiniDbException {
        lexer = new Lexer();
        parser = new Parser();
        catalog = new MemoryCatalog();
        semantic = new SemanticAnalyzer(catalog);
        planner = new Planner();
        pool = new InMemoryBufferPool();
        engine = new Engine(catalog, pool);

        // 建表 student(id INT, name VARCHAR(50), score FLOAT)
        catalog.createTable(new TableDef("student", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50),
                new ColumnDef("score", DataType.FLOAT, 0))));

        // 建表 nums(id INT, val INT) 用于 INT 聚合
        catalog.createTable(new TableDef("nums", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("val", DataType.INT, 0))));

        // 初始化 Engine 的表页映射
        engine.execute(new CreateTablePlan(new TableDef("t1", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("v", DataType.INT, 0)))));
    }

    // ================================================================
    // Parser 测试
    // ================================================================

    @Test
    void parseCountStar() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT COUNT(*) FROM student;");
        assertNotNull(stmt.columns());
        assertEquals(1, stmt.columns().size());
        assertInstanceOf(FuncCall.class, stmt.columns().get(0));
        FuncCall fc = (FuncCall) stmt.columns().get(0);
        assertEquals("COUNT", fc.func());
        assertNull(fc.arg(), "COUNT(*) 的 arg 应为 null");
    }

    @Test
    void parseCountColumn() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT COUNT(score) FROM student;");
        FuncCall fc = (FuncCall) stmt.columns().get(0);
        assertEquals("COUNT", fc.func());
        assertNotNull(fc.arg());
        assertInstanceOf(ColumnRef.class, fc.arg());
        assertEquals("score", ((ColumnRef) fc.arg()).column());
    }

    @Test
    void parseAllAggregateFunctions() throws Exception {
        // SUM, AVG, MIN, MAX 各自解析正确
        for (String sql : new String[]{
                "SELECT SUM(score) FROM student;",
                "SELECT AVG(score) FROM student;",
                "SELECT MIN(score) FROM student;",
                "SELECT MAX(score) FROM student;"
        }) {
            SelectStmt stmt = (SelectStmt) parse(sql);
            assertInstanceOf(FuncCall.class, stmt.columns().get(0));
        }
    }

    @Test
    void parseMultipleAggregates() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT COUNT(*), SUM(score), AVG(score) FROM student;");
        assertEquals(3, stmt.columns().size());
        assertInstanceOf(FuncCall.class, stmt.columns().get(0));
        assertInstanceOf(FuncCall.class, stmt.columns().get(1));
        assertInstanceOf(FuncCall.class, stmt.columns().get(2));
    }

    @Test
    void parseColumnNamedCountWithoutParens() throws Exception {
        // count 不带括号时仍为普通标识符（列引用）
        // 建一个含 count 列的表
        Catalog cat = new MemoryCatalog();
        cat.createTable(new TableDef("t", List.of(
                new ColumnDef("count", DataType.INT, 0))));
        SemanticAnalyzer sa = new SemanticAnalyzer(cat);
        SelectStmt stmt = (SelectStmt) parse("SELECT count FROM t;");
        assertEquals(1, stmt.columns().size());
        assertInstanceOf(ColumnRef.class, stmt.columns().get(0));
        assertEquals("count", ((ColumnRef) stmt.columns().get(0)).column());
    }

    @Test
    void parseDistinct() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT DISTINCT name FROM student;");
        assertTrue(stmt.distinct());
    }

    @Test
    void parseNonDistinctByDefault() throws Exception {
        SelectStmt stmt = (SelectStmt) parse("SELECT name FROM student;");
        assertFalse(stmt.distinct());
    }

    // ================================================================
    // Semantic 测试
    // ================================================================

    @Test
    void semanticAggregateWithWherePasses() throws Exception {
        // SELECT COUNT(*) FROM student WHERE score >= 90
        Statement stmt = parseAndAnalyze("SELECT COUNT(*) FROM student WHERE score >= 90;");
        assertInstanceOf(SelectStmt.class, stmt);
    }

    @Test
    void semanticMixAggregateAndPlainColumnRejected() throws Exception {
        // SELECT name, COUNT(*) FROM student → SEMANTIC 错误
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyze("SELECT name, COUNT(*) FROM student;"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("混写") || e.getMessage().contains("GROUP BY"));
    }

    @Test
    void semanticSumOnVarcharRejected() throws Exception {
        // SELECT SUM(name) FROM student → name 是 VARCHAR，SUM 要求数值类型
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyze("SELECT SUM(name) FROM student;"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("SUM") || e.getMessage().contains("数值"));
    }

    @Test
    void semanticAvgOnVarcharRejected() throws Exception {
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyze("SELECT AVG(name) FROM student;"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
    }

    @Test
    void semanticMinOnVarcharPasses() throws Exception {
        // MIN/MAX 对 VARCHAR 合法
        analyze("SELECT MIN(name) FROM student;");
    }

    @Test
    void semanticMaxOnVarcharPasses() throws Exception {
        analyze("SELECT MAX(name) FROM student;");
    }

    @Test
    void semanticSumStarRejected() throws Exception {
        // SELECT SUM(*) FROM student → SUM 不支持 * 参数
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyze("SELECT SUM(*) FROM student;"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
    }

    @Test
    void semanticUnknownFunctionRejected() throws Exception {
        // SELECT foo(score) FROM student → 未知函数
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyze("SELECT foo(score) FROM student;"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("未知函数") || e.getMessage().contains("foo"));
    }

    @Test
    void semanticAggregateInWhereRejected() throws Exception {
        // SELECT * FROM student WHERE COUNT(*) > 1 → 聚合不能出现在 WHERE
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyze("SELECT * FROM student WHERE COUNT(*) > 1;"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
    }

    // ================================================================
    // Planner 测试
    // ================================================================

    @Test
    void plannerGeneratesAggregatePlan() throws Exception {
        Statement stmt = parseAndAnalyze("SELECT COUNT(*) FROM student;");
        TableDef tableDef = catalog.findTable("student").orElse(null);
        PlanNode plan = planner.buildPlan(stmt, tableDef);
        assertInstanceOf(AggregatePlan.class, plan);
        AggregatePlan ap = (AggregatePlan) plan;
        assertEquals(1, ap.aggregates().size());
        assertEquals("COUNT", ap.aggregates().get(0).func());
    }

    @Test
    void plannerAggregateWithFilter() throws Exception {
        Statement stmt = parseAndAnalyze("SELECT COUNT(*) FROM student WHERE score >= 90;");
        TableDef tableDef = catalog.findTable("student").orElse(null);
        PlanNode plan = planner.buildPlan(stmt, tableDef);
        // 结构：AggregatePlan → Filter → SeqScan
        assertInstanceOf(AggregatePlan.class, plan);
        AggregatePlan ap = (AggregatePlan) plan;
        assertInstanceOf(Filter.class, ap.input());
        Filter filter = (Filter) ap.input();
        assertInstanceOf(SeqScan.class, filter.child());
    }

    // ================================================================
    // Engine/AggregateExecutor 端到端测试
    // ================================================================

    @Test
    void countStarOnEmptyTable() throws Exception {
        // 空表 COUNT(*) → 0
        engine.execute(new CreateTablePlan(new TableDef("empty_t", List.of(
                new ColumnDef("id", DataType.INT, 0)))));
        List<Object[]> results = runAggregate("SELECT COUNT(*) FROM empty_t;");
        assertEquals(1, results.size(), "聚合应产出 1 行");
        assertEquals(0, results.get(0)[0], "空表 COUNT(*) 应为 0");
    }

    @Test
    void sumOnEmptyTable() throws Exception {
        engine.execute(new CreateTablePlan(new TableDef("empty_t2", List.of(
                new ColumnDef("val", DataType.INT, 0)))));
        List<Object[]> results = runAggregate("SELECT SUM(val) FROM empty_t2;");
        assertEquals(1, results.size());
        assertEquals(0, results.get(0)[0], "空表 SUM 应为 0");
    }

    @Test
    void avgOnEmptyTable() throws Exception {
        engine.execute(new CreateTablePlan(new TableDef("empty_t3", List.of(
                new ColumnDef("val", DataType.INT, 0)))));
        List<Object[]> results = runAggregate("SELECT AVG(val) FROM empty_t3;");
        assertEquals(1, results.size());
        assertEquals(0, results.get(0)[0], "空表 AVG 应为 0");
    }

    @Test
    void minOnEmptyTable() throws Exception {
        engine.execute(new CreateTablePlan(new TableDef("empty_t4", List.of(
                new ColumnDef("val", DataType.INT, 0)))));
        List<Object[]> results = runAggregate("SELECT MIN(val) FROM empty_t4;");
        assertEquals(1, results.size());
        assertEquals(0, results.get(0)[0], "空表 MIN 应为 0");
    }

    @Test
    void maxOnEmptyTable() throws Exception {
        engine.execute(new CreateTablePlan(new TableDef("empty_t5", List.of(
                new ColumnDef("val", DataType.INT, 0)))));
        List<Object[]> results = runAggregate("SELECT MAX(val) FROM empty_t5;");
        assertEquals(1, results.size());
        assertEquals(0, results.get(0)[0], "空表 MAX 应为 0");
    }

    @Test
    void countStarWithData() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 20);
        insertIntoT1(3, 30);
        List<Object[]> results = runAggregate("SELECT COUNT(*) FROM t1;");
        assertEquals(3, results.get(0)[0]);
    }

    @Test
    void sumIntColumn() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 20);
        insertIntoT1(3, 30);
        List<Object[]> results = runAggregate("SELECT SUM(v) FROM t1;");
        assertEquals(60, results.get(0)[0], "SUM(10,20,30) 应为 60");
    }

    @Test
    void avgIntColumn() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 20);
        insertIntoT1(3, 30);
        List<Object[]> results = runAggregate("SELECT AVG(v) FROM t1;");
        assertEquals(20.0, results.get(0)[0], "AVG(10,20,30) 应为 20.0");
    }

    @Test
    void minIntColumn() throws Exception {
        insertIntoT1(1, 30);
        insertIntoT1(2, 10);
        insertIntoT1(3, 20);
        List<Object[]> results = runAggregate("SELECT MIN(v) FROM t1;");
        assertEquals(10, results.get(0)[0], "MIN(30,10,20) 应为 10");
    }

    @Test
    void maxIntColumn() throws Exception {
        insertIntoT1(1, 30);
        insertIntoT1(2, 10);
        insertIntoT1(3, 20);
        List<Object[]> results = runAggregate("SELECT MAX(v) FROM t1;");
        assertEquals(30, results.get(0)[0], "MAX(30,10,20) 应为 30");
    }

    @Test
    void multipleAggregatesInOneQuery() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 20);
        insertIntoT1(3, 30);
        List<Object[]> results = runAggregate("SELECT COUNT(*), SUM(v), AVG(v), MIN(v), MAX(v) FROM t1;");
        assertEquals(1, results.size());
        Object[] row = results.get(0);
        assertEquals(3, row[0], "COUNT");
        assertEquals(60, row[1], "SUM");
        assertEquals(20.0, row[2], "AVG");
        assertEquals(10, row[3], "MIN");
        assertEquals(30, row[4], "MAX");
    }

    @Test
    void aggregateWithWhere() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 20);
        insertIntoT1(3, 30);
        insertIntoT1(4, 40);
        List<Object[]> results = runAggregate("SELECT COUNT(*), SUM(v) FROM t1 WHERE v >= 20;");
        assertEquals(1, results.size());
        assertEquals(3, results.get(0)[0], "COUNT(v>=20) 应为 3");
        assertEquals(90, results.get(0)[1], "SUM(v>=20) 应为 90");
    }

    @Test
    void aggregateWhereFiltersAllRows() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 20);
        // WHERE v > 100 → 无匹配行
        List<Object[]> results = runAggregate("SELECT COUNT(*), SUM(v) FROM t1 WHERE v > 100;");
        assertEquals(1, results.size());
        assertEquals(0, results.get(0)[0], "WHERE 过滤全部行后 COUNT 应为 0");
        assertEquals(0, results.get(0)[1], "WHERE 过滤全部行后 SUM 应为 0");
    }

    // ================================================================
    // DISTINCT 测试
    // ================================================================

    @Test
    void distinctRemovesDuplicates() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 10);  // 重复值
        insertIntoT1(3, 20);
        insertIntoT1(4, 20);  // 重复值
        insertIntoT1(5, 30);

        List<Object[]> results = runQuery("SELECT DISTINCT v FROM t1;");
        assertEquals(3, results.size(), "DISTINCT v 应去重为 3 行");
    }

    @Test
    void distinctOnAllSameValues() throws Exception {
        insertIntoT1(1, 5);
        insertIntoT1(2, 5);
        insertIntoT1(3, 5);

        List<Object[]> results = runQuery("SELECT DISTINCT v FROM t1;");
        assertEquals(1, results.size(), "全部相同值去重后应为 1 行");
        assertEquals(5, results.get(0)[0]);
    }

    // ================================================================
    // 大小写不敏感
    // ================================================================

    @Test
    void aggregateFunctionNameCaseInsensitive() throws Exception {
        insertIntoT1(1, 10);
        insertIntoT1(2, 20);

        // count/Count/COUNT 都应工作
        for (String sql : new String[]{
                "SELECT count(*) FROM t1;",
                "SELECT Count(*) FROM t1;",
                "SELECT COUNT(*) FROM t1;"
        }) {
            List<Object[]> results = runAggregate(sql);
            assertEquals(2, results.get(0)[0]);
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private Statement parse(String sql) throws MiniDbException {
        List<Token> tokens = lexer.tokenize(sql);
        List<Statement> stmts = parser.parseScript(tokens);
        assertEquals(1, stmts.size());
        return stmts.get(0);
    }

    private void analyze(String sql) throws MiniDbException {
        Statement stmt = parse(sql);
        semantic.analyze(stmt);
    }

    private Statement parseAndAnalyze(String sql) throws MiniDbException {
        Statement stmt = parse(sql);
        semantic.analyze(stmt);
        return stmt;
    }

    private List<Object[]> runAggregate(String sql) throws MiniDbException {
        Statement stmt = parseAndAnalyze(sql);
        String tableName = ((SelectStmt) stmt).tableName();
        TableDef tableDef = catalog.findTable(tableName).orElse(null);
        PlanNode plan = planner.buildPlan(stmt, tableDef);
        return engine.executeQuery(plan, (SelectStmt) stmt);
    }

    private List<Object[]> runQuery(String sql) throws MiniDbException {
        return runAggregate(sql);
    }

    private void insertIntoT1(int id, int v) throws MiniDbException {
        engine.execute(new InsertPlan("t1", List.of("id", "v"), List.of(
                List.of(new Literal(id, DataType.INT, null),
                        new Literal(v, DataType.INT, null)))));
    }
}
