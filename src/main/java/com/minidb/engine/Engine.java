package com.minidb.engine;

import com.minidb.ast.Expression;
import com.minidb.ast.FuncCall;
import com.minidb.ast.Literal;
import com.minidb.ast.SelectStmt;
import com.minidb.buffer.BufferPool;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.TableDef;
import com.minidb.common.MiniDbException;
import com.minidb.plan.AggregatePlan;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行引擎入口。
 *
 * <p>{@link #execute(PlanNode)} 处理 DDL/DML（CREATE / INSERT / DELETE），返回 void。
 * <p>{@link #executeQuery(PlanNode)} 处理查询（SELECT），返回行集 {@code List<Object[]>}。
 *
 * <p>SELECT/DELETE 执行器采用火山模型：SeqScan → Filter → Project。
 *
 * <p>表→页映射：Engine 内 Map&lt;String, List&lt;Integer&gt;&gt;。
 */
public class Engine {

    private final Catalog catalog;
    private final BufferPool pool;

    /** 表→页 ID 列表映射。 */
    private final Map<String, List<Integer>> tablePages = new HashMap<>();

    public Engine(Catalog catalog, BufferPool pool) {
        this.catalog = catalog;
        this.pool = pool;
    }

    /**
     * 执行 DDL/DML 计划节点。
     *
     * @param plan CreateTablePlan / InsertPlan / DeletePlan
     * @throws MiniDbException 执行失败
     */
    public void execute(PlanNode plan) throws MiniDbException {
        switch (plan) {
            case CreateTablePlan p -> executeCreateTable(p);
            case InsertPlan p -> executeInsert(p);
            case DeletePlan p -> executeDelete(p);
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "不支持的计划类型: " + plan.getClass().getSimpleName());
        }
    }

    /**
     * 执行查询计划节点，返回结果行集。
     *
     * @param plan SELECT 计划树（Project → Filter → SeqScan 的任意组合，或 AggregatePlan）
     * @return 查询结果行集，每行为 Object[]
     * @throws MiniDbException 执行失败
     */
    public List<Object[]> executeQuery(PlanNode plan) throws MiniDbException {
        return executeQuery(plan, null);
    }

    /**
     * 执行查询计划节点，返回结果行集（支持 DISTINCT）。
     *
     * @param plan     SELECT 计划树
     * @param selectStmt 原始 SELECT AST（用于获取 DISTINCT 标志，可为 null）
     * @return 查询结果行集，每行为 Object[]
     * @throws MiniDbException 执行失败
     */
    public List<Object[]> executeQuery(PlanNode plan, SelectStmt selectStmt) throws MiniDbException {
        String tableName = findTableName(plan);
        List<String> allColumnNames = getColumnNames(tableName);
        boolean distinct = selectStmt != null && selectStmt.distinct();
        Executor executor = buildExecutor(plan, allColumnNames, distinct);

        // 火山模型：open → next → close
        List<Object[]> results = new ArrayList<>();
        executor.open();
        Object[] row;
        while ((row = executor.next()) != null) {
            results.add(row);
        }
        executor.close();
        return results;
    }

    /**
     * 查询结果的列名（供 CLI 打印表头）。
     *
     * @param plan SELECT 计划树
     * @return 输出列名列表
     */
    public List<String> getQueryColumnNames(PlanNode plan) {
        return switch (plan) {
            case Project p -> {
                if (p.columns() != null) {
                    yield p.columns();
                }
                yield getChildColumnNames(p.child());
            }
            case Filter f -> getChildColumnNames(f.child());
            case SeqScan s -> getColumnNames(s.tableName());
            case AggregatePlan ap -> {
                List<String> names = new ArrayList<>();
                for (FuncCall fc : ap.aggregates()) {
                    String argStr = fc.arg() != null
                            ? funcArgToString(fc.arg())
                            : "*";
                    names.add(fc.func().toLowerCase() + "(" + argStr + ")");
                }
                yield names;
            }
            default -> List.of();
        };
    }

    private String funcArgToString(Expression expr) {
        return switch (expr) {
            case com.minidb.ast.ColumnRef ref -> ref.column();
            case Literal lit -> String.valueOf(lit.value());
            default -> expr.toString();
        };
    }

    private List<String> getChildColumnNames(PlanNode node) {
        return switch (node) {
            case Project p -> {
                if (p.columns() != null) {
                    yield p.columns();
                }
                yield getChildColumnNames(p.child());
            }
            case Filter f -> getChildColumnNames(f.child());
            case SeqScan s -> getColumnNames(s.tableName());
            case AggregatePlan ap -> {
                List<String> names = new ArrayList<>();
                for (FuncCall fc : ap.aggregates()) {
                    String argStr = fc.arg() != null
                            ? funcArgToString(fc.arg())
                            : "*";
                    names.add(fc.func().toLowerCase() + "(" + argStr + ")");
                }
                yield names;
            }
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------
    // 执行器构建（计划树 → 执行器树）
    // ------------------------------------------------------------------

    /**
     * 递归构建执行器。
     * @param availableColumns 当前层级可用的列名列表（从 SeqScan 向上传递）
     * @param distinct 是否 DISTINCT（仅 Project 层使用）
     */
    private Executor buildExecutor(PlanNode plan, List<String> availableColumns, boolean distinct) throws MiniDbException {
        return switch (plan) {
            case SeqScan s -> {
                List<Integer> pageIds = tablePages.getOrDefault(s.tableName().toLowerCase(), List.of());
                List<ColumnDef> cols = getColumnDefs(s.tableName());
                yield new SeqScanExecutor(pool, s.tableName().toLowerCase(), pageIds, cols);
            }
            case Filter f -> {
                Executor child = buildExecutor(f.child(), availableColumns, false);
                yield new FilterExecutor(child, f.condition(), availableColumns);
            }
            case Project p -> {
                Executor child = buildExecutor(p.child(), availableColumns, false);
                yield new ProjectExecutor(child, p.columns(), availableColumns, distinct);
            }
            case AggregatePlan ap -> {
                Executor child = buildExecutor(ap.input(), availableColumns, false);
                yield new AggregateExecutor(child, ap.aggregates(), availableColumns);
            }
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "查询计划不支持: " + plan.getClass().getSimpleName());
        };
    }

    /** 从计划树中找到底层的表名。 */
    private String findTableName(PlanNode plan) throws MiniDbException {
        return switch (plan) {
            case SeqScan s -> s.tableName();
            case Filter f -> findTableName(f.child());
            case Project p -> findTableName(p.child());
            case AggregatePlan ap -> findTableName(ap.input());
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "无法确定表名: " + plan.getClass().getSimpleName());
        };
    }

    // ------------------------------------------------------------------
    // CreateTable
    // ------------------------------------------------------------------

    private void executeCreateTable(CreateTablePlan plan) throws MiniDbException {
        catalog.createTable(plan.table());
        tablePages.put(plan.table().tableName().toLowerCase(), new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // Insert
    // ------------------------------------------------------------------

    private void executeInsert(InsertPlan plan) throws MiniDbException {
        String tableNameKey = plan.tableName().toLowerCase();
        TableDef tableDef = catalog.findTable(plan.tableName())
                .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, null,
                        "表不存在: " + plan.tableName()));

        List<ColumnDef> allColumns = tableDef.columns();

        // 按 targetColumns 序构建对应的 ColumnDef 列表
        List<ColumnDef> targetColumnDefs;
        if (plan.targetColumns().isEmpty()) {
            targetColumnDefs = allColumns;
        } else {
            targetColumnDefs = new ArrayList<>();
            for (String colName : plan.targetColumns()) {
                ColumnDef found = allColumns.stream()
                        .filter(c -> c.name().equalsIgnoreCase(colName))
                        .findFirst()
                        .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, null,
                                "列不存在: " + colName));
                targetColumnDefs.add(found);
            }
        }

        // 获取或创建首页
        List<Integer> pageIds = tablePages.computeIfAbsent(tableNameKey, k -> new ArrayList<>());
        if (pageIds.isEmpty()) {
            Page newPage = pool.newPage(tableNameKey);
            pageIds.add(newPage.pageId());
        }

        // 逐行插入
        for (List<com.minidb.ast.Expression> row : plan.rows()) {
            Object[] values = new Object[row.size()];
            for (int i = 0; i < row.size(); i++) {
                com.minidb.ast.Expression expr = row.get(i);
                if (expr instanceof Literal lit) {
                    values[i] = lit.value();
                } else {
                    throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                            "INSERT 值必须是字面量");
                }
            }

            byte[] encoded = RowEncoder.encode(targetColumnDefs, values);

            // 尝试插入当前最后一页
            int lastPageId = pageIds.get(pageIds.size() - 1);
            Page currentPage = pool.getPage(tableNameKey, lastPageId);
            int slot = currentPage.insertRow(encoded);

            if (slot == -1) {
                // 当前页满，新建页
                Page newPage = pool.newPage(tableNameKey);
                pageIds.add(newPage.pageId());
                slot = newPage.insertRow(encoded);
                if (slot == -1) {
                    throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                            "行数据过大，无法插入空页");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    private void executeDelete(DeletePlan plan) throws MiniDbException {
        String tableNameKey = plan.tableName().toLowerCase();
        List<Integer> pageIds = tablePages.getOrDefault(tableNameKey, List.of());
        List<ColumnDef> columnDefs = getColumnDefs(plan.tableName());
        List<String> columnNames = columnDefs.stream().map(ColumnDef::name).toList();

        // 构建列名→索引映射
        Map<String, Integer> colMap = new HashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            colMap.put(columnNames.get(i).toLowerCase(), i);
        }

        // 逐页逐槽扫描，命中则 deleteRow
        for (int pageId : pageIds) {
            Page page = pool.getPage(tableNameKey, pageId);
            if (page == null) continue;

            int maxSlot = (page instanceof com.minidb.storage.MemoryPage mp) ? mp.getNextSlot() : 0;
            for (int slot = 0; slot < maxSlot; slot++) {
                byte[] raw = page.readRow(slot);
                if (raw == null) continue;  // 已删除

                if (plan.condition() == null) {
                    // 无 WHERE → 全删
                    page.deleteRow(slot);
                } else {
                    Object[] values = RowEncoder.decode(columnDefs, raw);
                    Object result = ExpressionEvaluator.evaluate(plan.condition(), colMap, values);
                    if (Boolean.TRUE.equals(result)) {
                        page.deleteRow(slot);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private List<ColumnDef> getColumnDefs(String tableName) throws MiniDbException {
        TableDef tableDef = catalog.findTable(tableName)
                .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, null,
                        "表不存在: " + tableName));
        return tableDef.columns();
    }

    private List<String> getColumnNames(String tableName) {
        return catalog.findTable(tableName).stream()
                .flatMap(t -> t.columns().stream())
                .map(ColumnDef::name)
                .toList();
    }

    /** 获取指定表的页 ID 列表（测试/调试用）。 */
    public List<Integer> getTablePageIds(String tableName) {
        return tablePages.getOrDefault(tableName.toLowerCase(), List.of());
    }
}
