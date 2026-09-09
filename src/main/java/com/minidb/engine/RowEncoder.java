package com.minidb.engine;

import com.minidb.catalog.ColumnDef;
import com.minidb.common.DataType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 行编解码器。
 * 格式: [null位图 (n+7)/8 B][INT 4B][FLOAT 8B][VARCHAR 2B长度+内容]
 * null位图: 每个bit对应一列，1表示null，0表示非null，按列数取字节数。
 */
public final class RowEncoder {

    private RowEncoder() {}

    /**
     * 编码一行数据。
     * @param columns 列定义列表
     * @param values  值数组，null表示该列为NULL
     * @return 编码后的字节数组
     */
    public static byte[] encode(List<ColumnDef> columns, Object[] values) {
        if (columns.size() != values.length) {
            throw new IllegalArgumentException("列数与值数不匹配");
        }

        int bitmapBytes = (columns.size() + 7) / 8;

        // 先计算所需空间
        int size = bitmapBytes; // null位图
        for (int i = 0; i < columns.size(); i++) {
            if (values[i] == null) continue;
            ColumnDef col = columns.get(i);
            size += switch (col.type()) {
                case INT -> 4;
                case FLOAT -> 8;
                case VARCHAR -> 2 + ((String) values[i]).getBytes(StandardCharsets.UTF_8).length;
                case BOOLEAN -> throw new IllegalArgumentException("BOOLEAN 不能作为列类型");
            };
        }

        ByteBuffer buf = ByteBuffer.allocate(size);

        // 写null位图
        byte[] nullBitmap = new byte[bitmapBytes];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                nullBitmap[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        buf.put(nullBitmap);

        // 写各列值
        for (int i = 0; i < columns.size(); i++) {
            if (values[i] == null) continue;
            ColumnDef col = columns.get(i);
            switch (col.type()) {
                case INT -> buf.putInt((Integer) values[i]);
                case FLOAT -> buf.putDouble((Double) values[i]);
                case VARCHAR -> {
                    byte[] bytes = ((String) values[i]).getBytes(StandardCharsets.UTF_8);
                    buf.putShort((short) bytes.length);
                    buf.put(bytes);
                }
                case BOOLEAN -> throw new IllegalArgumentException("BOOLEAN 不能作为列类型");
            }
        }

        return buf.array();
    }

    /**
     * 解码一行数据。
     * @param columns 列定义列表
     * @param data    编码后的字节数组
     * @return 值数组，null表示该列为NULL
     */
    public static Object[] decode(List<ColumnDef> columns, byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);

        // 读null位图
        int bitmapBytes = (columns.size() + 7) / 8;
        byte[] nullBitmap = new byte[bitmapBytes];
        buf.get(nullBitmap);
        Object[] values = new Object[columns.size()];

        // 读各列值
        for (int i = 0; i < columns.size(); i++) {
            if ((nullBitmap[i / 8] & (1 << (i % 8))) != 0) {
                values[i] = null;
                continue;
            }
            ColumnDef col = columns.get(i);
            values[i] = switch (col.type()) {
                case INT -> buf.getInt();
                case FLOAT -> buf.getDouble();
                case VARCHAR -> {
                    short len = buf.getShort();
                    byte[] bytes = new byte[len];
                    buf.get(bytes);
                    yield new String(bytes, StandardCharsets.UTF_8);
                }
                case BOOLEAN -> throw new IllegalArgumentException("BOOLEAN 不能作为列类型");
            };
        }

        return values;
    }
}
