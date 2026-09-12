package com.minidb.engine;

import com.minidb.ast.Expression;
import com.minidb.ast.SetClause;
import com.minidb.buffer.BufferPool;
import com.minidb.catalog.ColumnDef;
import com.minidb.common.MiniDbException;
import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UPDATE 执行器（两阶段，拍板 3）：
 * 阶段一：扫描全表，收集命中行的 decoded values（不存 slot，避免删除后偏移）。
 * 阶段二：逐行求值 SET 表达式 → 重扫描找到旧行 → deleteRow + insertRow。
 */
public class UpdateExecutor {

    private final BufferPool pool;
    private final String tableName;
    private final List<Integer> pageIds;
    private final List<ColumnDef> columnDefs;
    private final List<SetClause> sets;
    private final Expression condition;
    private final Map<String, Integer> colMap;

    public UpdateExecutor(BufferPool pool, String tableName, List<Integer> pageIds,
                          List<ColumnDef> columnDefs, List<SetClause> sets, Expression condition) {
        this.pool = pool;
        this.tableName = tableName;
        this.pageIds = pageIds;
        this.columnDefs = columnDefs;
        this.sets = sets;
        this.condition = condition;
        this.colMap = new HashMap<>();
        List<String> names = columnDefs.stream().map(ColumnDef::name).toList();
        for (int i = 0; i < names.size(); i++) {
            colMap.put(names.get(i).toLowerCase(), i);
        }
    }

    /** 执行两阶段更新，返回更新的行数。 */
    public int execute() throws MiniDbException {
        // 阶段一：收集命中行的值（仅保存 decoded values，不保存 slot 索引）
        List<Object[]> hitValues = new ArrayList<>();

        for (int pageId : pageIds) {
            Page page = pool.getPage(tableName, pageId);
            if (page == null) continue;
            for (int slot = 0; slot < page.slotCount(); slot++) {
                byte[] raw = page.readRow(slot);
                if (raw == null) continue;
                Object[] values = RowEncoder.decode(columnDefs, raw);
                if (condition == null) {
                    hitValues.add(values);
                } else {
                    Object result = ExpressionEvaluator.evaluate(condition, colMap, values);
                    if (Boolean.TRUE.equals(result)) {
                        hitValues.add(values);
                    }
                }
            }
        }

        // 阶段二：逐行求值 SET → 重扫描找到旧行 → deleteRow + insertRow
        for (Object[] oldValues : hitValues) {
            // 计算新值
            Object[] newValues = oldValues.clone();
            for (SetClause sc : sets) {
                String colName = sc.column().column().toLowerCase();
                Integer idx = colMap.get(colName);
                if (idx == null) {
                    throw new MiniDbException(MiniDbException.Phase.PLAN, sc.column().pos(),
                            "列不存在: " + sc.column().column());
                }
                newValues[idx] = ExpressionEvaluator.evaluate(sc.value(), colMap, oldValues);
            }

            // 重扫描找到旧行并删除（按内容匹配，避免 slot 偏移问题）
            byte[] oldEncoded = RowEncoder.encode(columnDefs, oldValues);
            boolean deleted = false;
            for (int pageId : pageIds) {
                Page page = pool.getPage(tableName, pageId);
                if (page == null) continue;
                for (int slot = 0; slot < page.slotCount(); slot++) {
                    byte[] raw = page.readRow(slot);
                    if (raw == null) continue;
                    if (Arrays.equals(raw, oldEncoded)) {
                        page.deleteRow(slot);
                        deleted = true;
                        break;
                    }
                }
                if (deleted) break;
            }

            // 插入新行
            byte[] encoded = RowEncoder.encode(columnDefs, newValues);
            int lastPageId = pageIds.get(pageIds.size() - 1);
            Page lastPage = pool.getPage(tableName, lastPageId);
            int slot = lastPage.insertRow(encoded);
            if (slot == -1) {
                Page newPage = pool.newPage(tableName);
                pageIds.add(newPage.pageId());
                slot = newPage.insertRow(encoded);
                if (slot == -1) {
                    throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                            "行数据过大，无法插入空页");
                }
            }
        }
        return hitValues.size();
    }
}
