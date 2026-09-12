package com.minidb.engine;

import com.minidb.ast.FuncCall;
import com.minidb.common.MiniDbException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合执行器（支持 GROUP BY + NULL 语义，拍板 6）：
 *
 * <p>无 GROUP BY：open 拉取子行流完成累计，next 输出单行后结束。
 * <p>有 GROUP BY：按组键元组分组（LinkedHashMap 保序），next 逐组输出 [组键...]+[聚合...]。
 *
 * <p>NULL 语义：
 * COUNT(*) = 全部行数（含 NULL 行）；COUNT(col) = 非 NULL 值数；
 * SUM/AVG/MIN/MAX 忽略 NULL 输入，无任何非 NULL 输入 → NULL；
 * COUNT 永不 NULL（空表 → 0）。
 *
 * <p>SUM(INT)→Integer、SUM(FLOAT)/AVG→Double、COUNT→Integer、MIN/MAX 保持列值原类型。
 */
public class AggregateExecutor implements Executor {

    private final Executor child;
    private final List<FuncCall> aggregates;
    private final List<String> groupBy;  // null = 标量聚合
    private final Map<String, Integer> columnMap;

    /** 标量聚合结果（无 GROUP BY 时使用）。 */
    private Object[] scalarResult;

    /** GROUP BY 分组结果（有 GROUP BY 时使用）。 */
    private List<Object[]> groupResults;
    private int groupCursor;

    /**
     * @param child       子执行器
     * @param aggregates  聚合函数列表
     * @param groupBy     分组列名列表（null = 标量聚合）
     * @param childColumns 子执行器输出的列名列表
     */
    public AggregateExecutor(Executor child, List<FuncCall> aggregates, List<String> groupBy,
                             List<String> childColumns) {
        this.child = child;
        this.aggregates = aggregates;
        this.groupBy = groupBy;
        this.columnMap = new HashMap<>();
        for (int i = 0; i < childColumns.size(); i++) {
            columnMap.put(childColumns.get(i).toLowerCase(), i);
        }
    }

    /** 兼容构造器（标量聚合，既有调用点不破坏）。 */
    public AggregateExecutor(Executor child, List<FuncCall> aggregates, List<String> childColumns) {
        this(child, aggregates, null, childColumns);
    }

    @Override
    public void open() throws MiniDbException {
        child.open();
        if (groupBy == null) {
            scalarResult = computeScalar();
        } else {
            groupResults = computeGrouped();
            groupCursor = 0;
        }
        child.close();
    }

    @Override
    public Object[] next() {
        if (groupBy == null) {
            Object[] out = scalarResult;
            scalarResult = null;
            return out;
        } else {
            if (groupCursor >= groupResults.size()) return null;
            return groupResults.get(groupCursor++);
        }
    }

    @Override
    public void close() {
        // 子执行器已在 open 中消费完毕并 close
    }

    // ------------------------------------------------------------------
    // 标量聚合（无 GROUP BY）
    // ------------------------------------------------------------------

    private Object[] computeScalar() throws MiniDbException {
        long[] counts = new long[aggregates.size()];
        Object[] sums = new Object[aggregates.size()];
        Object[] bests = new Object[aggregates.size()];
        boolean[] hasValue = new boolean[aggregates.size()];

        Object[] row;
        while ((row = child.next()) != null) {
            for (int i = 0; i < aggregates.size(); i++) {
                FuncCall f = aggregates.get(i);
                if ("COUNT".equals(f.func()) && f.arg() == null) {
                    counts[i]++;  // COUNT(*) 全行计数
                    continue;
                }
                Object v = ExpressionEvaluator.evaluate(f.arg(), columnMap, row);
                if (v == null) continue;  // NULL 输入跳过
                switch (f.func()) {
                    case "COUNT" -> counts[i]++;
                    case "SUM" -> sums[i] = add(sums[i], v);
                    case "AVG" -> {
                        sums[i] = add(sums[i], v);
                        counts[i]++;
                    }
                    case "MIN" -> {
                        if (!hasValue[i] || compare(v, bests[i]) < 0) {
                            bests[i] = v;
                            hasValue[i] = true;
                        }
                    }
                    case "MAX" -> {
                        if (!hasValue[i] || compare(v, bests[i]) > 0) {
                            bests[i] = v;
                            hasValue[i] = true;
                        }
                    }
                    default -> throw new MiniDbException(MiniDbException.Phase.PLAN, f.pos(),
                            "未知聚合函数: " + f.func());
                }
            }
        }

        return buildOutput(counts, sums, bests, hasValue);
    }

