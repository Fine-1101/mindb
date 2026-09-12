package com.minidb.semantic;

import com.minidb.ast.UnaryOp;
import com.minidb.common.DataType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.minidb.common.DataType.BOOLEAN;
import static com.minidb.common.DataType.FLOAT;
import static com.minidb.common.DataType.INT;
import static com.minidb.common.DataType.NULL;
import static com.minidb.common.DataType.VARCHAR;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeRulesTest {

    private static final DataType[] ALL = {INT, FLOAT, VARCHAR, BOOLEAN};

    private static boolean numeric(DataType t) {
        return t == INT || t == FLOAT;
    }

    @Test
    void arithmeticAllCombinations() {
        for (DataType l : ALL) {
            for (DataType r : ALL) {
                Optional<DataType> got = TypeRules.arithmetic(l, r);
                if (l == INT && r == INT) {
                    assertEquals(Optional.of(INT), got, l + " op " + r);
                } else if (numeric(l) && numeric(r)) {
                    assertEquals(Optional.of(FLOAT), got, l + " op " + r);
                } else {
                    assertEquals(Optional.empty(), got, l + " op " + r);
                }
            }
        }
    }

    @Test
    void comparisonAllCombinations() {
        for (DataType l : ALL) {
            for (DataType r : ALL) {
                Optional<DataType> got = TypeRules.comparison(l, r);
                boolean legal = (numeric(l) && numeric(r)) || (l == VARCHAR && r == VARCHAR);
                assertEquals(legal ? Optional.of(BOOLEAN) : Optional.empty(), got, l + " vs " + r);
            }
        }
    }

    @Test
    void logicalAllCombinations() {
        for (DataType l : ALL) {
            for (DataType r : ALL) {
                Optional<DataType> got = TypeRules.logical(l, r);
                assertEquals(l == BOOLEAN && r == BOOLEAN ? Optional.of(BOOLEAN) : Optional.empty(),
                        got, l + " AND " + r);
            }
        }
    }

    @Test
    void unaryAllCombinations() {
        assertEquals(Optional.of(BOOLEAN), TypeRules.unary(UnaryOp.NOT, BOOLEAN));
        assertEquals(Optional.of(INT), TypeRules.unary(UnaryOp.NEG, INT));
        assertEquals(Optional.of(FLOAT), TypeRules.unary(UnaryOp.NEG, FLOAT));
        for (DataType t : ALL) {
            if (t != BOOLEAN) {
                assertEquals(Optional.empty(), TypeRules.unary(UnaryOp.NOT, t), "NOT " + t);
            }
            if (!numeric(t)) {
                assertEquals(Optional.empty(), TypeRules.unary(UnaryOp.NEG, t), "NEG " + t);
            }
        }
    }

    // ==================================================================
    // D5 拍板 5：NULL 三值逻辑类型传播（T op NULL → T / BOOLEAN）
    // ==================================================================

    @Test
    void arithmeticWithNullPropagatesOperandType() {
        // T op NULL / NULL op T → T（T 须为数值）
        assertEquals(Optional.of(INT), TypeRules.arithmetic(INT, NULL));
        assertEquals(Optional.of(INT), TypeRules.arithmetic(NULL, INT));
        assertEquals(Optional.of(FLOAT), TypeRules.arithmetic(FLOAT, NULL));
        assertEquals(Optional.of(FLOAT), TypeRules.arithmetic(NULL, FLOAT));
        assertEquals(Optional.of(NULL), TypeRules.arithmetic(NULL, NULL));
        // VARCHAR 参与算术仍拒绝（NULL 传播不放宽数值约束）
        assertEquals(Optional.empty(), TypeRules.arithmetic(VARCHAR, NULL));
        assertEquals(Optional.empty(), TypeRules.arithmetic(NULL, VARCHAR));
    }

    @Test
    void comparisonWithNullYieldsBoolean() {
        // 可比类型与 NULL 比较 → BOOLEAN（静态类型，运行时三值逻辑归求值器）
        assertEquals(Optional.of(BOOLEAN), TypeRules.comparison(INT, NULL));
        assertEquals(Optional.of(BOOLEAN), TypeRules.comparison(NULL, INT));
        assertEquals(Optional.of(BOOLEAN), TypeRules.comparison(FLOAT, NULL));
        assertEquals(Optional.of(BOOLEAN), TypeRules.comparison(VARCHAR, NULL));
        assertEquals(Optional.of(BOOLEAN), TypeRules.comparison(NULL, NULL));
        // BOOLEAN 本就不可比较，遇 NULL 同样拒绝
        assertEquals(Optional.empty(), TypeRules.comparison(BOOLEAN, NULL));
        assertEquals(Optional.empty(), TypeRules.comparison(NULL, BOOLEAN));
    }

    @Test
    void logicalWithNullYieldsBoolean() {
        // NULL 视为未知布尔参与逻辑运算 → BOOLEAN
        assertEquals(Optional.of(BOOLEAN), TypeRules.logical(NULL, BOOLEAN));
        assertEquals(Optional.of(BOOLEAN), TypeRules.logical(BOOLEAN, NULL));
        assertEquals(Optional.of(BOOLEAN), TypeRules.logical(NULL, NULL));
        // 非布尔仍拒绝
        assertEquals(Optional.empty(), TypeRules.logical(NULL, INT));
        assertEquals(Optional.empty(), TypeRules.logical(INT, NULL));
    }

    @Test
    void unaryNullAndIsNullPredicates() {
        // NOT NULL → BOOLEAN（未知布尔的否定仍是未知布尔）
        assertEquals(Optional.of(BOOLEAN), TypeRules.unary(UnaryOp.NOT, NULL));
        // NEG NULL → empty（无类型可传播）
        assertEquals(Optional.empty(), TypeRules.unary(UnaryOp.NEG, NULL));
        // IS [NOT] NULL 谓词：任意操作数类型 → BOOLEAN（D5 拍板 8）
        for (DataType t : new DataType[]{INT, FLOAT, VARCHAR, BOOLEAN, NULL}) {
            assertEquals(Optional.of(BOOLEAN), TypeRules.unary(UnaryOp.IS_NULL, t), "IS_NULL " + t);
            assertEquals(Optional.of(BOOLEAN), TypeRules.unary(UnaryOp.IS_NOT_NULL, t),
                    "IS_NOT_NULL " + t);
        }
    }

    // ==================================================================
    // D5 拍板 6：聚合空值规则表（COUNT 永不 NULL；SUM/AVG/MIN/MAX(NULL)→NULL）
    // ==================================================================

    @Test
    void aggregateNullArgRules() {
        assertEquals(Optional.of(INT), TypeRules.aggregate("COUNT", NULL));
        assertEquals(Optional.of(NULL), TypeRules.aggregate("SUM", NULL));
        assertEquals(Optional.of(NULL), TypeRules.aggregate("AVG", NULL));
        assertEquals(Optional.of(NULL), TypeRules.aggregate("MIN", NULL));
        assertEquals(Optional.of(NULL), TypeRules.aggregate("MAX", NULL));
    }

    @Test
    void aggregateUnsupportedArgs() {
        // SUM/AVG 仅数值；MIN/MAX 数值或 VARCHAR；COUNT 任意——全部对 BOOLEAN 拒绝（除 COUNT）
        assertEquals(Optional.empty(), TypeRules.aggregate("SUM", VARCHAR));
        assertEquals(Optional.empty(), TypeRules.aggregate("AVG", VARCHAR));
        assertEquals(Optional.empty(), TypeRules.aggregate("SUM", BOOLEAN));
        assertEquals(Optional.empty(), TypeRules.aggregate("MIN", BOOLEAN));
        assertEquals(Optional.empty(), TypeRules.aggregate("FOO", INT));
        assertEquals(Optional.of(INT), TypeRules.aggregate("COUNT", BOOLEAN));
    }
}
