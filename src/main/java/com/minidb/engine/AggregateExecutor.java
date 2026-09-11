package com.minidb.engine;

import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.FuncCall;
import com.minidb.common.MiniDbException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合执行器：消费子执行器全部行，累计五个聚合函数（COUNT/SUM/AVG/MIN/MAX），产出单行。
 *
 * <p>空表语义：COUNT → 0、SUM → 0、AVG/MIN/MAX → 0（系统无 NULL 值）。
 * 与标准 SQL 差异：标准 SQL 中 SUM 空表返回 NULL，此处返回 0。
 */
public class AggregateExecutor implements Executor {

    private final Executor child;
    private final List<FuncCall> aggregates;
    private final Map<String, Integer> columnMap;  // 列名(小写) → 索引
    private final List<String> childColumns;

    private Object[] result;
    private boolean consumed;

    public AggregateExecutor(Executor child, List<FuncCall> aggregates, List<String> childColumns) {
        this.child = child;
        this.aggregates = aggregates;
        this.childColumns = childColumns;
        this.columnMap = new HashMap<>();
        if (childColumns != null) {
            for (int i = 0; i < childColumns.size(); i++) {
                columnMap.put(childColumns.get(i).toLowerCase(), i);
            }
        }
    }

    @Override
    public void open() throws MiniDbException {
        child.open();
        consumed = false;
        computeAggregates();
    }

    @Override
    public Object[] next() throws MiniDbException {
        if (!consumed) {
            consumed = true;
            return result;
        }
        return null;
    }

    @Override
    public void close() {
        child.close();
    }

    /** 消费子执行器全部行，计算每个聚合函数的结果。 */
    private void computeAggregates() throws MiniDbException {
        int aggCount = aggregates.size();
        long[] counts = new long[aggCount];
        double[] sums = new double[aggCount];
        Object[] mins = new Object[aggCount];
        Object[] maxs = new Object[aggCount];
        boolean[] hasValue = new boolean[aggCount];
        boolean[] allInt = new boolean[aggCount];  // 跟踪 SUM 是否全为 INT
        // 初始化：SUM 默认为全 INT（遇到非 INT 时置 false）
        for (int i = 0; i < aggCount; i++) {
            String func = aggregates.get(i).func().toLowerCase();
            if (func.equals("sum")) {
                allInt[i] = true;
            }
        }
        int rowCount = 0;

        Object[] row;
        while ((row = child.next()) != null) {
            rowCount++;
            for (int i = 0; i < aggCount; i++) {
                FuncCall fc = aggregates.get(i);
                String func = fc.func().toLowerCase();

                switch (func) {
                    case "count":
                        counts[i]++;
                        break;
                    case "sum":
                    case "avg": {
                        Object val = evaluateArg(fc.arg(), row);
                        if (val instanceof Number num) {
                            sums[i] += num.doubleValue();
                            if (func.equals("sum") && !(val instanceof Integer)) {
                                allInt[i] = false;
                            }
                        }
                        break;
                    }
                    case "min": {
                        Object val = evaluateArg(fc.arg(), row);
                        if (!hasValue[i]) {
                            mins[i] = val;
                            hasValue[i] = true;
                        } else {
                            if (compareValues(val, mins[i]) < 0) {
                                mins[i] = val;
                            }
                        }
                        break;
                    }
                    case "max": {
                        Object val = evaluateArg(fc.arg(), row);
                        if (!hasValue[i]) {
                            maxs[i] = val;
                            hasValue[i] = true;
                        } else {
                            if (compareValues(val, maxs[i]) > 0) {
                                maxs[i] = val;
                            }
                        }
                        break;
                    }
                }
            }
        }

        // 构建结果行
        result = new Object[aggCount];
        for (int i = 0; i < aggCount; i++) {
            FuncCall fc = aggregates.get(i);
            String func = fc.func().toLowerCase();

            switch (func) {
                case "count":
                    result[i] = (int) counts[i];
                    break;
                case "sum":
                    if (rowCount == 0) {
                        result[i] = 0;  // 空表语义
                    } else if (allInt[i]) {
                        result[i] = (int) sums[i];
                    } else {
                        result[i] = sums[i];
                    }
                    break;
                case "avg":
                    if (rowCount == 0) {
                        result[i] = 0;  // 空表语义
                    } else {
                        result[i] = sums[i] / rowCount;
                    }
                    break;
                case "min":
                    result[i] = hasValue[i] ? mins[i] : 0;  // 空表 → 0
                    break;
                case "max":
                    result[i] = hasValue[i] ? maxs[i] : 0;  // 空表 → 0
                    break;
            }
        }
    }

    /** 计算聚合函数参数的值。 */
    private Object evaluateArg(Expression arg, Object[] row) throws MiniDbException {
        if (arg == null) return null;  // COUNT(*)
        return ExpressionEvaluator.evaluate(arg, columnMap, row);
    }

    /** 统一数值比较：INT/DOUBLE 可互比，VARCHAR 按字典序。 */
    @SuppressWarnings("unchecked")
    private int compareValues(Object a, Object b) throws MiniDbException {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                "不可比较的类型: " + a.getClass().getSimpleName() + " vs " + b.getClass().getSimpleName());
    }
}
