package com.minidb.engine;

import com.minidb.catalog.ColumnDef;
import com.minidb.common.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RowEncoder 单元测试。
 * 验收标准：encode -> decode 往返一致。
 */
class RowEncoderTest {

    // 定义测试表: (id INT, name VARCHAR(50), score FLOAT)
    private static final List<ColumnDef> COLUMNS = List.of(
            new ColumnDef("id", DataType.INT, 0),
            new ColumnDef("name", DataType.VARCHAR, 50),
            new ColumnDef("score", DataType.FLOAT, 0)
    );

    @Test
    void testEncodeDecodeAllNonNull() {
        Object[] values = {1, "Tom", 90.5};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
        Object[] decoded = RowEncoder.decode(COLUMNS, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testEncodeDecodeWithNull() {
        Object[] values = {2, null, 85.0};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
        Object[] decoded = RowEncoder.decode(COLUMNS, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testEncodeDecodeAllNull() {
        Object[] values = {null, null, null};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
        Object[] decoded = RowEncoder.decode(COLUMNS, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testEncodeDecodeChineseString() {
        Object[] values = {3, "你好世界", 100.0};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
        Object[] decoded = RowEncoder.decode(COLUMNS, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testEncodeDecodeEmptyString() {
        Object[] values = {4, "", 0.0};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
        Object[] decoded = RowEncoder.decode(COLUMNS, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testEncodeDecodeSpecialChars() {
        Object[] values = {5, "Tom's book", -99.99};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
        Object[] decoded = RowEncoder.decode(COLUMNS, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testEncodeDecodeBoundaryValues() {
        Object[] values = {Integer.MAX_VALUE, "边界测试", Double.MIN_VALUE};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
        Object[] decoded = RowEncoder.decode(COLUMNS, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testNullBitmapCorrectness() {
        // 测试null位图: 第2列为null时，位图应为 0b00000010 = 2
        Object[] values = {1, null, 90.5};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);

        // 第一个字节是null位图
        assertEquals(0b00000010, encoded[0]);
    }

    @Test
    void testColumnValueMismatch() {
        Object[] values = {1, "Tom"}; // 只有2个值，但有3列
        assertThrows(IllegalArgumentException.class, () -> RowEncoder.encode(COLUMNS, values));
    }

    @Test
    void testSingleIntColumn() {
        List<ColumnDef> singleCol = List.of(new ColumnDef("id", DataType.INT, 0));
        Object[] values = {42};
        byte[] encoded = RowEncoder.encode(singleCol, values);
        Object[] decoded = RowEncoder.decode(singleCol, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testSingleVarcharColumn() {
        List<ColumnDef> singleCol = List.of(new ColumnDef("name", DataType.VARCHAR, 100));
        Object[] values = {"测试字符串"};
        byte[] encoded = RowEncoder.encode(singleCol, values);
        Object[] decoded = RowEncoder.decode(singleCol, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testSingleFloatColumn() {
        List<ColumnDef> singleCol = List.of(new ColumnDef("score", DataType.FLOAT, 0));
        Object[] values = {99.99};
        byte[] encoded = RowEncoder.encode(singleCol, values);
        Object[] decoded = RowEncoder.decode(singleCol, encoded);

        assertArrayEquals(values, decoded);
    }

    @Test
    void testIntVarcharMixedRow() {
        // INT + VARCHAR 混合行（不含 FLOAT）
        List<ColumnDef> cols = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50)
        );
        Object[] values = {42, "测试混合行"};
        byte[] encoded = RowEncoder.encode(cols, values);
        Object[] decoded = RowEncoder.decode(cols, encoded);

        assertArrayEquals(values, decoded);
        // 验证类型一致
        assertTrue(decoded[0] instanceof Integer);
        assertTrue(decoded[1] instanceof String);
    }

    @Test
    void testMoreThanEightColumns() {
        // 超过8列：null位图按 (n+7)/8 字节扩展，不再有列数上限
        List<ColumnDef> cols = List.of(
                new ColumnDef("c1", DataType.INT, 0),
                new ColumnDef("c2", DataType.INT, 0),
                new ColumnDef("c3", DataType.INT, 0),
                new ColumnDef("c4", DataType.INT, 0),
                new ColumnDef("c5", DataType.INT, 0),
                new ColumnDef("c6", DataType.INT, 0),
                new ColumnDef("c7", DataType.INT, 0),
                new ColumnDef("c8", DataType.INT, 0),
                new ColumnDef("c9", DataType.INT, 0)
        );
        Object[] values = {1, 2, 3, 4, 5, 6, 7, null, 9};
        byte[] encoded = RowEncoder.encode(cols, values);
        Object[] decoded = RowEncoder.decode(cols, encoded);

        assertArrayEquals(values, decoded);
        // 9列 => 位图2字节 + 8个非null INT（第8列为null跳过）
        assertEquals(2 + 4 * 8, encoded.length);
    }
}
