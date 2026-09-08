package com.minidb.semantic;

import com.minidb.common.DataType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeRulesTest {

    @Test
    void intPlusIntIsInt() {
        assertEquals(Optional.of(DataType.INT), TypeRules.arithmetic(DataType.INT, DataType.INT));
    }

    @Test
    void intPlusFloatIsFloat() {
        assertEquals(Optional.of(DataType.FLOAT), TypeRules.arithmetic(DataType.INT, DataType.FLOAT));
    }

    @Test
    void intPlusVarcharIsIllegal() {
        assertTrue(TypeRules.arithmetic(DataType.INT, DataType.VARCHAR).isEmpty());
    }
}
