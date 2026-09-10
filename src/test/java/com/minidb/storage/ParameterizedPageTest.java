package com.minidb.storage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

        int expectedDecrease;
        if (page instanceof MemoryPage) {
            expectedDecrease = 100;
        } else {
            expectedDecrease = 100 + Page.SLOT_ENTRY_SIZE;
        }

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
}