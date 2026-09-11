package com.minidb.engine;

import com.minidb.ast.FuncCall;
import com.minidb.common.MiniDbException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标量聚合执行器（无 GROUP BY）：open 拉取子行流完成累计，next 输出单行后结束。
 *
 * <p>输出值序 = aggregates 书写序；arg 为表达式时逐行求值（复用 ExpressionEvaluator）。
 * 空表语义拍板（D4，无 NULL 支持）：COUNT/SUM/AVG/MIN/MAX → 0。
 * SUM(INT)→Integer、SUM(FLOAT)/AVG→Double、COUNT→Integer、MIN/MAX 保持列值原类型。
 */
public class AggregateExecutor implements Executor {

    private final Executor child;
    private final List<FuncCall> aggregates;
    private final Map<String, Integer> columnMap;

    /** open 累计出的单行结果；null = 尚未 open 或已输出完毕。 */
    private Object[] result;

    /**
     * @param child       子执行器（SeqScan 或 Filter）
     * @param aggregates  聚合函数列表（与输出列序一致）
     * @param childColumns 子执行器输出的列名列表（arg 中列引用求值用）
     */
    public AggregateExecutor(Executor child, List<FuncCall> aggregates, List<String> childColumns) {
        this.child = child;
        this.aggregates = aggregates;
        this.columnMap = new HashMap<>();
        for (int i = 0; i < childColumns.size(); i++) {
            columnMap.put(childColumns.get(i).toLowerCase(), i);
        }
    }

    @Override
    public void open() throws MiniDbException {
        child.open();
        long[] counts = new long[aggregates.size()];
        Object[] sums = new Object[aggregates.size()];
        Object[] bests = new Object[aggregates.size()];

        Object[] row;
        while ((row = child.next()) != null) {
            for (int i = 0; i < aggregates.size(); i++) {
                FuncCall f = aggregates.get(i);
                if ("COUNT".equals(f.func())) {
                    counts[i]++;  // 无 NULL 支持：COUNT(col) 与 COUNT(*) 同为行数
                    continue;
                }
                Object v = ExpressionEvaluator.evaluate(f.arg(), columnMap, row);
                switch (f.func()) {
                    case "SUM" -> sums[i] = add(sums[i], v);
                    case "AVG" -> {
                        sums[i] = add(sums[i], v);
                        counts[i]++;
                    }
                    case "MIN" -> bests[i] = bests[i] == null || compare(v, bests[i]) < 0 ? v : bests[i];
                    case "MAX" -> bests[i] = bests[i] == null || compare(v, bests[i]) > 0 ? v : bests[i];
                    default -> throw new MiniDbException(MiniDbException.Phase.PLAN, f.pos(),
                            "未知聚合函数: " + f.func());
                }
            }
        }
        child.close();

        Object[] out = new Object[aggregates.size()];
        for (int i = 0; i < aggregates.size(); i++) {
            out[i] = switch (aggregates.get(i).func()) {
                case "COUNT" -> (int) counts[i];
                case "SUM" -> sums[i] == null ? 0 : sums[i];
                case "AVG" -> counts[i] == 0 ? 0 : ((Number) sums[i]).doubleValue() / counts[i];
                default -> bests[i] == null ? 0 : bests[i];  // MIN / MAX
            };
        }
        this.result = out;
    }

    @Override
    public Object[] next() {
        Object[] out = result;
        result = null;  // 单行输出，之后置空表示结束
        return out;
    }

    @Override
    public void close() {
        // 子执行器已在 open 中消费完毕并 close
    }

    /** 数值累加：Integer+Integer→Integer（SUM(INT) 保整型），其余按 double。 */
    private static Object add(Object acc, Object v) {
        if (acc == null) {
            return v;
        }
        if (acc instanceof Integer i && v instanceof Integer j) {
            return i + j;
        }
        return ((Number) acc).doubleValue() + ((Number) v).doubleValue();
    }

    /** 数值按 double 比较；VARCHAR 字典序（与 ExpressionEvaluator.compareValues 同规则）。 */
    private static int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        return ((String) a).compareTo((String) b);
    }
}
