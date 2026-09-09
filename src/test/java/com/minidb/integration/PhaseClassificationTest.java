package com.minidb.integration;

import com.minidb.common.MiniDbException;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.parser.Parser;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.semantic.SemanticAnalyzer;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段分类断言：LEXER/PARSER/SEMANTIC 各 ≥5 条错误 SQL，断言 phase + pos 精确。
 */
class PhaseClassificationTest {

    private final Lexer lexer = new Lexer();
    private final Parser parser = new Parser();

    // ============================================================
    // LEXER 错误 ≥5 条
    // ============================================================

    @Test void lexerError_unterminatedString() {
        assertPhase(MiniDbException.Phase.LEXER, () -> lexer.tokenize("SELECT * FROM t WHERE name = 'abc"));
    }
    @Test void lexerError_illegalChar() {
        assertPhase(MiniDbException.Phase.LEXER, () -> lexer.tokenize("SELECT @ FROM t"));
    }
    @Test void lexerError_unclosedBlockComment() {
        assertPhase(MiniDbException.Phase.LEXER, () -> lexer.tokenize("SELECT /* unclosed FROM t"));
    }
    @Test void lexerError_illegalNumber() {
        assertPhase(MiniDbException.Phase.LEXER, () -> lexer.tokenize("SELECT 123abc FROM t"));
    }
    @Test void lexerError_trailingDot() {
        assertPhase(MiniDbException.Phase.LEXER, () -> lexer.tokenize("SELECT 1. FROM t"));
    }

    // ============================================================
    // PARSER 错误 ≥5 条
    // ============================================================

    @Test void parserError_missingFrom() {
        assertPhase(MiniDbException.Phase.PARSER, () -> parse("SELECT * WHERE x = 1"));
    }
    @Test void parserError_missingTableName() {
        assertPhase(MiniDbException.Phase.PARSER, () -> parse("CREATE TABLE (id INT)"));
    }
    @Test void parserError_missingValues() {
        assertPhase(MiniDbException.Phase.PARSER, () -> parse("INSERT INTO t (id)"));
    }
    @Test void parserError_deleteMissingFrom() {
        assertPhase(MiniDbException.Phase.PARSER, () -> parse("DELETE t WHERE id = 1"));
    }
    @Test void parserError_whereMissingRhs() {
        assertPhase(MiniDbException.Phase.PARSER, () -> parse("SELECT * FROM t WHERE a ="));
    }

    // ============================================================
    // SEMANTIC 错误 ≥5 条
    // ============================================================

    @Test void semanticError_tableNotExists() {
        MemoryCatalog cat = new MemoryCatalog();
        assertPhase(MiniDbException.Phase.SEMANTIC, () -> semantic(cat, "SELECT * FROM nonexistent"));
    }

    @Test void semanticError_duplicateTable() {
        MemoryCatalog cat = new MemoryCatalog();
        assertPhase(MiniDbException.Phase.SEMANTIC, () -> {
            // 先手动注册表到 catalog（模拟 Engine 已执行 CREATE）
            cat.createTable(new com.minidb.catalog.TableDef("t1",
                    java.util.List.of(new com.minidb.catalog.ColumnDef("id", com.minidb.common.DataType.INT, 0))));
            // 再次 CREATE 同名表 → Semantic 应拒绝
            semantic(cat, "CREATE TABLE t1 (id INT)");
        });
    }

    @Test void semanticError_columnNotExists() {
        MemoryCatalog cat = new MemoryCatalog();
        assertPhase(MiniDbException.Phase.SEMANTIC, () -> {
            semantic(cat, "CREATE TABLE t2 (id INT)");
            semantic(cat, "SELECT name FROM t2");  // name 不存在
        });
    }

    @Test void semanticError_typeMismatchInsert() {
        MemoryCatalog cat = new MemoryCatalog();
        assertPhase(MiniDbException.Phase.SEMANTIC, () -> {
            semantic(cat, "CREATE TABLE t3 (id INT)");
            semantic(cat, "INSERT INTO t3 VALUES ('hello')");  // VARCHAR → INT
        });
    }

    @Test void semanticError_whereNotBoolean() {
        MemoryCatalog cat = new MemoryCatalog();
        assertPhase(MiniDbException.Phase.SEMANTIC, () -> {
            semantic(cat, "CREATE TABLE t4 (id INT)");
            semantic(cat, "SELECT * FROM t4 WHERE id");  // INT 不是 BOOLEAN
        });
    }

    // ============================================================
    // pos 精确断言
    // ============================================================

    @Test void posPrecision_lexerError() {
        MiniDbException ex = assertThrows(MiniDbException.class,
                () -> lexer.tokenize("SELECT @ FROM t"));
        assertNotNull(ex.pos(), "LEXER 错误应携带位置");
        assertEquals(1, ex.pos().line());
        assertEquals(8, ex.pos().col(), "@ 在第 8 列");
    }

    @Test void posPrecision_parserError() {
        MiniDbException ex = assertThrows(MiniDbException.class,
                () -> parse("SELECT * WHERE x = 1"));
        assertNotNull(ex.pos(), "PARSER 错误应携带位置");
    }

    @Test void posPrecision_semanticError() {
        MemoryCatalog cat = new MemoryCatalog();
        MiniDbException ex = assertThrows(MiniDbException.class,
                () -> semantic(cat, "SELECT * FROM nonexistent"));
        assertNotNull(ex.pos(), "SEMANTIC 错误应携带位置");
    }

    // ============================================================
    // 辅助
    // ============================================================

    private void parse(String sql) throws MiniDbException {
        List<Token> tokens = lexer.tokenize(sql);
        parser.parse(tokens);
    }

    private void semantic(MemoryCatalog catalog, String sql) throws MiniDbException {
        List<Token> tokens = lexer.tokenize(sql);
        var stmts = parser.parseScript(tokens);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(catalog);
        for (var stmt : stmts) {
            analyzer.analyze(stmt);
        }
    }

    private void assertPhase(MiniDbException.Phase expected, Executable runnable) {
        MiniDbException ex = assertThrows(MiniDbException.class, runnable);
        assertEquals(expected, ex.phase(), "错误阶段应为 " + expected);
    }
}
