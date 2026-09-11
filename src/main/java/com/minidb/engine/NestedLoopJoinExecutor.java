package com.minidb.engine;

import com.minidb.ast.Expression;
import com.minidb.common.MiniDbException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌套循环 JOIN 执行器（INNER JOIN，拍板 9）：
 * 左×右拼 Object[]，ON 为 TRUE 放行。
 * 列映射双注册（拍板 10）：限定名恒注册，非限定名两表唯一时注册。
 * 合成行 = 左表列 ++ 右表列。
 */
public class NestedLoopJoinExecutor implements Executor {

    private final Executor leftExec;
    private final Executor rightExec;
    private final Expression condition;
    private final Map<String, Integer> mergedColumnMap;
    private final List<String> outputColumns;

    private Object[] currentLeftRow;
    private final List<Object[]> rightRows = new ArrayList<>();
    private int rightIdx;

    /**
     * @param leftExec       左表执行器
     * @param rightExec      右表执行器
     * @param condition      ON 条件
     * @param leftTableName  左表名
     * @param rightTableName 右表名
     * @param leftCols       左表列名
     * @param rightCols      右表列名
     */
    public NestedLoopJoinExecutor(Executor leftExec, Executor rightExec, Expression condition,
                                   String leftTableName, String rightTableName,
                                   List<String> leftCols, List<String> rightCols) {
        this.leftExec = leftExec;
        this.rightExec = rightExec;
        this.condition = condition;

        // 构建合并列映射（双注册）
        this.outputColumns = new ArrayList<>();
        this.mergedColumnMap = new HashMap<>();
        int idx = 0;

        // 左表列
        for (String col : leftCols) {
            String qualKey = leftTableName.toLowerCase() + "." + col.toLowerCase();
            outputColumns.add(qualKey);
            mergedColumnMap.put(qualKey, idx);
            // 非限定名仅唯一时注册
            if (!mergedColumnMap.containsKey(col.toLowerCase())) {
                mergedColumnMap.put(col.toLowerCase(), idx);
            } else {
                // 二义：移除非限定名（标记为二义，后续查找会失败）
                mergedColumnMap.remove(col.toLowerCase());
            }
            idx++;
        }

        // 右表列
        for (String col : rightCols) {
            String qualKey = rightTableName.toLowerCase() + "." + col.toLowerCase();
            outputColumns.add(qualKey);
            mergedColumnMap.put(qualKey, idx);
            if (!mergedColumnMap.containsKey(col.toLowerCase())) {
                mergedColumnMap.put(col.toLowerCase(), idx);
            } else {
                mergedColumnMap.remove(col.toLowerCase());
            }
            idx++;
        }
    }

    @Override
    public void open() throws MiniDbException {
        leftExec.open();
        rightExec.open();
        // 缓存右表全部行
        rightRows.clear();
        Object[] row;
        while ((row = rightExec.next()) != null) {
            rightRows.add(row);
        }
        rightExec.close();
        currentLeftRow = null;
        rightIdx = 0;
    }

    @Override
    public Object[] next() throws MiniDbException {
        while (true) {
            // 尝试当前 左行 × 右行
            if (currentLeftRow != null && rightIdx < rightRows.size()) {
                Object[] rightRow = rightRows.get(rightIdx++);
                Object[] merged = merge(currentLeftRow, rightRow);
                if (condition == null) {
                    return merged;
                }
                Object result = ExpressionEvaluator.evaluate(condition, mergedColumnMap, merged);
                if (Boolean.TRUE.equals(result)) {
                    return merged;
                }
                continue;
            }
            // 取下一个左行
            currentLeftRow = leftExec.next();
            if (currentLeftRow == null) {
                return null;  // 左表耗尽
            }
            rightIdx = 0;
        }
    }

    @Override
    public void close() {
        leftExec.close();
    }

    private static Object[] merge(Object[] left, Object[] right) {
        Object[] merged = new Object[left.length + right.length];
        System.arraycopy(left, 0, merged, 0, left.length);
        System.arraycopy(right, 0, merged, left.length, right.length);
        return merged;
    }

    /** 获取合并后的列名列表（供上层执行器使用）。 */
    public List<String> getOutputColumns() {
        return outputColumns;
    }

    /** 获取合并后的列映射（供 Filter/Project 使用）。 */
    public Map<String, Integer> getMergedColumnMap() {
        return mergedColumnMap;
    }
}
