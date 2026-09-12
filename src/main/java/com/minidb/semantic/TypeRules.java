package com.minidb.semantic;

import com.minidb.ast.UnaryOp;
import com.minidb.common.DataType;

import java.util.Map;
import java.util.Optional;

/** 类型规则表（集中管理）：算术/比较/逻辑/一元/聚合。查不到返回 empty，由调用方带 AST 位置报 SEMANTIC 错误。 */
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

    /** 算术运算（+ - * /）：数值组合按 INT+INT→INT、含 FLOAT→FLOAT 提升；VARCHAR/BOOLEAN → empty。
     *  NULL 传播（D5 拍板 5）：T op NULL / NULL op T → T（T 须为数值，VARCHAR 参与算术仍拒绝）、NULL op NULL → NULL。 */
    public static Optional<DataType> arithmetic(DataType left, DataType right) {
        if (left == DataType.NULL && right == DataType.NULL) {
            return Optional.of(DataType.NULL);
        }
        if (right == DataType.NULL) {
            return arithmetic(left, left);    // T op NULL → T
        }
        if (left == DataType.NULL) {
            return arithmetic(right, right); // NULL op T → T
        }
        return Optional.ofNullable(ARITHMETIC.getOrDefault(left, Map.of()).get(right));
    }

    /** 比较运算（= != < <= > >=）：数值×数值 或 VARCHAR×VARCHAR → BOOLEAN；跨类 → empty。
     *  含 NULL（D5 拍板 5）：可比类型与 NULL 比较 → BOOLEAN（静态类型，运行时求值为 NULL/未知，
     *  由求值器三值逻辑处理）；BOOLEAN 不可比较。 */
    public static Optional<DataType> comparison(DataType left, DataType right) {
        if (left == DataType.NULL && right == DataType.NULL) {
            return Optional.of(DataType.BOOLEAN);
        }
        if (right == DataType.NULL) {
            return comparison(left, left);    // T = NULL → BOOLEAN（T 可比时）
        }
        if (left == DataType.NULL) {
            return comparison(right, right);  // NULL = T → BOOLEAN
        }
        return Optional.ofNullable(COMPARISON.getOrDefault(left, Map.of()).get(right));
    }

    /** 逻辑运算（AND/OR）：(BOOLEAN, BOOLEAN) → BOOLEAN；其余 → empty。
     *  含 NULL（D5 拍板 5）：NULL 视为未知布尔参与逻辑运算 → BOOLEAN
     *  （运行时 NULL AND FALSE→FALSE、NULL AND TRUE→NULL 等，由求值器处理）。 */
    public static Optional<DataType> logical(DataType left, DataType right) {
        boolean lb = left == DataType.BOOLEAN || left == DataType.NULL;
        boolean rb = right == DataType.BOOLEAN || right == DataType.NULL;
        return lb && rb ? Optional.of(DataType.BOOLEAN) : Optional.empty();
    }

    /** 一元运算：NOT(BOOLEAN|NULL)→BOOLEAN（NULL 为未知布尔）；NEG(INT)→INT；NEG(FLOAT)→FLOAT；
     *  IS_NULL/IS_NOT_NULL 任意类型→BOOLEAN（D5 拍板 8）；其余 → empty。 */
    public static Optional<DataType> unary(UnaryOp op, DataType operand) {
        return switch (op) {
            case NOT -> operand == DataType.BOOLEAN || operand == DataType.NULL
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
     * <p>NULL 参数（D5 拍板 6）：COUNT(NULL)→INT（运行时数为 0）；SUM/AVG/MIN/MAX(NULL)→NULL
     * （全 NULL 输入无值可聚合）。运行时空值语义归执行器：COUNT(col) 数非 NULL 值；
     * SUM/AVG/MIN/MAX 忽略 NULL 输入，无任何非 NULL 输入→NULL；COUNT 永不 NULL（空表→0）。
     *
     * <p>COUNT 的 arg 可为 null（COUNT(*)），本方法不读 arg 值。
     */
    public static Optional<DataType> aggregate(String func, DataType arg) {
        return switch (func) {
            case "COUNT" -> Optional.of(DataType.INT);
            case "SUM" -> switch (arg) {
                case INT -> Optional.of(DataType.INT);
                case FLOAT -> Optional.of(DataType.FLOAT);
                case NULL -> Optional.of(DataType.NULL);
                default -> Optional.empty();
            };
            case "AVG" -> arg == DataType.INT || arg == DataType.FLOAT
                    ? Optional.of(DataType.FLOAT)
                    : arg == DataType.NULL ? Optional.of(DataType.NULL) : Optional.empty();
            case "MIN", "MAX" -> arg == DataType.INT || arg == DataType.FLOAT || arg == DataType.VARCHAR
                    ? Optional.of(arg)
                    : arg == DataType.NULL ? Optional.of(DataType.NULL) : Optional.empty();
            default -> Optional.empty();
        };
    }
}
