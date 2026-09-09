package com.minidb.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlottedPageTest {

    @Test
    void insert10RowsReturnsIncrementalSlots() {
        SlottedPage page = new SlottedPage(1);

        for (int i = 0; i < 10; i++) {
            byte[] row = ("row" + i).getBytes();
            int slot = page.insertRow(row);
            assertEquals(i, slot);
        }

        assertEquals(10, page.getSlotCount());
    }

    @Test
    void read10RowsMatchesWrittenData() {
        SlottedPage page = new SlottedPage(1);
        byte[][] originalRows = new byte[10][];

        for (int i = 0; i < 10; i++) {
            originalRows[i] = ("row" + i).getBytes();
            page.insertRow(originalRows[i]);
        }

        for (int i = 0; i < 10; i++) {
            byte[] readRow = page.readRow(i);
            assertArrayEquals(originalRows[i], readRow, "第 " + i + " 行数据应该一致");
        }
    }

    @Test
    void freeSpaceDecreasesWithInsert() {
        SlottedPage page = new SlottedPage(1);
        int initialFree = page.freeSpace();
        assertEquals(Page.PAGE_SIZE, initialFree, "初始空闲空间应该等于页大小");

        byte[] row = new byte[100];
        page.insertRow(row);

        int expectedDecrease = 100 + Page.SLOT_ENTRY_SIZE;
        assertEquals(initialFree - expectedDecrease, page.freeSpace(),
                "期望减少 " + expectedDecrease);
    }

    @Test
    void freeSpaceMonotonicallyDecreases() {
        SlottedPage page = new SlottedPage(1);
        int previousFree = page.freeSpace();

        for (int i = 0; i < 10; i++) {
            byte[] row = new byte[10];
            page.insertRow(row);
            int currentFree = page.freeSpace();
            assertTrue(currentFree < previousFree);
            int expectedDecrease = 10 + Page.SLOT_ENTRY_SIZE;
            assertEquals(previousFree - expectedDecrease, currentFree);
            previousFree = currentFree;
        }
    }

    @Test
    void insertReturnsMinusOneWhenFull() {
        SlottedPage page = new SlottedPage(1);

        // 先用 100 字节填充大部分空间
        while (page.insertRow(new byte[100]) != -1) {
            // 继续插入
        }

        // 再用 1 字节精确填满剩余空间
        while (page.insertRow(new byte[1]) != -1) {
            // 继续插入
        }

        // 验证页面已满
        assertTrue(page.freeSpace() < 1,
                "页面应该已满，当前空闲空间: " + page.freeSpace());

        // 再插入任意大小都应该返回 -1
        int result = page.insertRow(new byte[1]);
        assertEquals(-1, result, "页满后插入应该返回 -1，实际返回: " + result);

        // 验证已有数据完整
        assertNotNull(page.readRow(0), "第一行数据应该完整");
    }

    @Test
    void fullPageDoesNotCorruptExistingData() {
        SlottedPage page = new SlottedPage(1);

        byte[] firstRow = new byte[]{1, 2, 3, 4, 5};
        page.insertRow(firstRow);

        while (page.insertRow(new byte[10]) != -1) {
            // 继续插入
        }

        assertArrayEquals(firstRow, page.readRow(0));
    }

    @Test
    void deleteRowMarksDeletedAndFreeSpaceUnchanged() {
        SlottedPage page = new SlottedPage(1);

        byte[] row1 = new byte[]{1, 2, 3};
        byte[] row2 = new byte[]{4, 5, 6, 7};

        page.insertRow(row1);
        page.insertRow(row2);

        int freeBefore = page.freeSpace();
        page.deleteRow(0);

        assertEquals(freeBefore, page.freeSpace());
        assertNull(page.readRow(0));
        assertArrayEquals(row2, page.readRow(1));
    }

    @Test
    void insert10RowsWithMixedIntVarchar() {
        SlottedPage page = new SlottedPage(1);
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
            assertEquals(i, slot);
        }

        for (int i = 0; i < 10; i++) {
            byte[] readRow = page.readRow(i);
            assertArrayEquals(originalRows[i], readRow);

            int id = bytesToInt(readRow, 0);
            int nameLen = ((readRow[4] & 0xFF) << 8) | (readRow[5] & 0xFF);
            String name = new String(readRow, 6, nameLen);

            assertEquals(i * 100, id);
            assertEquals("Row" + i, name);
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