    // ------------------------------------------------------------------
    // GROUP BY 分组聚合
    // ------------------------------------------------------------------

    private List<Object[]> computeGrouped() throws MiniDbException {
        // 收集分组列索引
        int[] groupIndices = new int[groupBy.size()];
        for (int i = 0; i < groupBy.size(); i++) {
            Integer idx = columnMap.get(groupBy.get(i).toLowerCase());
            if (idx == null) {
                throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                        "GROUP BY 列不存在: " + groupBy.get(i));
            }
            groupIndices[i] = idx;
        }

        // LinkedHashMap 保序分组
        LinkedHashMap<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        Object[] row;
        while ((row = child.next()) != null) {
            List<Object> key = new ArrayList<>();
            for (int idx : groupIndices) {
                key.add(row[idx]);
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // 每组计算聚合
        List<Object[]> results = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Object[]>> entry : groups.entrySet()) {
            List<Object> groupKey = entry.getKey();
            List<Object[]> groupRows = entry.getValue();

            long[] counts = new long[aggregates.size()];
            Object[] sums = new Object[aggregates.size()];
            Object[] bests = new Object[aggregates.size()];
            boolean[] hasValue = new boolean[aggregates.size()];

            for (Object[] r : groupRows) {
                for (int i = 0; i < aggregates.size(); i++) {
                    FuncCall f = aggregates.get(i);
                    if ("COUNT".equals(f.func()) && f.arg() == null) {
                        counts[i]++;
                        continue;
                    }
                    Object v = ExpressionEvaluator.evaluate(f.arg(), columnMap, r);
                    if (v == null) continue;
                    switch (f.func()) {
                        case "COUNT" -> counts[i]++;
                        case "SUM" -> sums[i] = add(sums[i], v);
                        case "AVG" -> {
                            sums[i] = add(sums[i], v);
                            counts[i]++;
                        }
                        case "MIN" -> {
                            if (!hasValue[i] || compare(v, bests[i]) < 0) {
                                bests[i] = v;
                                hasValue[i] = true;
                            }
                        }
                        case "MAX" -> {
                            if (!hasValue[i] || compare(v, bests[i]) > 0) {
                                bests[i] = v;
                                hasValue[i] = true;
                            }
                        }
                    }
                }
            }

            Object[] aggOut = buildOutput(counts, sums, bests, hasValue);
            // 输出行 = [组键...] ++ [聚合...]
            Object[] out = new Object[groupKey.size() + aggOut.length];
            for (int i = 0; i < groupKey.size(); i++) {
                out[i] = groupKey.get(i);
            }
            System.arraycopy(aggOut, 0, out, groupKey.size(), aggOut.length);
            results.add(out);
        }
        return results;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Object[] buildOutput(long[] counts, Object[] sums, Object[] bests, boolean[] hasValue) {
        Object[] out = new Object[aggregates.size()];
        for (int i = 0; i < aggregates.size(); i++) {
            out[i] = switch (aggregates.get(i).func()) {
                case "COUNT" -> (int) counts[i];
                case "SUM" -> sums[i];  // null if no non-NULL input
                case "AVG" -> counts[i] == 0 ? null : ((Number) sums[i]).doubleValue() / counts[i];
                default -> hasValue[i] ? bests[i] : null;  // MIN / MAX
            };
        }
        return out;
    }

    /** 数值累加：Integer+Integer→Integer（SUM(INT) 保整型），其余按 double。 */
    private static Object add(Object acc, Object v) {
        if (acc == null) return v;
        if (acc instanceof Integer i && v instanceof Integer j) return i + j;
        return ((Number) acc).doubleValue() + ((Number) v).doubleValue();
    }

    /** 数值按 double 比较；VARCHAR 字典序。 */
    private static int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        return ((String) a).compareTo((String) b);
    }
}
