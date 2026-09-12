package com.minidb.engine;

import com.minidb.common.MiniDbException;
import com.minidb.plan.SortKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 排序执行器（火山模型）：open 拉取子执行器全部行 → 多级稳定排序 → next 逐行输出。
 * NULL 视为最小值（拍板 7）：ASC 时 NULL 在前，DESC 时 NULL 在后。
 */
public class SortExecutor implements Executor {

    private final Executor child;
    private final List<SortKey> sortKeys;
    private final Map<String, Integer> columnMap;

    private List<Object[]> sortedRows;
    private int cursor;

    public SortExecutor(Executor child, List<SortKey> sortKeys, List<String> childColumns) {
        this.child = child;
        this.sortKeys = sortKeys;
        this.columnMap = new HashMap<>();
        if (childColumns != null) {
            for (int i = 0; i < childColumns.size(); i++) {
                String lower = childColumns.get(i).toLowerCase();
                columnMap.put(lower, i);
                // 限定名列同时注册非限定名（仅唯一时覆盖）
                int dotPos = lower.indexOf('.');
                if (dotPos >= 0) {
                    String unqual = lower.substring(dotPos + 1);
                    columnMap.putIfAbsent(unqual, i);
                }
            }
        }
    }

    @Override
    public void open() throws MiniDbException {
        child.open();
        sortedRows = new ArrayList<>();
        Object[] row;
        while ((row = child.next()) != null) {
            sortedRows.add(row);
        }
        child.close();

        // 多级稳定排序
        for (int i = sortKeys.size() - 1; i >= 0; i--) {
            SortKey key = sortKeys.get(i);
            String colName = key.column().toLowerCase();
            Integer idx = columnMap.get(colName);
            if (idx == null) {
                throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                        "ORDER BY 列不存在: " + key.column());
            }
            boolean asc = key.asc();
            sortedRows.sort((a, b) -> {
                Object va = a[idx];
                Object vb = b[idx];
                int cmp = compareWithNull(va, vb);
                return asc ? cmp : -cmp;
            });
        }
        cursor = 0;
    }

    @Override
    public Object[] next() {
        if (cursor >= sortedRows.size()) return null;
        return sortedRows.get(cursor++);
    }

    @Override
    public void close() {
        sortedRows = null;
    }

    /** NULL 最小比较：null vs null=0, null vs x=-1, x vs null=1。 */
    private static int compareWithNull(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        return 0;
    }
}
