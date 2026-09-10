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
import com.minidb.ast.Statement;
import com.minidb.ast.UnaryExpr;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.TableDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;

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
        }
    }

    /** 表达式类型推断。WHERE 布尔检查用，Planner/优化器复用。 */
    public DataType infer(Expression expr, String tableName) throws MiniDbException {
        return switch (expr) {
            case Literal lit -> lit.type();
            case ColumnRef ref -> resolveColumnType(tableName, ref);
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
            case FuncCall fc -> throw new MiniDbException(
                    MiniDbException.Phase.SEMANTIC, fc.pos(),
                    "聚合函数不能出现在 WHERE 条件中");
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

        // 目标列类型：指定列按书写序对齐（(score, id) 按 score,id 检查，非表定义序）；
        // 未指定列 = 表定义序全列
        List<DataType> targetTypes = new ArrayList<>();
        if (s.columns() == null) {
            for (ColumnDef col : table.columns()) {
                targetTypes.add(col.type());
            }
        } else {
            for (ColumnRef c : s.columns()) {
                targetTypes.add(resolveColumnType(table.tableName(), c));
            }
        }

        for (List<Expression> row : s.rows()) {
            if (row.size() != targetTypes.size()) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                        "列数与值数不匹配: " + targetTypes.size() + " 列, " + row.size() + " 值");
            }
            for (int i = 0; i < row.size(); i++) {
                checkValue(row.get(i), targetTypes.get(i));
            }
        }
    }

    /** INSERT 值类型匹配：INT←INT_LIT；FLOAT←INT_LIT/FLOAT_LIT（INT 提升）；VARCHAR←STRING。 */
    private void checkValue(Expression value, DataType columnType) throws MiniDbException {
        if (!(value instanceof Literal lit)) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, value.pos(),
                    "INSERT 值必须是字面量");
        }
        boolean ok = switch (columnType) {
            case INT -> lit.type() == DataType.INT;
            case FLOAT -> lit.type() == DataType.INT || lit.type() == DataType.FLOAT;
            case VARCHAR -> lit.type() == DataType.VARCHAR;
            case BOOLEAN -> false;
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
        if (s.columns() != null) {
            // 检查是否存在聚合函数
            boolean hasAggregate = false;
            boolean hasPlainColumn = false;
            for (Expression expr : s.columns()) {
                if (expr instanceof FuncCall fc) {
                    hasAggregate = true;
                    checkAggregateFunc(fc, table.tableName());
                } else if (expr instanceof ColumnRef ref) {
                    hasPlainColumn = true;
                    resolveColumnType(table.tableName(), ref);
                }
            }
            // 聚合与普通列不能混写
            if (hasAggregate && hasPlainColumn) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                        "聚合函数与普通列不能混写（不支持 GROUP BY）");
            }
        }
        if (s.where() != null) {
            requireBooleanWhere(s.where(), table.tableName());
        }
    }

    /** 聚合函数语义检查：函数名合法性、COUNT(*) 无参数、其他函数参数必须是列引用且类型合法。 */
    private void checkAggregateFunc(FuncCall fc, String tableName) throws MiniDbException {
        String func = fc.func().toLowerCase();
        switch (func) {
            case "count":
                // COUNT(*) 时 arg == null；COUNT(col) 时 arg 必须是列引用
                if (fc.arg() != null && !(fc.arg() instanceof ColumnRef)) {
                    throw new MiniDbException(MiniDbException.Phase.SEMANTIC, fc.pos(),
                            "COUNT 参数必须是列引用");
                }
                if (fc.arg() instanceof ColumnRef ref) {
                    resolveColumnType(tableName, ref);
                }
                break;
            case "sum", "avg":
                requireColumnArg(fc, tableName);
                // SUM/AVG 参数必须是数值类型
                if (fc.arg() instanceof ColumnRef ref) {
                    DataType colType = resolveColumnType(tableName, ref);
                    if (colType != DataType.INT && colType != DataType.FLOAT) {
                        throw new MiniDbException(MiniDbException.Phase.SEMANTIC, fc.pos(),
                                func.toUpperCase() + " 参数必须是数值类型, 实际: " + colType);
                    }
                }
                break;
            case "min", "max":
                requireColumnArg(fc, tableName);
                if (fc.arg() instanceof ColumnRef ref) {
                    resolveColumnType(tableName, ref);
                }
                break;
            default:
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, fc.pos(),
                        "未知函数: " + fc.func());
        }
    }

    private void requireColumnArg(FuncCall fc, String tableName) throws MiniDbException {
        if (fc.arg() == null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, fc.pos(),
                    fc.func().toUpperCase() + " 不支持 * 参数");
        }
        if (!(fc.arg() instanceof ColumnRef)) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, fc.pos(),
                    fc.func().toUpperCase() + " 参数必须是列引用");
        }
    }

    private void checkDelete(DeleteStmt s) throws MiniDbException {
        requireTable(s.tableName(), s.pos());
        if (s.where() != null) {
            requireBooleanWhere(s.where(), s.tableName());
        }
    }

    private void requireBooleanWhere(Expression where, String tableName) throws MiniDbException {
        DataType t = infer(where, tableName);
        if (t != DataType.BOOLEAN) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, where.pos(),
                    "WHERE 条件必须是 BOOLEAN, 实际是 " + t);
        }
    }

    // ------------------------------------------------------------------
    // 共用
    // ------------------------------------------------------------------

    private TableDef requireTable(String name, Position pos) throws MiniDbException {
        return catalog.findTable(name).orElseThrow(() ->
                new MiniDbException(MiniDbException.Phase.SEMANTIC, pos, "表不存在: " + name));
    }

    /** 列引用检查（带列自己的位置）：限定名须等于当前表名（忽略大小写），列须存在；返回列类型。 */
    private DataType resolveColumnType(String tableName, ColumnRef ref) throws MiniDbException {
        if (ref.table() != null && !ref.table().equalsIgnoreCase(tableName)) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                    "未知表限定符: " + ref.table());
        }
        return catalog.findColumn(tableName, ref.column())
                .map(ColumnDef::type)
                .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                        "列不存在: " + ref.column()));
    }
}
