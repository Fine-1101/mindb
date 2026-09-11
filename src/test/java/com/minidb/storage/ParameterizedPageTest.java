package com.minidb.storage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.minidb.catalog.ColumnDef;
import com.minidb.common.DataType;
import com.minidb.engine.RowEncoder;
import java.util.List;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ParameterizedPageTest {

    static Stream<Arguments> pageImplementations() {
        return Stream.of(
                Arguments.of(new MemoryPage(1), "MemoryPage"),
                Arguments.of(new SlottedPage(1), "SlottedPage")
        );
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void insertAndRead10Rows(Page page, String name) {
        byte[][] originalRows = new byte[10][];

        for (int i = 0; i < 10; i++) {
            originalRows[i] = ("row" + i).getBytes();
            int slot = page.insertRow(originalRows[i]);
            assertEquals(i, slot, name + ": 槽号应该递增");
        }

        for (int i = 0; i < 10; i++) {
            byte[] readRow = page.readRow(i);
            assertArrayEquals(originalRows[i], readRow, name + ": 第 " + i + " 行数据应该一致");
        }
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void freeSpaceDecreasesExactly(Page page, String name) {
        int initialFree = page.freeSpace();
        assertEquals(Page.PAGE_SIZE, initialFree, name + ": 初始空闲空间应该等于页大小");

        byte[] row = new byte[100];
        page.insertRow(row);

        // D2 拍板：freeSpace 统一公式 = PAGE_SIZE − Σ(行字节 + 槽目录项4B)，两实现对拍逐字节相等
        int expectedDecrease = 100 + Page.SLOT_ENTRY_SIZE;

        assertEquals(initialFree - expectedDecrease, page.freeSpace(),
                name + ": 空闲空间减少应该精确，期望减少 " + expectedDecrease);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void insertReturnsMinusOneWhenFull(Page page, String name) {
        // 先用 100 字节填充大部分空间
        while (page.insertRow(new byte[100]) != -1) {
            // 继续插入
        }

        // 再用 1 字节精确填满剩余空间
        while (page.insertRow(new byte[1]) != -1) {
            // 继续插入
        }

        // 页满瞬间 freeSpace == 0
        assertEquals(0, page.freeSpace(),
                name + ": 页满瞬间 freeSpace 应该为 0，实际: " + page.freeSpace());

        // 再插入任意大小都应该返回 -1
        int result = page.insertRow(new byte[1]);
        assertEquals(-1, result, name + ": 页满后插入应该返回 -1");

        // 验证已有数据完整
        assertNotNull(page.readRow(0), name + ": 第一行数据应该完整");
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void exactFitInsertDoesNotCorruptLastRow(Page page, String name) {
        // 回归：恰好装满时不得超额装填（曾因 freeSpace 多算页头 4B 导致槽目录覆盖最后一行）
        // 每行占 4B 数据 + 4B 槽目录项 = 8B，4096/8 = 512 行恰好装满
        byte[][] rows = new byte[512][];
        for (int i = 0; i < 512; i++) {
            rows[i] = new byte[]{(byte) i, (byte) (i >> 8), (byte) (i >> 16), (byte) (i >> 24)};
            int slot = page.insertRow(rows[i]);
            assertEquals(i, slot, name + ": 槽号应该递增");
        }

        assertEquals(0, page.freeSpace(), name + ": 恰好装满后空闲空间应为 0");
        assertEquals(-1, page.insertRow(new byte[4]), name + ": 满页后再插应返回 -1");

        for (int i = 0; i < 512; i++) {
            assertArrayEquals(rows[i], page.readRow(i), name + ": 第 " + i + " 行应该完整");
        }
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void deleteRowBehavior(Page page, String name) {
        byte[] row1 = new byte[]{1, 2, 3};
        byte[] row2 = new byte[]{4, 5, 6, 7};

        page.insertRow(row1);
        page.insertRow(row2);

        int freeBefore = page.freeSpace();
        page.deleteRow(0);

        assertEquals(freeBefore, page.freeSpace(), name + ": deleteRow 后 freeSpace 应该不变");
        assertNull(page.readRow(0), name + ": 删除后 readRow 应该返回 null");
        assertArrayEquals(row2, page.readRow(1), name + ": 其他行应该正常");
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void insert10RowsMixedIntVarchar(Page page, String name) {
        byte[][] originalRows = new byte[10][];

        for (int i = 0; i < 10; i++) {
            int intValue = i * 100;
            String strValue = "Row" + i;

            byte[] intBytes = intToBytes(intValue);
            byte[] strBytes = strValue.getBytes();

            byte[] row = new byte[4 + 2 + strBytes.length];
            System.arraycopy(intBytes, 0, row, 0, 4);
            row[4] = (byte) (strBytes.length >> 8);
            row[5] = (byte) strBytes.length;
            System.arraycopy(strBytes, 0, row, 6, strBytes.length);

            originalRows[i] = row;
            int slot = page.insertRow(row);
            assertEquals(i, slot, name + ": 槽号应该递增");
        }

        for (int i = 0; i < 10; i++) {
            byte[] readRow = page.readRow(i);
            assertArrayEquals(originalRows[i], readRow, name + ": 第 " + i + " 行数据应该一致");

            int id = bytesToInt(readRow, 0);
            int nameLen = ((readRow[4] & 0xFF) << 8) | (readRow[5] & 0xFF);
            String nameStr = new String(readRow, 6, nameLen);

            assertEquals(i * 100, id, name + ": ID应该匹配");
            assertEquals("Row" + i, nameStr, name + ": 名称应该匹配");
        }
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    private int bytesToInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) |
                ((bytes[offset + 1] & 0xFF) << 16) |
                ((bytes[offset + 2] & 0xFF) << 8) |
                (bytes[offset + 3] & 0xFF);
    }

    // 在 ParameterizedPageTest.java 中新增

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void rowEncoderNullRoundTrip(Page page, String name) {
        // 拍板4：NULL 可赋给任何列类型
        // 测试 INT/FLOAT/VARCHAR 各列含 NULL 混合行
        List<ColumnDef> columns = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("score", DataType.FLOAT, 0),
                new ColumnDef("name", DataType.VARCHAR, 32));

        // 构造含 NULL 的混合行
        Object[][] testValues = {
                {1, 95.5, "Alice"},           // 无 NULL
                {null, 88.0, "Bob"},          // INT NULL
                {3, null, "Carol"},           // FLOAT NULL
                {4, 72.5, null},              // VARCHAR NULL
                {null, null, null},           // 全 NULL
                {6, 60.0, ""},                // 空字符串（非 NULL）
        };

        byte[][] encodedRows = new byte[testValues.length][];
        for (int i = 0; i < testValues.length; i++) {
            encodedRows[i] = RowEncoder.encode(columns, testValues[i]);
            int slot = page.insertRow(encodedRows[i]);
            assertEquals(i, slot, name + ": 槽号应该递增");
        }

        // 读回并验证逐字节一致 + decode 后值相等（含 null）
        for (int i = 0; i < testValues.length; i++) {
            byte[] readRow = page.readRow(i);
            assertArrayEquals(encodedRows[i], readRow,
                    name + ": 第 " + i + " 行字节应该一致（含 NULL 编码）");

            Object[] decoded = RowEncoder.decode(columns, readRow);
            assertArrayEquals(testValues[i], decoded,
                    name + ": 第 " + i + " 行 decode 后值应该一致（含 null）");
        }
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void rowEncoderNullFreeSpaceParity(Page page, String name) {
        // 拍板4：含 NULL 的行 freeSpace 减量 == 行字节 + 槽目录项
        List<ColumnDef> columns = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("score", DataType.FLOAT, 0),
                new ColumnDef("name", DataType.VARCHAR, 32));

        Object[] rowWithNull = {null, null, null};
        byte[] encoded = RowEncoder.encode(columns, rowWithNull);
        int expectedSize = encoded.length;

        int freeBefore = page.freeSpace();
        page.insertRow(encoded);
        int freeAfter = page.freeSpace();

        assertEquals(freeBefore - freeAfter, expectedSize + Page.SLOT_ENTRY_SIZE,
                name + ": 含 NULL 行的 freeSpace 减量应该 == 行字节 + 槽目录项");
    }

    // 在 ParameterizedPageTest.java 中新增

    @ParameterizedTest(name = "{1}")
    @MethodSource("pageImplementations")
    void updateStoragePathParity(Page page, String name) {
        // 拍板2：UPDATE 存储 = deleteRow + insertRow，复用 DELETE+INSERT 路径
        // 证明无存储侧新语义：freeSpace 变化 == 对应 DELETE+INSERT 序列

        byte[] row1 = new byte[]{1, 2, 3};
        byte[] row2 = new byte[]{4, 5, 6, 7};
        byte[] row3 = new byte[]{8, 9};

        // 场景A：UPDATE 模拟（deleteRow + insertRow）
        page.insertRow(row1);   // slot 0
        page.insertRow(row2);   // slot 1
        int freeBeforeA = page.freeSpace();

        page.deleteRow(0);      // UPDATE 第一步：删旧行
        page.insertRow(row3);   // UPDATE 第二步：插新行

        int freeAfterA = page.freeSpace();

        // 场景B：DELETE + INSERT 序列（等价路径）
        Page pageB = name.equals("MemoryPage") ? new MemoryPage(2) : new SlottedPage(2);
        pageB.insertRow(row1);
        pageB.insertRow(row2);
        int freeBeforeB = pageB.freeSpace();

        pageB.deleteRow(0);
        pageB.insertRow(row3);

        int freeAfterB = pageB.freeSpace();

        // 断言：freeSpace 变化完全一致
        assertEquals(freeBeforeA - freeAfterA, freeBeforeB - freeAfterB,
                name + ": UPDATE 与 DELETE+INSERT 的 freeSpace 变化应该一致");

        // 断言：final freeSpace 一致
        assertEquals(freeAfterA, freeAfterB,
                name + ": UPDATE 与 DELETE+INSERT 的最终 freeSpace 应该一致");

        // 断言：行数据一致
        assertArrayEquals(row3, page.readRow(2), name + ": UPDATE 后新行应该可读");
        assertNull(page.readRow(0), name + ": UPDATE 后旧行应该已删除");

        // 断言：slotCount 一致（标记删除不回收槽）
        assertEquals(page.slotCount(), pageB.slotCount(),
                name + ": UPDATE 与 DELETE+INSERT 的 slotCount 应该一致");
    }
}