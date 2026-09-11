package com.minidb.engine;

import com.minidb.common.MiniDbException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投影执行器：列裁剪。columns 为 null 整行透传（SELECT *）。
 * 否则按 columns 指定的列名从子执行器输出中提取对应列。
 *
 * <p>distinct 为 SELECT DISTINCT 的行值去重（拍板：流式 next 逐行判重，
 * 输出天然保持输入序；值相等 = 投影后整行相等）。
 */
public class ProjectExecutor implements Executor {

    private final Executor child;
    private final List<String> columns;  // null = 透传全部列
    private final int[] indices;         // 投影列在子输出中的索引（columns != null 时有效）
    private final boolean distinct;
    private final Set<List<Object>> seen = new HashSet<>();

    /**
     * @param child         子执行器
     * @param columns       要投影的列名列表（null = SELECT *）
     * @param childColumns  子执行器输出的列名列表（用于建立索引映射）
     */
    public ProjectExecutor(Executor child, List<String> columns, List<String> childColumns) {
        this(child, columns, childColumns, false);
    }

    /**
     * @param distinct      SELECT DISTINCT 去重标记
     */
    public ProjectExecutor(Executor child, List<String> columns, List<String> childColumns,
                           boolean distinct) {
        this.child = child;
        this.columns = columns;
        this.distinct = distinct;
        if (columns != null && childColumns != null) {
            Map<String, Integer> nameToIdx = new HashMap<>();
            for (int i = 0; i < childColumns.size(); i++) {
                nameToIdx.put(childColumns.get(i).toLowerCase(), i);
            }
            this.indices = new int[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                Integer idx = nameToIdx.get(columns.get(i).toLowerCase());
                if (idx == null) {
                    throw new IllegalArgumentException("投影列不存在: " + columns.get(i));
                }
                this.indices[i] = idx;
            }
        } else {
            this.indices = null;
        }
    }

    @Override
    public void open() throws MiniDbException {
        child.open();
        seen.clear();
    }

    @Override
    public Object[] next() throws MiniDbException {
        Object[] childRow;
        while ((childRow = child.next()) != null) {
            Object[] projected;
            if (columns == null || indices == null) {
                projected = childRow;  // SELECT * 透传
            } else {
                projected = new Object[columns.size()];
                for (int i = 0; i < indices.length; i++) {
                    projected[i] = childRow[indices[i]];
                }
            }
            if (distinct && !seen.add(Arrays.asList(projected))) {
                continue;  // 重复行跳过，取下一行
            }
            return projected;
        }
        return null;
    }

    @Override
    public void close() {
        child.close();
    }
}
