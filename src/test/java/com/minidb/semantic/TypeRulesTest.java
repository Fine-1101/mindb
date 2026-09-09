package com.minidb.semantic;

import com.minidb.ast.UnaryOp;
import com.minidb.common.DataType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.minidb.common.DataType.BOOLEAN;
import static com.minidb.common.DataType.FLOAT;
import static com.minidb.common.DataType.INT;
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
}
