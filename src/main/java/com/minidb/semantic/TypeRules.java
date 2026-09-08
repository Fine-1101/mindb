package com.minidb.semantic;

import com.minidb.common.DataType;

import java.util.Map;
import java.util.Optional;

/** 算术类型规则表（D1 骨架，D3 补全比较/逻辑运算规则）。 */
public final class TypeRules {

    private TypeRules() {}

    private static final Map<DataType, Map<DataType, DataType>> ARITHMETIC = Map.of(
        DataType.INT, Map.of(
            DataType.INT, DataType.INT,
            DataType.FLOAT, DataType.FLOAT),
        DataType.FLOAT, Map.of(
            DataType.INT, DataType.FLOAT,
            DataType.FLOAT, DataType.FLOAT));

    /** 算术运算（+ - * /）的结果类型；含 VARCHAR 等不合法组合返回 Optional.empty()。 */
    public static Optional<DataType> arithmetic(DataType left, DataType right) {
        return Optional.ofNullable(ARITHMETIC.getOrDefault(left, Map.of()).get(right));
    }
}
