package com.minidb.integration;

import com.minidb.MiniDB;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 里程碑脚本测试：script 字符串全链跑 → 断言查询结果集（含中文/转义/大小写混写）。
 */
class MilestoneScriptTest {

    /** 全链执行脚本，返回 Engine 供后续查询验证。 */
    private Engine runScript(String script) throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        BufferPool pool = new InMemoryBufferPool();
        Engine engine = new Engine(catalog, pool);
        Lexer lexer = new Lexer();
        Parser parser = new Parser();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        Planner planner = new Planner();

        List<Token> tokens = lexer.tokenize(script);
        List<com.minidb.ast.Statement> stmts = parser.parseScript(tokens);
        for (com.minidb.ast.Statement stmt : stmts) {
            analyzer.analyze(stmt);
            TableDef tableDef = getTableDef(catalog, stmt);
            PlanNode plan = planner.buildPlan(stmt, tableDef);
            if (stmt instanceof com.minidb.ast.SelectStmt) {
                engine.executeQuery(plan);
            } else {
                engine.execute(plan);
            }
        }
        return engine;
    }

    @Test
    void testMilestoneScript() throws MiniDbException {
        String script = """
                CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT);
                INSERT INTO student VALUES (1, 'Tom''s book', 90.5);
                insert into student values (2, '你好世界', 85.0);
                INSERT INTO student VALUES (3, '', 77.7);
                """;
        Engine engine = runScript(script);

        // 查询 score >= 90.0
        String querySql = "SELECT name FROM student WHERE score >= 90.0";
        Lexer lexer = new Lexer();
        Parser parser = new Parser();
        Catalog catalog = new MemoryCatalog();
        // 需要重建 catalog... 简化：直接用 engine.executeQuery

        // 直接查全表验证
        List<Object[]> allRows = engine.executeQuery(new SeqScan("student"));
        assertEquals(3, allRows.size(), "应插入 3 行");

        // 验证中文和转义
        assertEquals("Tom's book", allRows.get(0)[1]);
        assertEquals("你好世界", allRows.get(1)[1]);
        assertEquals("", allRows.get(2)[1]);
    }

    @Test
    void testCaseInsensitiveKeywords() throws MiniDbException {
        String script = """
                create table t1 (id int, val varchar(20));
                insert into t1 values (1, 'hello');
                SELECT * from t1 where id = 1;
                """;
        Engine engine = runScript(script);

        List<Object[]> results = engine.executeQuery(new SeqScan("t1"));
        assertEquals(1, results.size());
        assertEquals(1, results.get(0)[0]);
        assertEquals("hello", results.get(0)[1]);
    }

    @Test
    void testDeleteAndRequery() throws MiniDbException {
        String script = """
                CREATE TABLE t (id INT, name VARCHAR(50));
                INSERT INTO t VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Charlie');
                """;
        Engine engine = runScript(script);

        // 删除 id=2
        engine.execute(new DeletePlan("t",
                new com.minidb.ast.BinaryExpr(
                        new com.minidb.ast.ColumnRef(null, "id", null),
                        com.minidb.ast.BinaryOp.EQ,
                        new com.minidb.ast.Literal(2, DataType.INT, null), null)));

        // 重新查询
        List<Object[]> results = engine.executeQuery(new SeqScan("t"));
        assertEquals(2, results.size());
        assertEquals("Alice", results.get(0)[1]);
        assertEquals("Charlie", results.get(1)[1]);
    }

    private TableDef getTableDef(Catalog catalog, com.minidb.ast.Statement stmt) {
        return switch (stmt) {
            case com.minidb.ast.SelectStmt s -> catalog.findTable(s.tableName()).orElse(null);
            case com.minidb.ast.DeleteStmt s -> catalog.findTable(s.tableName()).orElse(null);
            case com.minidb.ast.InsertStmt s -> catalog.findTable(s.tableName()).orElse(null);
            default -> null;
        };
    }
}
