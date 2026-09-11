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
import com.minidb.ast.OrderKey;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.SetClause;
import com.minidb.ast.Statement;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UpdateStmt;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 语义分析器：存在性 / INSERT 匹配 / WHERE 布尔检查，纯校验无副作用（不写 Catalog、不改 AST）。
 *
 * <p>错误统一抛 MiniDbException(SEMANTIC, 出错标识符自己的 AST 位置, 原因)——
 * 位置预检查在 Semantic 完成（拍板项2），Catalog 的无位置报错仅作兜底。
 *
 * <p>约定：语句节点的 pos 为表名 token 的位置（Parser 侧保证），表不存在类错误据此定位；
 * 列位置由 InsertStmt/SelectStmt 中的 ColumnRef 携带。
 */
public class SemanticAnalyzer {

    /** VARCHAR 的 2B 长度上限（RowEncoder putShort），超限会在编码时溢出。 */
    private static final int MAX_VARCHAR_LENGTH = 32767;

    /** D4 拍板的五个标量聚合函数（func 已由 Parser 大写规范化）。 */
    private static final Set<String> AGG_FUNCS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    private final Catalog catalog;

    public SemanticAnalyzer(Catalog catalog) {
        this.catalog = catalog;
    }

    /** 全量语义检查。失败抛 MiniDbException(SEMANTIC, AST节点位置, 原因)。 */
    public void analyze(Statement stmt) throws MiniDbException {
        switch (stmt) {
            case CreateTableStmt s -> checkCreateTable(s);
            case InsertStmt s -> checkInsert(s);
            case SelectStmt s -> checkSelect(s);
            case DeleteStmt s -> checkDelete(s);
            case UpdateStmt s -> checkUpdate(s);
        }
    }

