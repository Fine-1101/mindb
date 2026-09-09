package com.minidb.integration;

import com.minidb.buffer.BufferPool;
import com.minidb.buffer.InMemoryBufferPool;
import com.minidb.catalog.*;
import com.minidb.common.MiniDbException;
import com.minidb.engine.Engine;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.parser.Parser;
import com.minidb.plan.*;
import com.minidb.planner.Optimizer;
import com.minidb.planner.Planner;
import com.minidb.semantic.SemanticAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 优化等价性参数化测试：10 组 SQL，executeQuery(optimize(plan(s))) 与 executeQuery(plan(s)) 结果集相等。
 * 接入 B 的 Optimizer 真实规则（常量折叠/布尔化简/冗余消除）。
 */
class OptimizationEquivalenceTest {

    private Catalog catalog;
    private BufferPool pool;
    private Engine engine;
    private Lexer lexer;
    private Parser parser;
    private SemanticAnalyzer analyzer;
    private Planner planner;
    private Optimizer optimizer;

    @BeforeEach
    void setup() throws MiniDbException {
        catalog = new MemoryCatalog();
        pool = new InMemoryBufferPool();
        engine = new Engine(catalog, pool);
        lexer = new Lexer();
        parser = new Parser();
        analyzer = new SemanticAnalyzer(catalog);
        planner = new Planner(catalog);
        optimizer = new Optimizer();

        // 建表 + 插入数据
        runDdl("CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT)");
        runDdl("INSERT INTO student VALUES (1, 'Tom', 90.5)");
        runDdl("INSERT INTO student VALUES (2, 'Alice', 85.0)");
        runDdl("INSERT INTO student VALUES (3, 'Bob', 60.0)");
        runDdl("INSERT INTO student VALUES (4, 'Charlie', 77.7)");
        runDdl("INSERT INTO student VALUES (5, 'Dave', 95.0)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM student",
            "SELECT id FROM student",
            "SELECT name, score FROM student",
            "SELECT * FROM student WHERE score >= 90.0",
            "SELECT * FROM student WHERE id = 3",
            "SELECT * FROM student WHERE score < 70.0",
            "SELECT name FROM student WHERE score > 80.0",
            "SELECT * FROM student WHERE id > 2 AND score >= 70.0",
            "SELECT * FROM student WHERE score >= 90.0 OR id = 2",
            "SELECT id, name FROM student WHERE score >= 60.0 AND score <= 90.0"
    })
    void testOptimizationEquivalence(String sql) throws MiniDbException {
        List<Token> tokens = lexer.tokenize(sql);
        var stmts = parser.parseScript(tokens);
        assertEquals(1, stmts.size());
        var stmt = stmts.get(0);
        analyzer.analyze(stmt);

        PlanNode plan = planner.plan(stmt);
        PlanNode optimized = optimizer.optimize(plan);

        List<Object[]> baseResults = engine.executeQuery(plan);
        List<Object[]> optResults = engine.executeQuery(optimized);

        assertEquals(baseResults.size(), optResults.size(),
                "优化前后结果行数应相等: " + sql);
        for (int i = 0; i < baseResults.size(); i++) {
            assertArrayEquals(baseResults.get(i), optResults.get(i),
                    "第 " + i + " 行应相等: " + sql);
        }
    }

    private void runDdl(String sql) throws MiniDbException {
        List<Token> tokens = lexer.tokenize(sql);
        var stmts = parser.parseScript(tokens);
        for (var stmt : stmts) {
            analyzer.analyze(stmt);
            PlanNode plan = planner.plan(stmt);
            if (stmt instanceof com.minidb.ast.SelectStmt) {
                engine.executeQuery(plan);
            } else {
                engine.execute(plan);
            }
        }
    }
}
