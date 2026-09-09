package com.minidb.engine;

import com.minidb.common.MiniDbException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 投影执行器：列裁剪。columns 为 null 整行透传（SELECT *）。
 * 否则按 columns 指定的列名从子执行器输出中提取对应列。
 */
public class ProjectExecutor implements Executor {

    private final Executor child;
    private final List<String> columns;  // null = 透传全部列
    private final int[] indices;         // 投影列在子输出中的索引（columns != null 时有效）

    /**
     * @param child         子执行器
     * @param columns       要投影的列名列表（null = SELECT *）
     * @param childColumns  子执行器输出的列名列表（用于建立索引映射）
     */
    public ProjectExecutor(Executor child, List<String> columns, List<String> childColumns) {
        this.child = child;
        this.columns = columns;
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
    }

    @Override
    public Object[] next() throws MiniDbException {
        Object[] childRow = child.next();
        if (childRow == null) return null;
        if (columns == null || indices == null) {
            return childRow;  // SELECT * 透传
        }
        Object[] projected = new Object[columns.size()];
        for (int i = 0; i < indices.length; i++) {
            projected[i] = childRow[indices[i]];
        }
        return projected;
    }

    @Override
    public void close() {
        child.close();
    }
}
