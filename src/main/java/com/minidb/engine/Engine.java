package com.minidb.engine;

import com.minidb.ast.Literal;
import com.minidb.buffer.BufferPool;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.TableDef;
import com.minidb.common.MiniDbException;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行引擎入口。
 *
 * <p>支持 CreateTablePlan / InsertPlan。DeletePlan 抛"未实现"占位（执行器新 D3）。
 * SELECT 查询算子（SeqScan/Filter/Project 火山模型）新 D3 交付。
 *
 * <p>表→页映射：Engine 内 Map&lt;String, List&lt;Integer&gt;&gt;（内存阶段的临时组织，新 D3 磁盘版替换）。
 */
public class Engine {

    private final Catalog catalog;
    private final BufferPool pool;

    /** 表→页 ID 列表映射（内存阶段临时组织）。 */
    private final Map<String, List<Integer>> tablePages = new HashMap<>();

    public Engine(Catalog catalog, BufferPool pool) {
        this.catalog = catalog;
        this.pool = pool;
    }

    /**
     * 执行计划节点。
     *
     * @param plan CreateTablePlan / InsertPlan / DeletePlan
     * @throws MiniDbException 执行失败
     */
    public void execute(PlanNode plan) throws MiniDbException {
        switch (plan) {
            case CreateTablePlan p -> executeCreateTable(p);
            case InsertPlan p -> executeInsert(p);
            case DeletePlan ignored -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "DELETE 执行器未实现（新 D3）");
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "不支持的计划类型: " + plan.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // CreateTable
    // ------------------------------------------------------------------

    private void executeCreateTable(CreateTablePlan plan) throws MiniDbException {
        catalog.createTable(plan.table());
        // 初始化表的页映射（空列表）
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
        // targetColumns 为空 = INSERT 未指定列 = 使用表定义序全列
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
            // 提取字面量值
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

            // 编码
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
    // 查询辅助（供测试使用）
    // ------------------------------------------------------------------

    /** 获取指定表的页 ID 列表（测试/调试用）。 */
    public List<Integer> getTablePageIds(String tableName) {
        return tablePages.getOrDefault(tableName.toLowerCase(), List.of());
    }
}
