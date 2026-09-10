package com.minidb.engine;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.FuncCall;
import com.minidb.ast.Literal;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.common.MiniDbException;

import java.util.List;
import java.util.Map;

/**
 * 表达式求值器（火山模型 WHERE 求值共用）。
 *
 * <p>输入：AST 表达式 + 列名→值映射（由 SeqScan 解码后构建）。
 * 输出：Java 对象（Integer / Double / String / Boolean）。
 *
 * <p>短路求值：AND 左假右不判，OR 左真右不判。
 * 类型已由 Semantic 保证，此处不做类型检查。
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {}

    /**
     * 对表达式求值。
     *
     * @param expr    AST 表达式节点
     * @param columns 列名（小写）→ 在 row 中的索引
     * @param row     当前行的值数组（索引与 columns 映射对应）
     * @return 求值结果：Integer / Double / String / Boolean
     */
    public static Object evaluate(Expression expr, Map<String, Integer> columns, Object[] row)
            throws MiniDbException {
        return switch (expr) {
            case Literal lit -> lit.value();
            case ColumnRef ref -> {
                String key = ref.column().toLowerCase();
                Integer idx = columns.get(key);
                if (idx == null) {
                    throw new MiniDbException(MiniDbException.Phase.PLAN, ref.pos(),
                            "列不存在: " + ref.column());
                }
                yield row[idx];
            }
            case BinaryExpr b -> evaluateBinary(b, columns, row);
            case UnaryExpr u -> evaluateUnary(u, columns, row);
            // D4-A 编译契约适配：FuncCall 已进入 Expression permits。
            // 聚合执行（AggregateExecutor）属于 D 的 D4 任务，这里只给最小占位分支。
            case FuncCall f -> throw new MiniDbException(MiniDbException.Phase.PLAN, f.pos(),
                    "聚合函数执行尚未支持: " + f.func());
        };
    }

    // ------------------------------------------------------------------
    // 二元表达式（含 AND/OR 短路）
    // ------------------------------------------------------------------

    private static Object evaluateBinary(BinaryExpr b, Map<String, Integer> columns, Object[] row)
            throws MiniDbException {
        // 短路求值：AND / OR
        if (b.op() == BinaryOp.AND) {
            Object left = evaluate(b.left(), columns, row);
            if (Boolean.FALSE.equals(left)) return false;  // 左假右不判
            Object right = evaluate(b.right(), columns, row);
            return toBoolean(left) && toBoolean(right);
        }
        if (b.op() == BinaryOp.OR) {
            Object left = evaluate(b.left(), columns, row);
            if (Boolean.TRUE.equals(left)) return true;  // 左真右不判
            Object right = evaluate(b.right(), columns, row);
            return toBoolean(left) || toBoolean(right);
        }

        // 比较运算
        Object left = evaluate(b.left(), columns, row);
        Object right = evaluate(b.right(), columns, row);
        return switch (b.op()) {
            case EQ -> compareValues(left, right) == 0;
            case NE -> compareValues(left, right) != 0;
            case LT -> compareValues(left, right) < 0;
            case LE -> compareValues(left, right) <= 0;
            case GT -> compareValues(left, right) > 0;
            case GE -> compareValues(left, right) >= 0;
            // 算术运算
            case ADD -> arithmetic(left, right, '+');
            case SUB -> arithmetic(left, right, '-');
            case MUL -> arithmetic(left, right, '*');
            case DIV -> arithmetic(left, right, '/');
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, b.pos(),
                    "不支持的二元运算符: " + b.op());
        };
    }

    // ------------------------------------------------------------------
    // 一元表达式
    // ------------------------------------------------------------------

    private static Object evaluateUnary(UnaryExpr u, Map<String, Integer> columns, Object[] row)
            throws MiniDbException {
        Object operand = evaluate(u.operand(), columns, row);
        return switch (u.op()) {
            case NOT -> !toBoolean(operand);
            case NEG -> switch (operand) {
                case Integer i -> -i;
                case Double d -> -d;
                default -> throw new MiniDbException(MiniDbException.Phase.PLAN, u.pos(),
                        "NEG 不支持类型: " + operand.getClass().getSimpleName());
            };
        };
    }

    // ------------------------------------------------------------------
    // 比较与算术辅助
    // ------------------------------------------------------------------

    /** 统一数值比较：INT/DOUBLE 可互比，VARCHAR 按字典序。null 参与比较返回 null（SQL 三值逻辑暂不支持）。 */
    private static int compareValues(Object left, Object right) throws MiniDbException {
        if (left == null || right == null) {
            throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "NULL 参与比较");
        }
        // 数值统一为 double 比较
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        // VARCHAR
        if (left instanceof String ls && right instanceof String rs) {
            return ls.compareTo(rs);
        }
        throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                "不可比较的类型: " + left.getClass().getSimpleName()
                        + " vs " + right.getClass().getSimpleName());
    }

    private static Object arithmetic(Object left, Object right, char op) throws MiniDbException {
        if (left == null || right == null) {
            throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                    "NULL 参与算术运算");
        }
        // INT op INT → INT
        if (left instanceof Integer li && right instanceof Integer ri) {
            if (op == '/' && ri == 0) {
                throw new MiniDbException(MiniDbException.Phase.PLAN, null, "除零错误");
            }
            int result = switch (op) {
                case '+' -> li + ri;
                case '-' -> li - ri;
                case '*' -> li * ri;
                case '/' -> li / ri;
                default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null, "未知算术运算符: " + op);
            };
            return result;
        }
        // 含 FLOAT → double 运算
        double ld = ((Number) left).doubleValue();
        double rd = ((Number) right).doubleValue();
        if (op == '/' && rd == 0.0) {
            throw new MiniDbException(MiniDbException.Phase.PLAN, null, "除零错误");
        }
        return switch (op) {
            case '+' -> ld + rd;
            case '-' -> ld - rd;
            case '*' -> ld * rd;
            case '/' -> ld / rd;
            default -> throw new MiniDbException(MiniDbException.Phase.PLAN, null, "未知算术运算符: " + op);
        };
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        throw new ClassCastException("期望 BOOLEAN，实际: "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }
}
