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
 * 优化等价性参数化测试：executeQuery(optimize(plan(s))) 与 executeQuery(plan(s)) 结果集相等。
 * 接入 B 的 Optimizer 真实规则（常量折叠/布尔化简/Filter合并/投影裁剪/冗余消除）。
 * 三组：基础 WHERE 类、五特性叠加类（聚合/DISTINCT/GROUP BY/ORDER BY/JOIN 含可折叠 ON）、空表边界。
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

        // 建表 + 插入数据（含重名行触发 DISTINCT/GROUP BY 分组、NULL score 触发聚合空值路径）
        runDdl("CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT)");
        runDdl("INSERT INTO student VALUES (1, 'Tom', 90.5)");
        runDdl("INSERT INTO student VALUES (2, 'Alice', 85.0)");
        runDdl("INSERT INTO student VALUES (3, 'Bob', 60.0)");
        runDdl("INSERT INTO student VALUES (4, 'Charlie', 77.7)");
        runDdl("INSERT INTO student VALUES (5, 'Dave', 95.0)");
        runDdl("INSERT INTO student VALUES (6, 'Tom', 55.0)");
        runDdl("INSERT INTO student VALUES (7, 'NullGuy', NULL)");
        runDdl("CREATE TABLE course (cid INT, cname VARCHAR(30))");
        runDdl("INSERT INTO course VALUES (1, 'DB'), (3, 'OS'), (5, 'Networks')");
        // 空表：聚合/过滤的空集边界
        runDdl("CREATE TABLE empty_t (id INT, name VARCHAR(20), score FLOAT)");
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
        assertOptimizationEquivalent(sql);
    }

    /**
     * 五特性叠加 + 可折叠条件：聚合/DISTINCT/GROUP BY/ORDER BY/JOIN 与
     * 常量折叠（1 = 1 / 2 > 3）、布尔化简（x AND TRUE → x / x OR FALSE → x）、
     * 冗余 Filter 消除（WHERE 1 = 1）叠加，验证优化后结果集不变。
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // 聚合 + WHERE 折叠化简（含 NULL score 行触发聚合空值路径）
            "SELECT COUNT(*), SUM(score), AVG(score), MIN(score), MAX(score) FROM student WHERE score >= 60.0 AND 1 = 1",
            // DISTINCT + OR FALSE 化简（重名 Tom 去重）
            "SELECT DISTINCT name FROM student WHERE score > 50.0 OR 2 > 3",
            // GROUP BY + 冗余 Filter 消除（WHERE 1 = 1）
            "SELECT name, COUNT(*) FROM student WHERE 1 = 1 GROUP BY name",
            // GROUP BY + ORDER BY（Sort 最外层，含聚合列排序）
            "SELECT name, COUNT(*) FROM student GROUP BY name ORDER BY name ASC",
            // ORDER BY 多键 + SELECT *（任意列可作键）
            "SELECT * FROM student ORDER BY score DESC, id ASC",
            // JOIN + ON 常量折叠（1 = 1 折 TRUE 后 AND TRUE 化简，ON 只剩等值条件）
            "SELECT id, name FROM student JOIN course ON student.id = course.cid AND 1 = 1",
            // JOIN + WHERE 折叠叠加
            "SELECT id, name FROM student JOIN course ON student.id = course.cid WHERE student.score >= 70.0 AND 2 = 2",
            // NOT + 折叠叠加
            "SELECT name FROM student WHERE NOT (score < 60.0) AND 3 = 3"
    })
    void testOptimizationEquivalenceRichRules(String sql) throws MiniDbException {
        assertOptimizationEquivalent(sql);
    }

    /** 空表边界：过滤空集、标量聚合空表语义（COUNT→0，SUM/AVG/MIN/MAX→NULL）、分组空集。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM empty_t",
            "SELECT * FROM empty_t WHERE id = 1",
            "SELECT COUNT(*) FROM empty_t",
            "SELECT SUM(score), AVG(score), MIN(score), MAX(score) FROM empty_t",
            "SELECT name, COUNT(*) FROM empty_t GROUP BY name"
    })
    void testOptimizationEquivalenceEmptyTable(String sql) throws MiniDbException {
        assertOptimizationEquivalent(sql);
    }

    /** 核心：优化前后计划各自执行，行数与逐行数据相等。 */
    private void assertOptimizationEquivalent(String sql) throws MiniDbException {
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
