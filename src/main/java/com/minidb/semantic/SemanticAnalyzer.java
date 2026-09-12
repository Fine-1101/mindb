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
 *
 * <p>D5 五特性检查（拍板表见 docs/D5.md）：UPDATE 四查、ORDER BY/GROUP BY 键检查、
 * JOIN 双表列解析（限定名恒可解析、非限定名二义报错）、NULL 可赋任意列、
 * GROUP BY 的 SELECT 非聚合列 ⊆ 分组列集。
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

    /** 列解析器：单表 = 限定名匹配 + 存在性；JOIN = 双表解析（拍板 10）。infer 据此参数化。 */
    @FunctionalInterface
    private interface ColumnResolver {
        ColumnDef resolve(ColumnRef ref) throws MiniDbException;
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

    /** 表达式类型推断（单表）。WHERE 布尔检查用，Planner/优化器复用。 */
    public DataType infer(Expression expr, String tableName) throws MiniDbException {
        return infer(expr, ref -> resolveColumn(tableName, ref));
    }

    /** 表达式类型推断（按列解析器；JOIN 上下文与单表共用一套推断）。 */
    private DataType infer(Expression expr, ColumnResolver resolver) throws MiniDbException {
        return switch (expr) {
            case Literal lit -> lit.type();
            case ColumnRef ref -> resolver.resolve(ref).type();
            case BinaryExpr b -> {
                DataType lt = infer(b.left(), resolver);
                DataType rt = infer(b.right(), resolver);
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
                DataType ot = infer(u.operand(), resolver);
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
     *  VARCHAR←STRING 且 UTF-8 字节数 ≤ maxLength（超限会撑爆 RowEncoder 的 2B 长度）；
     *  任何列 ← NULL 字面量（D5 拍板 4，NULL 可赋任意列）。 */
    private void checkValue(Expression value, ColumnDef column) throws MiniDbException {
        if (!(value instanceof Literal lit)) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, value.pos(),
                    "INSERT 值必须是字面量");
        }
        if (column.type() == DataType.VARCHAR && lit.type() == DataType.VARCHAR) {
            int len = ((String) lit.value()).getBytes(StandardCharsets.UTF_8).length;
            if (len > column.maxLength()) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, lit.pos(),
                        "VARCHAR 值超长: 列 " + column.name() + "(" + column.maxLength()
                                + "), 实际 " + len + " 字节");
            }
        }
        checkAssignable(lit.type(), column, lit.pos());
    }

    /** 值类型可赋给列：INT←INT；FLOAT←INT/FLOAT；VARCHAR←VARCHAR；任何列 ← NULL（D5 拍板 4）。
     *  INSERT 字面量与 UPDATE 表达式共用。 */
    private void checkAssignable(DataType valueType, ColumnDef column, Position pos)
            throws MiniDbException {
        boolean ok = switch (column.type()) {
            case INT -> valueType == DataType.INT || valueType == DataType.NULL;
            case FLOAT -> valueType == DataType.INT || valueType == DataType.FLOAT
                    || valueType == DataType.NULL;
            case VARCHAR -> valueType == DataType.VARCHAR || valueType == DataType.NULL;
            case BOOLEAN, NULL -> false; // 仅语义类型，不能作列类型
        };
        if (!ok) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, pos,
                    "类型不匹配: 列类型 " + column.type() + ", 值类型 " + valueType);
        }
    }

    // ------------------------------------------------------------------
    // Select / Delete
    // ------------------------------------------------------------------

    private void checkSelect(SelectStmt s) throws MiniDbException {
        TableDef table = requireTable(s.tableName(), s.pos());
        ColumnResolver resolver;
        if (s.joinTable() != null) {
            TableDef join = requireTable(s.joinTable(), s.pos());
            resolver = ref -> resolveJoinColumn(table, join, ref);
        } else {
            resolver = ref -> resolveColumn(table.tableName(), ref);
        }
        if (s.columns() != null) {
            for (ColumnRef c : s.columns()) {
                resolver.resolve(c); // 纯存在性检查；SELECT * 的展开归 Planner
            }
        }
        if (s.aggregates() != null) {
            checkAggregates(s, resolver);
        }
        if (s.groupBy() != null) {
            checkGroupBy(s, resolver);
        }
        if (s.joinOn() != null) {
            rejectAggregateInCondition(s.joinOn(), "ON 条件");
            requireBoolean(s.joinOn(), resolver, "ON 条件");
        }
        if (s.where() != null) {
            rejectAggregateInWhere(s.where());
            requireBoolean(s.where(), resolver, "WHERE 条件");
        }
        if (s.orderBy() != null) {
            checkOrderBy(s, resolver);
        }
    }

    /** 聚合四查（D4 拍板）：混写普通列（标量聚合；GROUP BY 下允许混写，⊆ 检查归 checkGroupBy）/
     * 未知函数 / 非 COUNT 用 * / 参数类型不支持。 */
    private void checkAggregates(SelectStmt s, ColumnResolver resolver) throws MiniDbException {
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
                DataType argType = infer(f.arg(), resolver); // arg 内列存在性/类型，错误自带 pos
                TypeRules.aggregate(f.func(), argType).orElseThrow(() ->
                        new MiniDbException(MiniDbException.Phase.SEMANTIC, f.pos(),
                                "聚合参数类型不支持: " + f.func() + "(" + argType + ")"));
            }
        }
    }

    /** GROUP BY 检查（D5 拍板 12）：键列存在（JOIN 上下文含二义检查）；
     * SELECT 非聚合列必须 ⊆ 分组列集（pos 报到该列）。 */
    private void checkGroupBy(SelectStmt s, ColumnResolver resolver) throws MiniDbException {
        if (s.columns() == null && s.aggregates() == null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, s.pos(),
                    "GROUP BY 不支持 SELECT *");
        }
        for (ColumnRef g : s.groupBy()) {
            resolver.resolve(g);
        }
        if (s.columns() != null) {
            for (ColumnRef c : s.columns()) {
                boolean inGroup = s.groupBy().stream()
                        .anyMatch(g -> g.column().equalsIgnoreCase(c.column()));
                if (!inGroup) {
                    throw new MiniDbException(MiniDbException.Phase.SEMANTIC, c.pos(),
                            "SELECT 非聚合列必须在 GROUP BY 中: " + c.column());
                }
            }
        }
    }

    /** ORDER BY 检查（D5 拍板 7/11）：键列存在（JOIN 上下文含二义检查）。
     * Sort 在计划最外层，键必须能在 SELECT 输出列中解析（SELECT * 输出全部列除外）。 */
    private void checkOrderBy(SelectStmt s, ColumnResolver resolver) throws MiniDbException {
        Set<String> output = null; // null = SELECT *（全部列，存在性由 resolver 保证）
        if (s.groupBy() != null) {
            output = new HashSet<>();
            if (s.columns() != null) {
                for (ColumnRef c : s.columns()) {
                    output.add(c.column().toLowerCase(Locale.ROOT));
                }
            }
            if (s.aggregates() != null) {
                for (FuncCall f : s.aggregates()) {
                    output.add(f.display().toLowerCase(Locale.ROOT));
                }
            }
        } else if (s.aggregates() != null) {
            output = new HashSet<>();
            for (FuncCall f : s.aggregates()) {
                output.add(f.display().toLowerCase(Locale.ROOT));
            }
        } else if (s.columns() != null) {
            output = new HashSet<>();
            for (ColumnRef c : s.columns()) {
                output.add(c.column().toLowerCase(Locale.ROOT));
            }
        }
        for (OrderKey k : s.orderBy()) {
            resolver.resolve(k.column());
            if (output != null && !output.contains(k.column().column().toLowerCase(Locale.ROOT))) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, k.column().pos(),
                        "ORDER BY 列必须在 SELECT 输出中: " + k.column().column());
            }
        }
    }

    private void checkDelete(DeleteStmt s) throws MiniDbException {
        requireTable(s.tableName(), s.pos());
        if (s.where() != null) {
            rejectAggregateInWhere(s.where());
            requireBooleanWhere(s.where(), s.tableName());
        }
    }

    // ------------------------------------------------------------------
    // Update（D5 拍板 2/3/4）
    // ------------------------------------------------------------------

    /** UPDATE 四查：表存在 / SET 列存在 / SET 值类型匹配（NULL 可赋任意列，表达式可引用本行列）/
     * WHERE 布尔。VARCHAR 字面量超长同 INSERT 上限（重编码走 RowEncoder）。 */
    private void checkUpdate(UpdateStmt s) throws MiniDbException {
        TableDef table = requireTable(s.tableName(), s.pos());
        for (SetClause set : s.sets()) {
            ColumnDef col = resolveColumn(table.tableName(), set.column());
            DataType valueType = infer(set.value(), table.tableName());
            checkAssignable(valueType, col, set.value().pos());
            if (valueType == DataType.VARCHAR && set.value() instanceof Literal lit
                    && lit.value() instanceof String str) {
                int len = str.getBytes(StandardCharsets.UTF_8).length;
                if (len > col.maxLength()) {
                    throw new MiniDbException(MiniDbException.Phase.SEMANTIC, lit.pos(),
                            "VARCHAR 值超长: 列 " + col.name() + "(" + col.maxLength()
                                    + "), 实际 " + len + " 字节");
                }
            }
        }
        if (s.where() != null) {
            rejectAggregateInWhere(s.where());
            requireBooleanWhere(s.where(), s.tableName());
        }
    }

    // ------------------------------------------------------------------
    // 共用
    // ------------------------------------------------------------------

    /** 聚合出现在 WHERE/DELETE 条件：报 SEMANTIC（pos 定位到聚合函数名）。 */
    private void rejectAggregateInWhere(Expression where) throws MiniDbException {
        rejectAggregateInCondition(where, "WHERE");
    }

    private void rejectAggregateInCondition(Expression cond, String label) throws MiniDbException {
        FuncCall agg = findAggregate(cond);
        if (agg != null) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, agg.pos(),
                    "聚合函数不允许出现在 " + label + " 中: " + agg.display());
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

    /** WHERE 布尔检查（单表，DELETE/UPDATE 用）。NULL 视为未知布尔放行（拍板 5：运行时等同过滤）。 */
    private void requireBooleanWhere(Expression where, String tableName) throws MiniDbException {
        requireBoolean(where, ref -> resolveColumn(tableName, ref), "WHERE 条件");
    }

    /** 条件布尔检查：BOOLEAN 放行；NULL 视为未知布尔放行（运行时求值为 NULL 等同过滤，拍板 5）。 */
    private void requireBoolean(Expression e, ColumnResolver resolver, String label)
            throws MiniDbException {
        DataType t = infer(e, resolver);
        if (t != DataType.BOOLEAN && t != DataType.NULL) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, e.pos(),
                    label + "必须是 BOOLEAN, 实际是 " + t);
        }
    }

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

    /** JOIN 列解析（D5 拍板 10）：限定名按表名匹配其一（列须存在，都不匹配报未知表限定符）；
     * 非限定名两表唯一时注册，均存在 → 二义性列（pos = 列引用位置），均不存在 → 列不存在。 */
    private ColumnDef resolveJoinColumn(TableDef left, TableDef right, ColumnRef ref)
            throws MiniDbException {
        if (ref.table() != null) {
            String tableName = ref.table().equalsIgnoreCase(left.tableName()) ? left.tableName()
                    : ref.table().equalsIgnoreCase(right.tableName()) ? right.tableName() : null;
            if (tableName == null) {
                throw new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                        "未知表限定符: " + ref.table());
            }
            return catalog.findColumn(tableName, ref.column())
                    .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                            "列不存在: " + ref.column()));
        }
        Optional<ColumnDef> inLeft = catalog.findColumn(left.tableName(), ref.column());
        Optional<ColumnDef> inRight = catalog.findColumn(right.tableName(), ref.column());
        if (inLeft.isPresent() && inRight.isPresent()) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                    "二义性列: " + ref.column() + "（" + left.tableName() + " 与 "
                            + right.tableName() + " 均存在，请用表名限定）");
        }
        return inLeft.or(() -> inRight).orElseThrow(() ->
                new MiniDbException(MiniDbException.Phase.SEMANTIC, ref.pos(),
                        "列不存在: " + ref.column()));
    }
}
