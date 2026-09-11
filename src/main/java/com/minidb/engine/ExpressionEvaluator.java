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
                String key;
                if (ref.table() != null) {
                    key = ref.table().toLowerCase() + "." + ref.column().toLowerCase();
                } else {
                    key = ref.column().toLowerCase();
                }
                Integer idx = columns.get(key);
                if (idx == null) {
                    // 回退：限定名找不到时尝试非限定名
                    if (ref.table() != null) {
                        idx = columns.get(ref.column().toLowerCase());
                    }
                }
                if (idx == null) {
                    throw new MiniDbException(MiniDbException.Phase.PLAN, ref.pos(),
                            "列不存在: " + (ref.table() != null ? ref.table() + "." : "") + ref.column());
                }
                yield row[idx];
            }
            case BinaryExpr b -> evaluateBinary(b, columns, row);
            case UnaryExpr u -> evaluateUnary(u, columns, row);
            // 聚合已由 Semantic 拒绝进 WHERE/行级求值；执行端到达 = 内部状态异常
            case FuncCall f -> throw new MiniDbException(MiniDbException.Phase.PLAN, f.pos(),
                    "聚合函数不允许出现在行级求值中: " + f.display());
        };
    }

    // ------------------------------------------------------------------
    // 二元表达式（含 AND/OR 短路）
    // ------------------------------------------------------------------

    private static Object evaluateBinary(BinaryExpr b, Map<String, Integer> columns, Object[] row)
            throws MiniDbException {
        // 三值逻辑短路求值：AND / OR（拍板 5）
        if (b.op() == BinaryOp.AND) {
            Object left = evaluate(b.left(), columns, row);
            if (Boolean.FALSE.equals(left)) return false;  // F 短路
            Object right = evaluate(b.right(), columns, row);
            if (Boolean.FALSE.equals(right)) return false;  // F 短路
            if (left == null || right == null) return null;  // NULL + T = NULL
            return toBoolean(left) && toBoolean(right);
        }
        if (b.op() == BinaryOp.OR) {
            Object left = evaluate(b.left(), columns, row);
            if (Boolean.TRUE.equals(left)) return true;  // T 短路
            Object right = evaluate(b.right(), columns, row);
            if (Boolean.TRUE.equals(right)) return true;  // T 短路
            if (left == null || right == null) return null;  // NULL + F = NULL
            return toBoolean(left) || toBoolean(right);
        }

        // 比较运算（NULL 参与 → 返回 null，三值逻辑）
        Object left = evaluate(b.left(), columns, row);
        Object right = evaluate(b.right(), columns, row);
        if (left == null || right == null) {
            return null;  // NULL 参与任何比较 → null
        }
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
            case NOT -> operand == null ? null : !toBoolean(operand);
            case NEG -> switch (operand) {
                case null -> null;
                case Integer i -> -i;
                case Double d -> -d;
                default -> throw new MiniDbException(MiniDbException.Phase.PLAN, u.pos(),
                        "NEG 不支持类型: " + operand.getClass().getSimpleName());
            };
            // IS [NOT] NULL（拍板 8）
            case IS_NULL -> operand == null;
            case IS_NOT_NULL -> operand != null;
        };
    }

    // ------------------------------------------------------------------
    // 比较与算术辅助
    // ------------------------------------------------------------------

    /** 统一数值比较：INT/DOUBLE 可互比，VARCHAR 按字典序。null 参与比较抛异常（由调用方捕获返回 null）。 */
    private static int compareValues(Object left, Object right) throws MiniDbException {
        if (left == null || right == null) {
            // NULL 参与比较 → 三值逻辑返回 null（调用方处理）
            throw new NullCompareException();
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
            return null;  // NULL 参与算术 → NULL
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
        if (value == null) return false;  // NULL 当 false 处理（WHERE 过滤）
        if (value instanceof Boolean b) return b;
        throw new ClassCastException("期望 BOOLEAN，实际: " + value.getClass().getSimpleName());
    }

    /** NULL 参与比较时抛出的内部异常，用于流程控制。 */
    static class NullCompareException extends MiniDbException {
        NullCompareException() {
            super(Phase.PLAN, null, "NULL_COMPARE");
        }
    }
}
