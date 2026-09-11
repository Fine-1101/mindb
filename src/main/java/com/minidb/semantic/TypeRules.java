package com.minidb.semantic;

import com.minidb.ast.UnaryOp;
import com.minidb.common.DataType;

import java.util.Map;
import java.util.Optional;

/** 类型规则表（集中管理）：算术/比较/逻辑/一元。查不到返回 empty，由调用方带 AST 位置报 SEMANTIC 错误。 */
public final class TypeRules {

    private TypeRules() {}

    //二维表
    private static final Map<DataType, Map<DataType, DataType>> ARITHMETIC = Map.of(
        DataType.INT, Map.of(
            DataType.INT, DataType.INT,
            DataType.FLOAT, DataType.FLOAT),
        DataType.FLOAT, Map.of(
            DataType.INT, DataType.FLOAT,
            DataType.FLOAT, DataType.FLOAT));

    private static final Map<DataType, Map<DataType, DataType>> COMPARISON = Map.of(
        DataType.INT, Map.of(
            DataType.INT, DataType.BOOLEAN,
            DataType.FLOAT, DataType.BOOLEAN),
        DataType.FLOAT, Map.of(
            DataType.INT, DataType.BOOLEAN,
            DataType.FLOAT, DataType.BOOLEAN),
        DataType.VARCHAR, Map.of(
            DataType.VARCHAR, DataType.BOOLEAN));

    /** 算术运算（+ - * /）：数值组合按 INT+INT→INT、含 FLOAT→FLOAT 提升；含 NULL → 对方类型；含 VARCHAR/BOOLEAN → empty。 */
    public static Optional<DataType> arithmetic(DataType left, DataType right) {
        if (left == DataType.NULL) return Optional.of(right);
        if (right == DataType.NULL) return Optional.of(left);
        return Optional.ofNullable(ARITHMETIC.getOrDefault(left, Map.of()).get(right));
    }

    /** 比较运算（= != < <= > >=）：数值×数值 或 VARCHAR×VARCHAR → BOOLEAN；含 NULL → BOOLEAN；跨类 → empty。 */
    public static Optional<DataType> comparison(DataType left, DataType right) {
        if (left == DataType.NULL || right == DataType.NULL) {
            return Optional.of(DataType.BOOLEAN);
        }
        return Optional.ofNullable(COMPARISON.getOrDefault(left, Map.of()).get(right));
    }

    /** 逻辑运算（AND/OR）：(BOOLEAN, BOOLEAN) → BOOLEAN；其余 → empty。 */
    public static Optional<DataType> logical(DataType left, DataType right) {
        return left == DataType.BOOLEAN && right == DataType.BOOLEAN
                ? Optional.of(DataType.BOOLEAN) : Optional.empty();
    }

    /** 一元运算：NOT(BOOLEAN)→BOOLEAN；NEG(INT)→INT；NEG(FLOAT)→FLOAT；
     *  IS_NULL/IS_NOT_NULL 任意类型→BOOLEAN（D5 拍板 8，并行阶段接线）；其余 → empty。 */
    public static Optional<DataType> unary(UnaryOp op, DataType operand) {
        return switch (op) {
            case NOT -> operand == DataType.BOOLEAN
                    ? Optional.of(DataType.BOOLEAN) : Optional.empty();
            case NEG -> switch (operand) {
                case INT -> Optional.of(DataType.INT);
                case FLOAT -> Optional.of(DataType.FLOAT);
                default -> Optional.empty();
            };
            case IS_NULL, IS_NOT_NULL -> Optional.of(DataType.BOOLEAN);
        };
    }

    /**
     * 聚合函数结果类型（D4 拍板）：COUNT→INT（arg 任意或 *）；SUM 同参型数值；
     * AVG(INT/FLOAT)→FLOAT；MIN/MAX(T)→T（数值或 VARCHAR）。未知函数/不支持参数 → empty。
     *
     * <p>COUNT 的 arg 可为 null（COUNT(*)），本方法不读 arg 值。
     */
    public static Optional<DataType> aggregate(String func, DataType arg) {
        return switch (func) {
            case "COUNT" -> Optional.of(DataType.INT);
            case "SUM" -> switch (arg) {
                case INT -> Optional.of(DataType.INT);
                case FLOAT -> Optional.of(DataType.FLOAT);
                default -> Optional.empty();
            };
            case "AVG" -> arg == DataType.INT || arg == DataType.FLOAT
                    ? Optional.of(DataType.FLOAT) : Optional.empty();
            case "MIN", "MAX" -> arg == DataType.INT || arg == DataType.FLOAT || arg == DataType.VARCHAR
                    ? Optional.of(arg) : Optional.empty();
            default -> Optional.empty();
        };
    }
}
