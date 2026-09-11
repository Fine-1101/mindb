package com.minidb.semantic;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.Expression;
import com.minidb.ast.FuncCall;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.Literal;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.MemoryCatalog;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SemanticAnalyzer 单元测试：检查矩阵每格一正一反。
 * 反例断言四件套：异常类型、phase==SEMANTIC、pos 精确到出错标识符的行列号、message 含表/列名。
 * 各用例的 pos 取对应 SQL 原文中该 token 的真实行列号。
 */
class SemanticAnalyzerTest {

    private static Position p(int line, int col) {
        return new Position(line, col);
    }

    /** student(id INT, name VARCHAR(50), score FLOAT)。 */
    private static Catalog studentCatalog() throws MiniDbException {
        Catalog catalog = new MemoryCatalog();
        catalog.createTable(new TableDef("student", List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50),
                new ColumnDef("score", DataType.FLOAT, 0))));
        return catalog;
    }

    private static SemanticAnalyzer analyzer(Catalog catalog) {
        return new SemanticAnalyzer(catalog);
    }

    private static Literal intLit(int line, int col) {
        return new Literal(1, DataType.INT, p(line, col));
    }

    /** id = 1（id 在 (line,col)，等号表达式 pos 用右操作数位置）。 */
    private static Expression idEqOne(int line, int colId, int colVal) {
        return new BinaryExpr(new ColumnRef(null, "id", p(line, colId)), BinaryOp.EQ,
                new Literal(1, DataType.INT, p(line, colVal)), p(line, colId));
    }

    // ==================================================================
    // CreateTable：表已存在 / 重复列名 / VARCHAR 超限
    // ==================================================================

    @Test
    void createTablePassesWithoutSideEffect() throws Exception {
        Catalog catalog = new MemoryCatalog();
        analyzer(catalog).analyze(new CreateTableStmt("course",
                List.of(new ColumnDef("cid", DataType.INT, 0)), p(1, 14)));
        // analyze 纯校验：不写 Catalog
        assertTrue(catalog.findTable("course").isEmpty());
    }

    @Test
    void createTableDuplicateRejected() {
        // CREATE TABLE student (...) —— student 已存在，pos 是表名位置(1,14)
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new CreateTableStmt("student",
                        List.of(new ColumnDef("id", DataType.INT, 0)), p(1, 14))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 14), e.pos());
        assertTrue(e.getMessage().contains("student"));
    }

    @Test
    void createTableDuplicateColumnNameRejected() {
        // CREATE TABLE t (id INT, id INT)
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(new MemoryCatalog()).analyze(new CreateTableStmt("t", List.of(
                        new ColumnDef("id", DataType.INT, 0),
                        new ColumnDef("id", DataType.INT, 0)), p(1, 14))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("id"));
    }

    @Test
    void createTableVarcharLengthOverflowRejected() {
        // CREATE TABLE t (name VARCHAR(40000)) —— 超 2B 长度上限
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(new MemoryCatalog()).analyze(new CreateTableStmt("t", List.of(
                        new ColumnDef("name", DataType.VARCHAR, 40000)), p(1, 14))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("32767"));
    }

    // ==================================================================
    // Insert：表存在 / 列存在 / 列数 / 类型（四维反例）+ 指定列序对齐
    // ==================================================================

    @Test
    void insertWithSpecifiedColumnsPasses() throws Exception {
        // INSERT INTO student (id, name, score) VALUES (1, 'Tom', 90.5) —— FLOAT 列给 INT_LIT 也合法
        analyzer(studentCatalog()).analyze(new InsertStmt("student",
                List.of(new ColumnRef(null, "id", p(1, 22)),
                        new ColumnRef(null, "name", p(1, 26)),
                        new ColumnRef(null, "score", p(1, 32))),
                List.of(List.of(new Literal(1, DataType.INT, p(1, 48)),
                        new Literal("Tom", DataType.VARCHAR, p(1, 51)),
                        new Literal(90.5, DataType.FLOAT, p(1, 58)))),
                p(1, 13)));
    }

    @Test
    void insertWithoutColumnsPasses() throws Exception {
        // INSERT INTO student VALUES (2, 'Ann', 85.0) —— 未指定列 = 表定义序全列
        analyzer(studentCatalog()).analyze(new InsertStmt("student", null,
                List.of(List.of(new Literal(2, DataType.INT, p(1, 30)),
                        new Literal("Ann", DataType.VARCHAR, p(1, 33)),
                        new Literal(85.0, DataType.FLOAT, p(1, 40)))),
                p(1, 13)));
    }

    @Test
    void insertColumnOrderRespected() throws Exception {
        // INSERT INTO student (score, id) VALUES (1, 5)：score(FLOAT)←INT_LIT 合法、id(INT)←INT_LIT 合法
        // 若按表定义序（id 在前）检查，第一个值 1 对 id 也合法、第二个值 5 对 score 也合法——
        // 需要类型错位用例才能真正证明按书写序对齐，见下
        analyzer(studentCatalog()).analyze(new InsertStmt("student",
                List.of(new ColumnRef(null, "score", p(1, 22)),
                        new ColumnRef(null, "id", p(1, 29))),
                List.of(List.of(new Literal(1, DataType.INT, p(1, 39)),
                        new Literal(5, DataType.INT, p(1, 42)))),
                p(1, 13)));
    }

    @Test
    void insertColumnOrderRespectedNegative() {
        // INSERT INTO student (score, id) VALUES (1, 'x')：第二个值属于 id(INT)，'x' 是 VARCHAR →
        // 报错位置在 'x'(1,42)。若错按表定义序（id,score），'x' 对应 score(FLOAT) 也会报错但语义错乱，
        // 值 1 对 score 合法则证明 (score,id) 书写序生效
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("student",
                        List.of(new ColumnRef(null, "score", p(1, 22)),
                                new ColumnRef(null, "id", p(1, 29))),
                        List.of(List.of(new Literal(1, DataType.INT, p(1, 39)),
                                new Literal("x", DataType.VARCHAR, p(1, 42)))),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 42), e.pos());
    }

    @Test
    void insertTableMissingRejected() {
        // INSERT INTO stuent (id) VALUES (1) —— 表名错拼，pos 是 stuent 的位置(1,13)
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("stuent",
                        List.of(new ColumnRef(null, "id", p(1, 21))),
                        List.of(List.of(new Literal(1, DataType.INT, p(1, 30)))),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 13), e.pos());
        assertTrue(e.getMessage().contains("stuent"));
    }

    @Test
    void insertUnknownColumnRejectedAtColumnPosition() {
        // INSERT INTO student (id, naem) VALUES (1, 2) —— pos 是 naem 的位置(1,26)，不是语句首/表名
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("student",
                        List.of(new ColumnRef(null, "id", p(1, 22)),
                                new ColumnRef(null, "naem", p(1, 26))),
                        List.of(List.of(new Literal(1, DataType.INT, p(1, 39)),
                                new Literal(2, DataType.INT, p(1, 42)))),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 26), e.pos());
        assertTrue(e.getMessage().contains("naem"));
    }

    @Test
    void insertColumnCountMismatchRejected() {
        // INSERT INTO student (id, name, score) VALUES (1, 'Tom') —— 3 列 2 值
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("student",
                        List.of(new ColumnRef(null, "id", p(1, 22)),
                                new ColumnRef(null, "name", p(1, 26)),
                                new ColumnRef(null, "score", p(1, 32))),
                        List.of(List.of(new Literal(1, DataType.INT, p(1, 48)),
                                new Literal("Tom", DataType.VARCHAR, p(1, 51)))),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("3 列"));
    }

    @Test
    void insertRowCountMismatchOnSecondRowRejected() {
        // 多行 VALUES：第二行少一个值 → 报错
        assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("student", null,
                        List.of(List.of(new Literal(1, DataType.INT, p(1, 30)),
                                        new Literal("a", DataType.VARCHAR, p(1, 33)),
                                        new Literal(1.0, DataType.FLOAT, p(1, 37))),
                                List.of(new Literal(2, DataType.INT, p(2, 30)),
                                        new Literal("b", DataType.VARCHAR, p(2, 33)))),
                        p(1, 13))));
    }

    @Test
    void insertTypeMismatchRejectedAtValuePosition() {
        // INSERT INTO student (id) VALUES ('abc') —— INT 列给字符串，pos 是 'abc' 的位置(1,33)
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("student",
                        List.of(new ColumnRef(null, "id", p(1, 22))),
                        List.of(List.of(new Literal("abc", DataType.VARCHAR, p(1, 33)))),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 33), e.pos());
        assertTrue(e.getMessage().contains("INT"));
    }

    @Test
    void insertVarcharColumnGivenIntRejected() {
        // INSERT INTO student (name) VALUES (123) —— VARCHAR 列给整数
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("student",
                        List.of(new ColumnRef(null, "name", p(1, 22))),
                        List.of(List.of(new Literal(123, DataType.INT, p(1, 33)))),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 33), e.pos());
    }

    @Test
    void insertVarcharValueOverMaxLengthRejected() {
        // name VARCHAR(50)，插入 51 字符 → 超长，pos 是值的 token 位置
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new InsertStmt("student",
                        List.of(new ColumnRef(null, "name", p(1, 22))),
                        List.of(List.of(new Literal("x".repeat(51), DataType.VARCHAR, p(1, 33)))),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 33), e.pos());
        assertTrue(e.getMessage().contains("超长"));
    }

    @Test
    void insertVarcharValueAtMaxLengthPasses() throws Exception {
        // 恰好 50 字符（UTF-8 50 字节）→ 通过
        analyzer(studentCatalog()).analyze(new InsertStmt("student",
                List.of(new ColumnRef(null, "name", p(1, 22))),
                List.of(List.of(new Literal("x".repeat(50), DataType.VARCHAR, p(1, 33)))),
                p(1, 13)));
    }

    // ==================================================================
    // Select：表存在 / 列存在 / WHERE 布尔
    // ==================================================================

    @Test
    void selectWithColumnsAndWherePasses() throws Exception {
        // SELECT id, name FROM student WHERE score >= 90.0
        analyzer(studentCatalog()).analyze(new SelectStmt(
                List.of(new ColumnRef(null, "id", p(1, 8)),
                        new ColumnRef(null, "name", p(1, 12))),
                "student",
                new BinaryExpr(new ColumnRef(null, "score", p(1, 32)), BinaryOp.GE,
                        new Literal(90.0, DataType.FLOAT, p(1, 41)), p(1, 32)),
                p(1, 25)));
    }

    @Test
    void selectStarWithWherePasses() throws Exception {
        // SELECT * FROM student WHERE id = 1
        analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                idEqOne(1, 33, 39), p(1, 15)));
    }

    @Test
    void selectTableMissingRejectedAtTablePosition() {
        // SELECT * FROM stuent WHERE id = 1 —— pos 是 stuent 的位置(1,15)，不是语句首(1,1)
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null, "stuent",
                        idEqOne(1, 33, 39), p(1, 15))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 15), e.pos());
        assertTrue(e.getMessage().contains("stuent"));
    }

    @Test
    void selectUnknownColumnRejectedAtColumnPosition() {
        // SELECT naem FROM student —— pos 是 naem 的位置(1,8)
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(
                        List.of(new ColumnRef(null, "naem", p(1, 8))),
                        "student", null, p(1, 18))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 8), e.pos());
        assertTrue(e.getMessage().contains("naem"));
    }

    @Test
    void selectWhereVarcharComparisonPasses() throws Exception {
        // SELECT * FROM student WHERE name = 'x' （VARCHAR=VARCHAR 合法）
        analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                new BinaryExpr(new ColumnRef(null, "name", p(1, 33)), BinaryOp.EQ,
                        new Literal("x", DataType.VARCHAR, p(1, 40)), p(1, 33)),
                p(1, 15)));
    }

    @Test
    void selectWhereNotComparisonPasses() throws Exception {
        // SELECT * FROM student WHERE NOT id = 1 （NOT 作用于比较结果）
        analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                new UnaryExpr(UnaryOp.NOT, idEqOne(1, 37, 43), p(1, 33)), p(1, 15)));
    }

    @Test
    void selectWhereNonBooleanRejected() {
        // SELECT * FROM student WHERE id —— WHERE 是 INT 列
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                        new ColumnRef(null, "id", p(1, 33)), p(1, 15))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 33), e.pos());
        assertTrue(e.getMessage().contains("INT"));
    }

    @Test
    void selectWhereAndOfIntsRejected() {
        // SELECT * FROM student WHERE 1 AND 2 —— AND 操作数非 BOOLEAN
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                        new BinaryExpr(new Literal(1, DataType.INT, p(1, 33)), BinaryOp.AND,
                                new Literal(2, DataType.INT, p(1, 40)), p(1, 33)),
                        p(1, 15))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("INT AND INT"));
    }

    @Test
    void selectWhereCrossTypeComparisonRejected() {
        // SELECT * FROM student WHERE id = 'x' —— INT = VARCHAR 跨类
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                        new BinaryExpr(new ColumnRef(null, "id", p(1, 33)), BinaryOp.EQ,
                                new Literal("x", DataType.VARCHAR, p(1, 38)), p(1, 33)),
                        p(1, 15))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("INT EQ VARCHAR"));
    }

    // ==================================================================
    // Select 聚合四查（D4）：混写 / 未知函数 / 非 COUNT 用 * / 参数类型不支持
    // ==================================================================

    @Test
    void scalarAggregatesPass() throws Exception {
        // SELECT COUNT(*), SUM(score), AVG(score), MIN(name), MAX(id) FROM student
        analyzer(studentCatalog()).analyze(new SelectStmt(null, List.of(
                new FuncCall("COUNT", null, p(1, 8)),
                new FuncCall("SUM", new ColumnRef(null, "score", p(1, 18)), p(1, 18)),
                new FuncCall("AVG", new ColumnRef(null, "score", p(1, 31)), p(1, 31)),
                new FuncCall("MIN", new ColumnRef(null, "name", p(1, 44)), p(1, 44)),
                new FuncCall("MAX", new ColumnRef(null, "id", p(1, 56)), p(1, 56))),
                "student", null, false, p(1, 65)));
    }

    @Test
    void aggregateMixedWithColumnRejected() {
        // SELECT name, COUNT(*) FROM student —— pos 定位到首个聚合项
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(
                        List.of(new ColumnRef(null, "name", p(1, 8))),
                        List.of(new FuncCall("COUNT", null, p(1, 15))),
                        "student", null, false, p(1, 27))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 15), e.pos());
        assertTrue(e.getMessage().contains("混写"));
    }

    @Test
    void unknownAggregateFuncRejected() {
        // SELECT FOO(id) FROM student
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null,
                        List.of(new FuncCall("FOO", new ColumnRef(null, "id", p(1, 12)), p(1, 8))),
                        "student", null, false, p(1, 20))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 8), e.pos());
        assertTrue(e.getMessage().contains("FOO"));
    }

    @Test
    void starArgOnlyForCountRejected() {
        // SELECT SUM(*) FROM student
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null,
                        List.of(new FuncCall("SUM", null, p(1, 8))),
                        "student", null, false, p(1, 17))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("COUNT"));
    }

    @Test
    void aggregateArgTypeRejected() {
        // SELECT SUM(name) FROM student —— VARCHAR 不支持 SUM
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null,
                        List.of(new FuncCall("SUM", new ColumnRef(null, "name", p(1, 12)), p(1, 8))),
                        "student", null, false, p(1, 22))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 8), e.pos());
        assertTrue(e.getMessage().contains("SUM"));
    }

    @Test
    void aggregateInWhereRejected() {
        // SELECT name FROM student WHERE COUNT(id) > 1 —— pos 定位到聚合函数名
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(
                        List.of(new ColumnRef(null, "name", p(1, 8))),
                        "student",
                        new BinaryExpr(new FuncCall("COUNT", new ColumnRef(null, "id", p(1, 37)), p(1, 33)),
                                BinaryOp.GT, new Literal(1, DataType.INT, p(1, 46)), p(1, 43)),
                        p(1, 25))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 33), e.pos());
        assertTrue(e.getMessage().contains("WHERE"));
    }

    @Test
    void nestedAggregateRejected() {
        // SELECT SUM(COUNT(*)) FROM student —— infer(FuncCall) 到达即嵌套聚合
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null,
                        List.of(new FuncCall("SUM", new FuncCall("COUNT", null, p(1, 12)), p(1, 8))),
                        "student", null, false, p(1, 26))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("嵌套聚合"));
    }

    @Test
    void aggregateUnknownColumnRejectedAtColumnPosition() {
        // SELECT SUM(scroe) FROM student —— pos 是 scroe 自己的位置
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null,
                        List.of(new FuncCall("SUM", new ColumnRef(null, "scroe", p(1, 12)), p(1, 8))),
                        "student", null, false, p(1, 23))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 12), e.pos());
        assertTrue(e.getMessage().contains("scroe"));
    }

    // ==================================================================
    // Delete：表存在 / WHERE 布尔
    // ==================================================================

    @Test
    void deleteWithAndWithoutWherePasses() throws Exception {
        // DELETE FROM student WHERE id = 2 / DELETE FROM student
        analyzer(studentCatalog()).analyze(new DeleteStmt("student", idEqOne(1, 28, 34), p(1, 13)));
        analyzer(studentCatalog()).analyze(new DeleteStmt("student", null, p(1, 13)));
    }

    @Test
    void deleteTableMissingRejected() {
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new DeleteStmt("stuent", null, p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 13), e.pos());
        assertTrue(e.getMessage().contains("stuent"));
    }

    @Test
    void deleteWhereNonBooleanRejected() {
        // DELETE FROM student WHERE 1 + 2 —— WHERE 是算术表达式(INT)
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new DeleteStmt("student",
                        new BinaryExpr(new Literal(1, DataType.INT, p(1, 28)), BinaryOp.ADD,
                                new Literal(2, DataType.INT, p(1, 32)), p(1, 28)),
                        p(1, 13))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertTrue(e.getMessage().contains("INT"));
    }

    // ==================================================================
    // 限定名 / 大小写贯通（拍板项3 落地证明）
    // ==================================================================

    @Test
    void qualifiedColumnRefPassesWhenTableMatches() throws Exception {
        // SELECT * FROM student WHERE student.id = 1 —— 限定名等于当前表名
        analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                new BinaryExpr(new ColumnRef("student", "id", p(1, 33)), BinaryOp.EQ,
                        new Literal(1, DataType.INT, p(1, 46)), p(1, 33)),
                p(1, 15)));
    }

    @Test
    void unknownTableQualifierRejectedAtRefPosition() {
        // SELECT * FROM student WHERE x.id = 1 —— 限定名不等于当前表名
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).analyze(new SelectStmt(null, "student",
                        new BinaryExpr(new ColumnRef("x", "id", p(1, 33)), BinaryOp.EQ,
                                new Literal(1, DataType.INT, p(1, 38)), p(1, 33)),
                        p(1, 15))));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 33), e.pos());
        assertTrue(e.getMessage().contains("x"));
    }

    @Test
    void caseInsensitiveThroughCatalog() throws Exception {
        // 建表 Student(ID INT, Name VARCHAR(16)) 后 insert into STUDENT (id) values (1) 通过
        Catalog catalog = new MemoryCatalog();
        catalog.createTable(new TableDef("Student", List.of(
                new ColumnDef("ID", DataType.INT, 0),
                new ColumnDef("Name", DataType.VARCHAR, 16))));
        analyzer(catalog).analyze(new InsertStmt("STUDENT",
                List.of(new ColumnRef(null, "id", p(2, 26))),
                List.of(List.of(new Literal(1, DataType.INT, p(2, 34)))),
                p(2, 13)));
    }

    // ==================================================================
    // infer：Literal / ColumnRef / BinaryExpr(全部 op) / UnaryExpr(2 op)
    // ==================================================================

    @Test
    void inferLiteralAndColumnRef() throws Exception {
        SemanticAnalyzer sa = analyzer(studentCatalog());
        assertEquals(DataType.INT, sa.infer(intLit(1, 1), "student"));
        assertEquals(DataType.VARCHAR, sa.infer(new ColumnRef(null, "name", p(1, 1)), "student"));
        assertEquals(DataType.FLOAT, sa.infer(new ColumnRef(null, "score", p(1, 1)), "student"));
        // 限定名大小写不敏感
        assertEquals(DataType.INT, sa.infer(new ColumnRef("STUDENT", "id", p(1, 1)), "student"));
    }

    @Test
    void inferBinaryAllOps() throws Exception {
        SemanticAnalyzer sa = analyzer(studentCatalog());
        for (BinaryOp op : new BinaryOp[]{BinaryOp.EQ, BinaryOp.NE, BinaryOp.LT,
                BinaryOp.LE, BinaryOp.GT, BinaryOp.GE}) {
            assertEquals(DataType.BOOLEAN, sa.infer(idEqOne(1, 1, 5), "student"), op.name());
        }
        for (BinaryOp op : new BinaryOp[]{BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV}) {
            assertEquals(DataType.INT, sa.infer(new BinaryExpr(
                    new Literal(1, DataType.INT, p(1, 1)), op,
                    new Literal(2, DataType.INT, p(1, 5)), p(1, 1)), "student"), op.name());
        }
        // INT op FLOAT → FLOAT；BOOLEAN AND/OR → BOOLEAN
        assertEquals(DataType.FLOAT, sa.infer(new BinaryExpr(
                new Literal(1, DataType.INT, p(1, 1)), BinaryOp.MUL,
                new Literal(2.0, DataType.FLOAT, p(1, 5)), p(1, 1)), "student"));
        assertEquals(DataType.BOOLEAN, sa.infer(new BinaryExpr(
                idEqOne(1, 1, 5), BinaryOp.AND,
                new BinaryExpr(new ColumnRef(null, "score", p(1, 1)), BinaryOp.GT,
                        new Literal(1.0, DataType.FLOAT, p(1, 5)), p(1, 1)), p(1, 1)), "student"));
    }

    @Test
    void inferUnaryOps() throws Exception {
        SemanticAnalyzer sa = analyzer(studentCatalog());
        assertEquals(DataType.INT, sa.infer(new UnaryExpr(UnaryOp.NEG, intLit(1, 1), p(1, 1)), "student"));
        assertEquals(DataType.FLOAT, sa.infer(new UnaryExpr(UnaryOp.NEG,
                new Literal(1.5, DataType.FLOAT, p(1, 1)), p(1, 1)), "student"));
        assertEquals(DataType.BOOLEAN, sa.infer(new UnaryExpr(UnaryOp.NOT,
                idEqOne(1, 1, 5), p(1, 1)), "student"));
    }

    @Test
    void inferUnknownColumnInWhereRejected() {
        // WHERE core = 1 —— WHERE 里未知列，pos 是 core 的位置
        MiniDbException e = assertThrows(MiniDbException.class, () ->
                analyzer(studentCatalog()).infer(
                        new BinaryExpr(new ColumnRef(null, "core", p(1, 33)), BinaryOp.EQ,
                                new Literal(1, DataType.INT, p(1, 40)), p(1, 33)),
                        "student"));
        assertEquals(MiniDbException.Phase.SEMANTIC, e.phase());
        assertEquals(p(1, 33), e.pos());
        assertTrue(e.getMessage().contains("core"));
    }
}
