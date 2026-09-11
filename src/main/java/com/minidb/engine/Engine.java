package com.minidb.engine;

import com.minidb.ast.FuncCall;
import com.minidb.ast.Literal;
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
import com.minidb.plan.JoinPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import com.minidb.plan.SortPlan;
import com.minidb.plan.UpdatePlan;
import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行引擎入口。
 *
 * <p>{@link #execute(PlanNode)} 处理 DDL/DML（CREATE / INSERT / DELETE / UPDATE），返回 void。
 * <p>{@link #executeQuery(PlanNode)} 处理查询（SELECT），返回行集 {@code List<Object[]>}。
 *
 * <p>火山模型执行器树：SeqScan / Filter / Project / Aggregate / Sort / Join。
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
     * @return UPDATE 时返回更新行数，其它语句返回 null。
     */
    public Integer execute(PlanNode plan) throws MiniDbException {
        return switch (plan) {
            case CreateTablePlan p -> { executeCreateTable(p); yield null; }
            case InsertPlan p -> { executeInsert(p); yield null; }
            case DeletePlan p -> { executeDelete(p); yield null; }
            case UpdatePlan p -> executeUpdate(p);
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "不支持的计划类型: " + plan.getClass().getSimpleName());
        };
    }

    /** 执行 UPDATE 计划（两阶段，拍板 3），返回更新行数。 */
    private int executeUpdate(UpdatePlan plan) throws MiniDbException {
        String tableNameKey = plan.tableName().toLowerCase();
        List<Integer> pageIds = tablePages.getOrDefault(tableNameKey, List.of());
        List<ColumnDef> columnDefs = getColumnDefs(plan.tableName());
        UpdateExecutor updater = new UpdateExecutor(pool, tableNameKey, pageIds, columnDefs,
                plan.sets(), plan.condition());
        return updater.execute();
    }

    /**
     * 执行查询计划节点，返回结果行集。
     */
    public List<Object[]> executeQuery(PlanNode plan) throws MiniDbException {
        List<String> colNames = getPlanColumnNames(plan);
        Executor executor = buildExecutor(plan, colNames);

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
     */
    public List<String> getQueryColumnNames(PlanNode plan) throws MiniDbException {
        return getPlanColumnNames(plan);
    }

    // ------------------------------------------------------------------
    // 递归计算计划树输出列名
    // ------------------------------------------------------------------

    private List<String> getPlanColumnNames(PlanNode plan) throws MiniDbException {
        return switch (plan) {
            case SeqScan s -> getColumnNames(s.tableName());
            case Filter f -> getPlanColumnNames(f.child());
            case Project p -> {
                if (p.columns() != null) {
                    yield p.columns();
                }
                yield getPlanColumnNames(p.child());
            }
            case AggregatePlan a -> {
                List<String> names = new ArrayList<>();
                if (a.groupBy() != null) {
                    names.addAll(a.groupBy());
                }
                for (FuncCall f : a.aggregates()) {
                    names.add(f.display());
                }
                yield names;
            }
            case SortPlan s -> getPlanColumnNames(s.child());
            case JoinPlan jp -> {
                List<String> names = new ArrayList<>();
                String leftTable = findTableName(jp.left());
                for (String c : getPlanColumnNames(jp.left())) {
                    names.add(leftTable.toLowerCase() + "." + c.toLowerCase());
                }
                String rightTable = findTableName(jp.right());
                for (String c : getPlanColumnNames(jp.right())) {
                    names.add(rightTable.toLowerCase() + "." + c.toLowerCase());
                }
                yield names;
            }
            default -> List.of();
        };
    }

    private String findTableName(PlanNode plan) throws MiniDbException {
        return switch (plan) {
            case SeqScan s -> s.tableName();
            case Filter f -> findTableName(f.child());
            case Project p -> findTableName(p.child());
            case AggregatePlan a -> findTableName(a.input());
            case SortPlan s -> findTableName(s.child());
            case JoinPlan j -> findTableName(j.left());
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "无法确定表名: " + plan.getClass().getSimpleName());
        };
    }

    // ------------------------------------------------------------------
    // 执行器构建（每个执行器从子计划自行计算列名）
    // ------------------------------------------------------------------

    private Executor buildExecutor(PlanNode plan, List<String> parentColumns) throws MiniDbException {
        return switch (plan) {
            case SeqScan s -> {
                List<Integer> pageIds = tablePages.getOrDefault(s.tableName().toLowerCase(), List.of());
                List<ColumnDef> cols = getColumnDefs(s.tableName());
                yield new SeqScanExecutor(pool, s.tableName().toLowerCase(), pageIds, cols);
            }
            case Filter f -> {
                List<String> childCols = getPlanColumnNames(f.child());
                Executor child = buildExecutor(f.child(), childCols);
                yield new FilterExecutor(child, f.condition(), childCols);
            }
            case Project p -> {
                List<String> childCols = getPlanColumnNames(p.child());
                Executor child = buildExecutor(p.child(), childCols);
                yield new ProjectExecutor(child, p.columns(), childCols, p.distinct());
            }
            case AggregatePlan a -> {
                List<String> childCols = getPlanColumnNames(a.input());
                Executor child = buildExecutor(a.input(), childCols);
                yield new AggregateExecutor(child, a.aggregates(), a.groupBy(), childCols);
            }
            case SortPlan s -> {
                List<String> childCols = getPlanColumnNames(s.child());
                Executor child = buildExecutor(s.child(), childCols);
                yield new SortExecutor(child, s.keys(), childCols);
            }
            case JoinPlan jp -> {
                String leftTableName = findTableName(jp.left());
                String rightTableName = findTableName(jp.right());
                List<String> leftCols = getColumnNames(leftTableName);
                List<String> rightCols = getColumnNames(rightTableName);
                Executor leftExec = buildExecutor(jp.left(), leftCols);
                Executor rightExec = buildExecutor(jp.right(), rightCols);
                yield new NestedLoopJoinExecutor(leftExec, rightExec, jp.condition(),
                        leftTableName, rightTableName, leftCols, rightCols);
            }
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "查询计划不支持: " + plan.getClass().getSimpleName());
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

        List<Integer> pageIds = tablePages.computeIfAbsent(tableNameKey, k -> new ArrayList<>());
        if (pageIds.isEmpty()) {
            Page newPage = pool.newPage(tableNameKey);
            pageIds.add(newPage.pageId());
        }

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

            int lastPageId = pageIds.get(pageIds.size() - 1);
            Page currentPage = pool.getPage(tableNameKey, lastPageId);
            if (currentPage == null) {
                throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                        "页不存在或已被缓冲池淘汰: " + tableNameKey + "#" + lastPageId);
            }
            int slot = currentPage.insertRow(encoded);

            if (slot == -1) {
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

        Map<String, Integer> colMap = new HashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            colMap.put(columnNames.get(i).toLowerCase(), i);
        }

        for (int pageId : pageIds) {
            Page page = pool.getPage(tableNameKey, pageId);
            if (page == null) continue;

            for (int slot = 0; slot < page.slotCount(); slot++) {
                byte[] raw = page.readRow(slot);
                if (raw == null) continue;

                if (plan.condition() == null) {
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

    /** 重启恢复：按磁盘文件页数重建表的页映射。 */
    public void recoverTablePages(String tableName, int pageCount) {
        String key = tableName.toLowerCase();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            ids.add(i);
        }
        tablePages.put(key, ids);
    }
}
