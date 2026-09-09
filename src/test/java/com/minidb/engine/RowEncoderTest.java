package com.minidb.engine;

import com.minidb.catalog.ColumnDef;
import com.minidb.common.DataType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RowEncoder 单元测试。
 * 验收标准：encode -> decode 往返一致 + 编码长度断言。
 */
class RowEncoderTest {

    // 定义测试表: (id INT, name VARCHAR(50), score FLOAT)
    private static final List<ColumnDef> COLUMNS = List.of(
            new ColumnDef("id", DataType.INT, 0),
            new ColumnDef("name", DataType.VARCHAR, 50),
            new ColumnDef("score", DataType.FLOAT, 0)
    );

    // ============================================================
    // 往返一致性
    // ============================================================

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
        // 测试 null 位图: 第2列为null时，位图应为 0b00000010 = 2
        Object[] values = {1, null, 90.5};
        byte[] encoded = RowEncoder.encode(COLUMNS, values);
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
        List<ColumnDef> cols = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50)
        );
        Object[] values = {42, "测试混合行"};
        byte[] encoded = RowEncoder.encode(cols, values);
        Object[] decoded = RowEncoder.decode(cols, encoded);
        assertArrayEquals(values, decoded);
        assertTrue(decoded[0] instanceof Integer);
        assertTrue(decoded[1] instanceof String);
    }

    // ============================================================
    // 编码长度断言
    // ============================================================

    @Test
    void testEncodingLengthSingleInt() {
        // INT 行: 1B null位图 + 4B INT = 5B
        List<ColumnDef> cols = List.of(new ColumnDef("id", DataType.INT, 0));
        byte[] encoded = RowEncoder.encode(cols, new Object[]{42});
        assertEquals(5, encoded.length, "INT 行编码长度应为 1+4=5 字节");
    }

    @Test
    void testEncodingLengthSingleFloat() {
        // FLOAT 行: 1B null位图 + 8B FLOAT = 9B
        List<ColumnDef> cols = List.of(new ColumnDef("score", DataType.FLOAT, 0));
        byte[] encoded = RowEncoder.encode(cols, new Object[]{99.99});
        assertEquals(9, encoded.length, "FLOAT 行编码长度应为 1+8=9 字节");
    }

    @Test
    void testEncodingLengthSingleVarchar() {
        // VARCHAR(5) 行: 1B null位图 + 2B长度 + 5B UTF-8 = 8B
        List<ColumnDef> cols = List.of(new ColumnDef("name", DataType.VARCHAR, 50));
        byte[] encoded = RowEncoder.encode(cols, new Object[]{"Hello"});
        assertEquals(8, encoded.length, "VARCHAR(5) 行编码长度应为 1+2+5=8 字节");
    }

    @Test
    void testEncodingLengthMixed() {
        // INT + VARCHAR(3) + FLOAT: 1 + 4 + (2+3) + 8 = 18B
        List<ColumnDef> cols = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50),
                new ColumnDef("score", DataType.FLOAT, 0)
        );
        byte[] encoded = RowEncoder.encode(cols, new Object[]{1, "Tom", 90.5});
        assertEquals(18, encoded.length, "混合行编码长度应为 1+4+(2+3)+8=18 字节");
    }

    @Test
    void testEncodingLengthChineseVarchar() {
        // 中文 UTF-8 每字 3B: VARCHAR("你好") = 2B长度 + 6B内容 = 8B
        // 总计: 1B位图 + 8B = 9B
        List<ColumnDef> cols = List.of(new ColumnDef("name", DataType.VARCHAR, 50));
        byte[] encoded = RowEncoder.encode(cols, new Object[]{"你好"});
        assertEquals(9, encoded.length, "VARCHAR('你好') 编码长度应为 1+2+6=9 字节");
    }

    @Test
    void testEncodingLengthEmptyVarchar() {
        // VARCHAR("") 行: 1B null位图 + 2B长度 + 0B内容 = 3B
        List<ColumnDef> cols = List.of(new ColumnDef("name", DataType.VARCHAR, 50));
        byte[] encoded = RowEncoder.encode(cols, new Object[]{""});
        assertEquals(3, encoded.length, "VARCHAR('') 编码长度应为 1+2+0=3 字节");
    }

    @Test
    void testEncodingLengthWithNullColumn() {
        // INT(null) + VARCHAR("A") + FLOAT: 1 + (2+1) + 8 = 12B (INT null 不占定长区)
        List<ColumnDef> cols = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 50),
                new ColumnDef("score", DataType.FLOAT, 0)
        );
        byte[] encoded = RowEncoder.encode(cols, new Object[]{null, "A", 1.0});
        assertEquals(12, encoded.length, "含 null 的混合行编码长度应为 1+(2+1)+8=12 字节");
    }

    // ============================================================
    // 超过 8 列（null 位图 > 1 字节）
    // ============================================================

    @Test
    void testMoreThan8Columns() {
        // 10 列: null 位图 = (10+7)/8 = 2 字节
        List<ColumnDef> tenCols = List.of(
                new ColumnDef("c0", DataType.INT, 0),
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
        Object[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        byte[] encoded = RowEncoder.encode(tenCols, values);
        // 2B 位图 + 10*4B INT = 42B
        assertEquals(42, encoded.length);

        Object[] decoded = RowEncoder.decode(tenCols, encoded);
        assertArrayEquals(values, decoded);
    }

    @Test
    void testMoreThan8ColumnsWithNull() {
        // 10 列，第 9 列（位图第 2 字节第 0 位）为 null
        List<ColumnDef> tenCols = List.of(
                new ColumnDef("c0", DataType.INT, 0),
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
        Object[] values = {1, 2, 3, 4, 5, 6, 7, 8, null, 10};
        byte[] encoded = RowEncoder.encode(tenCols, values);

        // 验证位图第 2 字节: 第 8 列为 null → bit 0 = 1 → 0b00000001 = 1
        assertEquals(0, encoded[0]); // 前 8 列均非 null
        assertEquals(1, encoded[1]); // 第 9 列(index 8)为 null

        Object[] decoded = RowEncoder.decode(tenCols, encoded);
        assertArrayEquals(values, decoded);
    }
}
