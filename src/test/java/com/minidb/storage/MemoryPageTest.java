package com.minidb.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPageTest {

    @Test
    void insertThenReadRows() {
        MemoryPage page = new MemoryPage(1);

        byte[] row1 = new byte[]{1, 2, 3};
        byte[] row2 = new byte[]{4, 5, 6, 7};

        int slot1 = page.insertRow(row1);
        int slot2 = page.insertRow(row2);

        assertEquals(0, slot1);
        assertEquals(1, slot2);
        assertArrayEquals(row1, page.readRow(0));
        assertArrayEquals(row2, page.readRow(1));
    }

    @Test
    void freeSpaceDecreasesWithInsert() {
        MemoryPage page = new MemoryPage(1);
        int initialFree = page.freeSpace();

        byte[] row = new byte[100];
        page.insertRow(row);

        assertEquals(initialFree - 100, page.freeSpace());
        assertEquals(100, page.getUsedSpace());
    }

    @Test
    void insertReturnsMinusOneWhenFull() {
        MemoryPage page = new MemoryPage(1);

        // 先插入大量中等大小的行
        int inserted = 0;
        while (page.freeSpace() > 100) {
            int slot = page.insertRow(new byte[50]);
            if (slot == -1) break;
            inserted++;
        }

        // 然后尝试插入小数据，直到填满
        while (true) {
            int slot = page.insertRow(new byte[1]);
            if (slot == -1) break;
            inserted++;
        }

        // 验证页面已满
        assertTrue(page.freeSpace() == 0 || page.freeSpace() < 1,
                "页面应该已满，当前空闲空间: " + page.freeSpace());

        // 再次尝试插入，应该返回 -1
        int result = page.insertRow(new byte[1]);
        assertEquals(-1, result, "页满后插入应该返回 -1");

        // 验证之前的数据仍然存在
        assertNotNull(page.readRow(0), "第一行数据应该存在");
        assertTrue(inserted > 0, "应该至少插入了一行");
    }

    @Test
    void fullPageDoesNotCorruptExistingData() {
        MemoryPage page = new MemoryPage(1);

        byte[] firstRow = new byte[]{1, 2, 3, 4, 5};
        page.insertRow(firstRow);

        while (page.insertRow(new byte[1]) != -1) {
            // 继续插入
        }

        assertArrayEquals(firstRow, page.readRow(0));
    }

    @Test
    void deleteRowReclaimsSpace() {
        MemoryPage page = new MemoryPage(1);
        byte[] row1 = new byte[]{1, 2, 3};
        byte[] row2 = new byte[]{4, 5, 6, 7};

        page.insertRow(row1);
        page.insertRow(row2);

        int freeBefore = page.freeSpace();
        page.deleteRow(0);

        assertEquals(freeBefore + row1.length, page.freeSpace());
        assertNull(page.readRow(0));
        assertArrayEquals(row2, page.readRow(1));
    }

    @Test
    void readInvalidSlotReturnsNull() {
        MemoryPage page = new MemoryPage(1);
        assertNull(page.readRow(-1));
        assertNull(page.readRow(100));

        page.insertRow(new byte[]{1, 2, 3});
        assertNull(page.readRow(5));
    }

    @Test
    void nullRowInsertionReturnsMinusOne() {
        MemoryPage page = new MemoryPage(1);
        assertEquals(-1, page.insertRow(null));
        assertEquals(0, page.getRowCount());
    }

    @Test
    void pageIdIsCorrect() {
        MemoryPage page1 = new MemoryPage(42);
        assertEquals(42, page1.pageId());

        MemoryPage page2 = new MemoryPage(100);
        assertEquals(100, page2.pageId());
    }

    @Test
    void insertLargeRowsHandlesBoundary() {
        MemoryPage page = new MemoryPage(1);
        byte[] largeRow = new byte[4000];

        int slot = page.insertRow(largeRow);
        assertEquals(0, slot);
        assertEquals(4000, page.getUsedSpace());
        assertEquals(MemoryPage.PAGE_SIZE - 4000, page.freeSpace());

        assertEquals(-1, page.insertRow(new byte[200]));
        assertArrayEquals(largeRow, page.readRow(0));
    }

    @Test
    void insert10RowsReturnsIncrementalSlots() {
        MemoryPage page = new MemoryPage(1);

        for (int i = 0; i < 10; i++) {
            byte[] row = ("row" + i).getBytes();
            int slot = page.insertRow(row);
            assertEquals(i, slot, "槽号应该递增: 期望 " + i + "，实际 " + slot);
        }

        assertEquals(10, page.getRowCount());
    }

    @Test
    void read10RowsMatchesWrittenData() {
        MemoryPage page = new MemoryPage(1);
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
    void freeSpaceMonotonicallyDecreases() {
        MemoryPage page = new MemoryPage(1);
        int previousFree = page.freeSpace();

        for (int i = 0; i < 10; i++) {
            byte[] row = new byte[10];
            page.insertRow(row);
            int currentFree = page.freeSpace();
            assertTrue(currentFree < previousFree,
                    "空闲空间应该递减: 之前 " + previousFree + "，现在 " + currentFree);
            assertEquals(previousFree - 10, currentFree);
            previousFree = currentFree;
        }
    }

    @Test
    void insert10RowsWithMixedIntVarchar() {
        MemoryPage page = new MemoryPage(1);
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
            assertEquals(i, slot, "槽号应该递增: 期望 " + i + "，实际 " + slot);
        }

        for (int i = 0; i < 10; i++) {
            byte[] readRow = page.readRow(i);
            assertArrayEquals(originalRows[i], readRow, "第 " + i + " 行数据应该完全一致");

            int id = bytesToInt(readRow, 0);
            int nameLen = ((readRow[4] & 0xFF) << 8) | (readRow[5] & 0xFF);
            String name = new String(readRow, 6, nameLen);

            assertEquals(i * 100, id, "ID应该匹配");
            assertEquals("Row" + i, name, "名称应该匹配");
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