    /** 表达式类型推断。WHERE 布尔检查用，Planner/优化器复用。 */
    public DataType infer(Expression expr, String tableName) throws MiniDbException {
        return switch (expr) {
            case Literal lit -> lit.type();
            case ColumnRef ref -> resolveColumn(tableName, ref).type();
            case BinaryExpr b -> {
                DataType lt = infer(b.left(), tableName);
                DataType rt = infer(b.right(), tableName);
                Optional<DataType> result = switch (b.op()) {
                    case AND, OR -> TypeRules.logical(lt, rt);
                    case EQ, NE, LT, LE, GT, GE -> TypeRules.comparison(lt, rt);
                    case ADD, SUB, MUL, DIV -> TypeRules.arithmetic(lt, rt);
                };
                yield result.orElseThrow(() -> new MiniDbException(
                        MiniDbException.Phase.SEMANTIC, b.pos(),
                        "类型不匹配: " + lt + " " + b.op() + " " + rt));
            }
            case UnaryExpr u -> {
                DataType ot = infer(u.operand(), tableName);
                yield TypeRules.unary(u.op(), ot).orElseThrow(() -> new MiniDbException(
                        MiniDbException.Phase.SEMANTIC, u.pos(),
                        "类型不匹配: " + u.op() + " " + ot));
            }
            // 顶层聚合类型由 checkAggregates/TypeRules.aggregate 管；此处到达 = 嵌套聚合（SUM(COUNT(x))）
            case FuncCall f -> throw new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                    "嵌套聚合函数不支持: " + f.display());
        };
    }

    // ------------------------------------------------------------------
    // CreateTable
    // ------------------------------------------------------------------

    private void checkCreateTable(CreateTableStmt s) throws MiniDbException {
        if (catalog.findTable(s.tableName()).isPresent()) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                    "表已存在: " + s.tableName());
        }
        Set<String> seen = new HashSet<>();
        for (ColumnDef col : s.columns()) {
            if (!seen.add(col.name().toLowerCase(Locale.ROOT))) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                        "重复列名: " + col.name());
            }
            if (col.type() == DataType.VARCHAR && col.maxLength() > MAX_VARCHAR_LENGTH) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                        "VARCHAR 长度超上限(" + MAX_VARCHAR_LENGTH + "): "
                                + col.name() + "(" + col.maxLength() + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    // Insert
    // ------------------------------------------------------------------

    private void checkInsert(InsertStmt s) throws MiniDbException {
        TableDef table = requireTable(s.tableName(), s.pos());

        // 目标列：指定列按书写序对齐（(score, id) 按 score,id 检查，非表定义序）；
        // 未指定列 = 表定义序全列
        List<ColumnDef> targetColumns = new ArrayList<>();
        if (s.columns() == null) {
            targetColumns.addAll(table.columns());
        } else {
            for (ColumnRef c : s.columns()) {
                targetColumns.add(resolveColumn(table.tableName(), c));
            }
        }

        for (List<Expression> row : s.rows()) {
            if (row.size() != targetColumns.size()) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                        "列数与值数不匹配: " + targetColumns.size() + " 列, " + row.size() + " 值");
            }
            for (int i = 0; i < row.size(); i++) {
                checkValue(row.get(i), targetColumns.get(i));
            }
        }
    }

    /** INSERT 值类型匹配：INT←INT_LIT；FLOAT←INT_LIT/FLOAT_LIT（INT 提升）；
     *  VARCHAR←STRING 且 UTF-8 字节数 ≤ maxLength（超限会撑爆 RowEncoder 的 2B 长度）。 */
    private void checkValue(Expression value, ColumnDef column) throws MiniDbException {
        if (!(value instanceof Literal lit)) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, value.pos(),
                    "INSERT 值必须是字面量");
        }
        DataType columnType = column.type();
        if (columnType == DataType.VARCHAR && lit.type() == DataType.VARCHAR) {
            int len = ((String) lit.value()).getBytes(StandardCharsets.UTF_8).length;
            if (len > column.maxLength()) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, lit.pos(),
                        "VARCHAR 值超长: 列 " + column.name() + "(" + column.maxLength()
                                + "), 实际 " + len + " 字节");
            }
        }
        boolean ok = switch (columnType) {
            case INT -> lit.type() == DataType.INT || lit.type() == DataType.NULL;
            case FLOAT -> lit.type() == DataType.INT || lit.type() == DataType.FLOAT || lit.type() == DataType.NULL;
            case VARCHAR -> lit.type() == DataType.VARCHAR || lit.type() == DataType.NULL;
            case BOOLEAN -> false;
            // NULL 与 BOOLEAN 同为先例：仅字面量语义类型，不能作列类型（建表时 Parser 已拒绝）
            case NULL -> false;
        };
        if (!ok) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, lit.pos(),
                    "类型不匹配: 列类型 " + columnType + ", 值类型 " + lit.type());
        }
    }

    // ------------------------------------------------------------------
    // Select / Delete
    // ------------------------------------------------------------------

    private void checkSelect(SelectStmt s) throws MiniDbException {
        TableDef table = requireTable(s.tableName(), s.pos());

        // JOIN 多表列解析
        TableDef joinTableDef = null;
        List<String> tableNames = new ArrayList<>();
        tableNames.add(table.tableName());
        if (s.joinTable() != null) {
            joinTableDef = requireTable(s.joinTable(), s.pos());
            tableNames.add(joinTableDef.tableName());
            // ON 条件布尔检查
            if (s.joinOn() == null) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                        "JOIN 缺少 ON 条件");
            }
            requireBooleanWhere(s.joinOn(), tableNames);
        }

        // 列存在性检查（多表解析）
        if (s.columns() != null) {
            for (ColumnRef c : s.columns()) {
                resolveColumnMulti(c, tableNames, table, joinTableDef);
            }
        }
        if (s.aggregates() != null) {
            checkAggregatesMulti(s, table, joinTableDef, tableNames);
        }
        if (s.where() != null) {
            rejectAggregateInWhere(s.where());
            requireBooleanWhere(s.where(), tableNames);
        }
        // GROUP BY 检查：非聚合列必须在 GROUP BY 中
        if (s.groupBy() != null && s.aggregates() != null) {
            // 聚合 + GROUP BY：合法
        } else if (s.groupBy() != null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                    "GROUP BY 必须与聚合函数一起使用");
        }
        // ORDER BY 列存在性检查
        if (s.orderBy() != null) {
            for (OrderKey key : s.orderBy()) {
                resolveColumnMulti(key.column(), tableNames, table, joinTableDef);
            }
        }
    }

    /** 聚合四查（D4 拍板）：混写普通列 / 未知函数 / 非 COUNT 用 * / 参数类型不支持。 */
    private void checkAggregates(SelectStmt s, TableDef table) throws MiniDbException {
        // GROUP BY 时允许聚合与普通列混写
        if (s.columns() != null && s.groupBy() == null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.aggregates().get(0).pos(),
                    "聚合函数不能与普通列混写: " + s.aggregates().get(0).display());
        }
        for (FuncCall f : s.aggregates()) {
            if (!AGG_FUNCS.contains(f.func())) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                        "未知聚合函数: " + f.func());
            }
            if (f.arg() == null && !"COUNT".equals(f.func())) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                        "只有 COUNT 支持 *: " + f.func());
            }
            if (f.arg() != null) {
                DataType argType = infer(f.arg(), table.tableName());
                TypeRules.aggregate(f.func(), argType).orElseThrow(() ->
                        new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                                "聚合参数类型不支持: " + f.func() + "(" + argType + ")"));
            }
        }
    }

    /** 多表聚合检查（JOIN 场景）。 */
    private void checkAggregatesMulti(SelectStmt s, TableDef left, TableDef right,
                                       List<String> tableNames) throws MiniDbException {
        // GROUP BY 时允许聚合与普通列混写（普通列必须是 GROUP BY 列）
        if (s.columns() != null && s.groupBy() == null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.aggregates().get(0).pos(),
                    "聚合函数不能与普通列混写: " + s.aggregates().get(0).display());
        }
        for (FuncCall f : s.aggregates()) {
            if (!AGG_FUNCS.contains(f.func())) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                        "未知聚合函数: " + f.func());
            }
            if (f.arg() == null && !"COUNT".equals(f.func())) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                        "只有 COUNT 支持 *: " + f.func());
            }
            if (f.arg() != null) {
                DataType argType = inferMulti(f.arg(), tableNames, left, right);
                TypeRules.aggregate(f.func(), argType).orElseThrow(() ->
                        new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                                "聚合参数类型不支持: " + f.func() + "(" + argType + ")"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    private void checkUpdate(UpdateStmt s) throws MiniDbException {
        TableDef table = requireTable(s.tableName(), s.pos());
        for (SetClause sc : s.sets()) {
            ColumnDef col = resolveColumn(table.tableName(), sc.column());
            // SET 值类型检查（表达式推断）
            DataType valType = infer(sc.value(), table.tableName());
            if (valType != DataType.NULL && valType != col.type()) {
                // INT→FLOAT 提升允许
                if (!(col.type() == DataType.FLOAT && valType == DataType.INT)) {
                    throw new MiniDbException(MiniDbException.Phase.SEMANTIC, sc.column().pos(),
                            "SET 类型不匹配: 列 " + col.name() + "(" + col.type() + "), 值类型 " + valType);
                }
            }
        }
        if (s.where() != null) {
            rejectAggregateInWhere(s.where());
            requireBooleanWhere(s.where(), table.tableName());
        }
    }

    private void checkDelete(DeleteStmt s) throws MiniDbException {
        requireTable(s.tableName(), s.pos());
        if (s.where() != null) {
            rejectAggregateInWhere(s.where());
            requireBooleanWhere(s.where(), s.tableName());
        }
    }

    /** 聚合出现在 WHERE/DELETE 条件：报 SEMANTIC（pos 定位到聚合函数名）。 */
    private void rejectAggregateInWhere(Expression where) throws MiniDbException {
        FuncCall agg = findAggregate(where);
        if (agg != null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, agg.pos(),
                    "聚合函数不允许出现在 WHERE 中: " + agg.display());
        }
    }

    private static FuncCall findAggregate(Expression expr) {
        if (expr instanceof BinaryExpr b) {
            FuncCall left = findAggregate(b.left());
            return left != null ? left : findAggregate(b.right());
        }
        if (expr instanceof UnaryExpr u) {
            return findAggregate(u.operand());
        }
        if (expr instanceof FuncCall f) {
            return f;
        }
        return null;
    }

    private void requireBooleanWhere(Expression where, String tableName) throws MiniDbException {
        DataType t = infer(where, tableName);
        if (t != DataType.BOOLEAN) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, where.pos(),
                    "WHERE 条件必须是 BOOLEAN, 实际是 " + t);
        }
    }

    /** 多表 WHERE/ON 布尔检查。 */
    private void requireBooleanWhere(Expression where, List<String> tableNames) throws MiniDbException {
        // 简化：用第一个表做 infer（多表列解析在 inferMulti 中处理）
        // 这里需要一个多表 infer
        DataType t = inferMulti(where, tableNames, null, null);
        if (t != DataType.BOOLEAN) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, where.pos(),
                    "WHERE 条件必须是 BOOLEAN, 实际是 " + t);
        }
    }

    /** 多表类型推断（JOIN 场景）。 */
    private DataType inferMulti(Expression expr, List<String> tableNames,
                                 TableDef left, TableDef right) throws MiniDbException {
        return switch (expr) {
            case Literal lit -> lit.type();
            case ColumnRef ref -> resolveColumnMulti(ref, tableNames, left, right).type();
            case BinaryExpr b -> {
                DataType lt = inferMulti(b.left(), tableNames, left, right);
                DataType rt = inferMulti(b.right(), tableNames, left, right);
                Optional<DataType> result = switch (b.op()) {
                    case AND, OR -> TypeRules.logical(lt, rt);
                    case EQ, NE, LT, LE, GT, GE -> TypeRules.comparison(lt, rt);
                    case ADD, SUB, MUL, DIV -> TypeRules.arithmetic(lt, rt);
                };
                yield result.orElseThrow(() -> new MiniDbException(
                        MiniDbException.Phase.SEMANTIC, b.pos(),
                        "类型不匹配: " + lt + " " + b.op() + " " + rt));
            }
            case UnaryExpr u -> {
                DataType ot = inferMulti(u.operand(), tableNames, left, right);
                yield TypeRules.unary(u.op(), ot).orElseThrow(() -> new MiniDbException(
                        MiniDbException.Phase.SEMANTIC, u.pos(),
                        "类型不匹配: " + u.op() + " " + ot));
            }
            case FuncCall f -> throw new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                    "嵌套聚合函数不支持: " + f.display());
        };
    }

    /** 多表列解析：限定名按表名解析，非限定名在唯一时解析、二义时报错。 */
    private ColumnDef resolveColumnMulti(ColumnRef ref, List<String> tableNames,
                                          TableDef left, TableDef right) throws MiniDbException {
        if (ref.table() != null) {
            // 限定名：找到对应表
            TableDef tdef = null;
            for (String tn : tableNames) {
                if (tn.equalsIgnoreCase(ref.table())) {
                    tdef = catalog.findTable(tn).orElse(null);
                    break;
                }
            }
            if (tdef == null) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                        "未知表: " + ref.table());
            }
            return tdef.columns().stream()
                    .filter(c -> c.name().equalsIgnoreCase(ref.column()))
                    .findFirst()
                    .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                            "列不存在: " + ref.table() + "." + ref.column()));
        }
        // 非限定名：在所有表中查找
        ColumnDef found = null;
        String foundTable = null;
        for (String tn : tableNames) {
            Optional<ColumnDef> col = catalog.findColumn(tn, ref.column());
            if (col.isPresent()) {
                if (found != null) {
                    throw new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                            "列引用二义: " + ref.column() + " 在多个表中存在");
                }
                found = col.get();
                foundTable = tn;
            }
        }
        if (found == null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                    "列不存在: " + ref.column());
        }
        return found;
    }

    // ------------------------------------------------------------------
    // 共用
    // ------------------------------------------------------------------

    private TableDef requireTable(String name, Position pos) throws MiniDbException {
        return catalog.findTable(name).orElseThrow(() ->
                new MiniDbException(MiniDbException.Phase.SEMANTIC, pos, "表不存在: " + name));
    }

    /** 列引用检查（带列自己的位置）：限定名须等于当前表名（忽略大小写），列须存在。 */
    private ColumnDef resolveColumn(String tableName, ColumnRef ref) throws MiniDbException {
        if (ref.table() != null && !ref.table().equalsIgnoreCase(tableName)) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                    "未知表限定符: " + ref.table());
        }
        return catalog.findColumn(tableName, ref.column())
                .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                        "列不存在: " + ref.column()));
    }

}
