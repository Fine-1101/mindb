package com.minidb.engine;

import com.minidb.ast.Expression;
import com.minidb.catalog.ColumnDef;
import com.minidb.common.MiniDbException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 过滤执行器：WHERE 求值器，对子执行器每行求值 condition，true 则透传。
 * 支持 Literal/ColumnRef/BinaryExpr/UnaryExpr，AND/OR 短路求值。
 */
public class FilterExecutor implements Executor {

    private final Executor child;
    private final Expression condition;
    private final Map<String, Integer> columnMap;  // 列名(小写) → 索引

    /**
     * @param child       子执行器
     * @param condition   WHERE 条件 AST 表达式
     * @param columnNames 子执行器输出的列名列表（与 Object[] 索引对应）
     */
    public FilterExecutor(Executor child, Expression condition, List<String> columnNames) {
        this.child = child;
        this.condition = condition;
        this.columnMap = new HashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            columnMap.put(columnNames.get(i).toLowerCase(), i);
        }
    }

    @Override
    public void open() throws MiniDbException {
        child.open();
    }

    @Override
    public Object[] next() throws MiniDbException {
        while (true) {
            Object[] row = child.next();
            if (row == null) return null;
            Object result = ExpressionEvaluator.evaluate(condition, columnMap, row);
            if (Boolean.TRUE.equals(result)) {
                return row;
            }
        }
    }

    @Override
    public void close() {
        child.close();
    }
}
