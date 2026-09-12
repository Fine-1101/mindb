package com.minidb.storage;

import com.minidb.buffer.DiskBufferPool;
import com.minidb.buffer.BufferPoolStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.minidb.catalog.ColumnDef;
import com.minidb.common.DataType;
import com.minidb.engine.RowEncoder;
import java.util.List;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DiskPageTest {

    private static final String TEST_TABLE = "test_table";
    private DiskBufferPool pool;

    @BeforeEach
    void setUp() throws IOException {
        // 先关闭可能残留的 pool
        if (pool != null) {
            pool.close();
        }
        // 清理测试数据
        Files.deleteIfExists(Paths.get("data", TEST_TABLE + ".dat"));
        pool = new DiskBufferPool(3);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        try {
            Files.deleteIfExists(Paths.get("data", TEST_TABLE + ".dat"));
            Files.deleteIfExists(Paths.get("data", "table_a.dat"));
            Files.deleteIfExists(Paths.get("data", "table_b.dat"));
        } catch (IOException e) {
            // ignore
        }
    }

    @Test
    void diskRoundTrip() {
        Page page = pool.newPage(TEST_TABLE);
        byte[][] originalRows = new byte[10][];

        for (int i = 0; i < 10; i++) {
            originalRows[i] = ("row" + i).getBytes();
            int slot = page.insertRow(originalRows[i]);
            assertEquals(i, slot);
        }

        pool.flushAll();

        // 关闭并重新打开
        pool.close();
        pool = new DiskBufferPool(3);
        Page loadedPage = pool.getPage(TEST_TABLE, 0);

        assertNotNull(loadedPage);

        for (int i = 0; i < 10; i++) {
            byte[] readRow = loadedPage.readRow(i);
            assertArrayEquals(originalRows[i], readRow, "第 " + i + " 行数据应该一致");
        }
    }

    @Test
    void onlyDirtyPagesWritten() {
        Page page1 = pool.newPage(TEST_TABLE);
        Page page2 = pool.newPage(TEST_TABLE);
        Page page3 = pool.newPage(TEST_TABLE);

        page1.insertRow(new byte[]{1, 2, 3});

        pool.getPage(TEST_TABLE, 1);
        pool.getPage(TEST_TABLE, 2);

        pool.flushAll();

        pool.close();
        pool = new DiskBufferPool(3);
        Page loaded1 = pool.getPage(TEST_TABLE, 0);
        Page loaded2 = pool.getPage(TEST_TABLE, 1);
        Page loaded3 = pool.getPage(TEST_TABLE, 2);

        assertNotNull(loaded1);
        assertNotNull(loaded2);
        assertNotNull(loaded3);

        assertArrayEquals(new byte[]{1, 2, 3}, loaded1.readRow(0));
        assertNull(loaded2.readRow(0));
        assertNull(loaded3.readRow(0));
    }

    @Test
    void evictionWritesBack() throws IOException {
        DiskBufferPool smallPool = new DiskBufferPool(2);

        Page page1 = smallPool.newPage(TEST_TABLE);
        page1.insertRow(new byte[]{1, 2, 3});

        Page page2 = smallPool.newPage(TEST_TABLE);
        page2.insertRow(new byte[]{4, 5, 6});

        Page page3 = smallPool.newPage(TEST_TABLE);
        page3.insertRow(new byte[]{7, 8, 9});

        smallPool.flushAll();
        smallPool.close();

        long fileLength = Files.size(Paths.get("data", TEST_TABLE + ".dat"));
        long expectedLength = 3L * (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE);
        assertEquals(expectedLength, fileLength, "文件长度应该等于 3 页");

        DiskBufferPool newPool = new DiskBufferPool(3);
        Page loaded1 = newPool.getPage(TEST_TABLE, 0);
        Page loaded2 = newPool.getPage(TEST_TABLE, 1);
        Page loaded3 = newPool.getPage(TEST_TABLE, 2);

        assertArrayEquals(new byte[]{1, 2, 3}, loaded1.readRow(0));
        assertArrayEquals(new byte[]{4, 5, 6}, loaded2.readRow(0));
        assertArrayEquals(new byte[]{7, 8, 9}, loaded3.readRow(0));
        newPool.close();
    }

    @Test
    void statsWorks() {
        Page page1 = pool.newPage(TEST_TABLE);
        Page page2 = pool.newPage(TEST_TABLE);
        Page page3 = pool.newPage(TEST_TABLE);

        BufferPoolStats stats = pool.stats();
        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());

        pool.getPage(TEST_TABLE, 0);
        pool.getPage(TEST_TABLE, 1);
        pool.getPage(TEST_TABLE, 2);

        stats = pool.stats();
        assertEquals(3, stats.hits());
        assertEquals(0, stats.misses());
    }

    @Test
    void pageIdExistsNeverReturnsNull() {
        pool.newPage(TEST_TABLE);  // 创建 page 0

        Page page = pool.getPage(TEST_TABLE, 0);
        assertNotNull(page, "存在的 pageId 不应该返回 null");

        // 不存在的 pageId 返回 null（兜底）
        Page missing = pool.getPage(TEST_TABLE, 100);
        assertNull(missing);
    }

    // 在 DiskPageTest.java 中新增

    @Test
    void updatePersistsAcrossRestart() throws IOException {
        // 拍板2：UPDATE 后 close→重启→数据一致（含变长 VARCHAR 搬家行）
        List<ColumnDef> columns = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("name", DataType.VARCHAR, 64));

        // 第一页：插入 3 行
        Page page = pool.newPage(TEST_TABLE);
        byte[] row1 = RowEncoder.encode(columns, new Object[]{1, "short"});
        byte[] row2 = RowEncoder.encode(columns, new Object[]{2, "Alice"});
        byte[] row3 = RowEncoder.encode(columns, new Object[]{3, "Bob"});
        page.insertRow(row1);
        page.insertRow(row2);
        page.insertRow(row3);

        pool.flushAll();

        // 模拟 UPDATE：deleteRow(1) + insertRow(变长新行)
        String longName = "A_very_long_name_that_might_need_more_space_than_before_1234567890";
        byte[] newRow2 = RowEncoder.encode(columns, new Object[]{2, longName});
        page.deleteRow(1);
        int newSlot = page.insertRow(newRow2);
        assertTrue(newSlot >= 0, "UPDATE 新行应该插入成功");

        pool.flushAll();
        pool.close();

        // 重启
        pool = new DiskBufferPool(3);
        Page loadedPage = pool.getPage(TEST_TABLE, 0);
        assertNotNull(loadedPage, "重启后页应该存在");

        // 验证：旧行已删，新行可读
        assertNull(loadedPage.readRow(1), "UPDATE 后旧行应该为 null");

        // 验证其他行完整
        Object[] decoded0 = RowEncoder.decode(columns, loadedPage.readRow(0));
        assertEquals(1, decoded0[0]);
        assertEquals("short", decoded0[1]);

        Object[] decoded2 = RowEncoder.decode(columns, loadedPage.readRow(2));
        assertEquals(3, decoded2[0]);
        assertEquals("Bob", decoded2[1]);

        // 验证新行（变长 VARCHAR 搬家行）
        Object[] decodedNew = RowEncoder.decode(columns, loadedPage.readRow(newSlot));
        assertEquals(2, decodedNew[0]);
        assertEquals(longName, decodedNew[1]);
    }

    @Test
    void nullPersistsAcrossRestart() throws IOException {
        // 拍板4：NULL 落盘不丢
        List<ColumnDef> columns = List.of(
                new ColumnDef("id", DataType.INT, 0),
                new ColumnDef("score", DataType.FLOAT, 0),
                new ColumnDef("name", DataType.VARCHAR, 32));

        Page page = pool.newPage(TEST_TABLE);
        Object[][] values = {
                {1, 95.5, "Alice"},
                {null, 88.0, "Bob"},
                {3, null, "Carol"},
                {4, 72.5, null},
                {null, null, null},
        };
        for (Object[] v : values) {
            page.insertRow(RowEncoder.encode(columns, v));
        }

        pool.flushAll();
        pool.close();

        // 重启
        pool = new DiskBufferPool(3);
        Page loadedPage = pool.getPage(TEST_TABLE, 0);
        assertNotNull(loadedPage, "重启后页应该存在");

        // 验证 NULL 不丢
        for (int i = 0; i < values.length; i++) {
            Object[] decoded = RowEncoder.decode(columns, loadedPage.readRow(i));
            assertArrayEquals(values[i], decoded,
                    "第 " + i + " 行 NULL 值重启后应该保持");
        }
    }

    @Test
    void twoTablesCoexist() {
        // 拍板10：双表页并存，互不干扰
        Page pageA = pool.newPage("table_a");
        Page pageB = pool.newPage("table_b");

        byte[] rowA = new byte[]{1, 2, 3};
        byte[] rowB = new byte[]{4, 5, 6, 7};

        pageA.insertRow(rowA);
        pageB.insertRow(rowB);

        pool.flushAll();
        pool.close();

        // 重启
        pool = new DiskBufferPool(3);
        Page loadedA = pool.getPage("table_a", 0);
        Page loadedB = pool.getPage("table_b", 0);

        assertNotNull(loadedA, "table_a 页应该存在");
        assertNotNull(loadedB, "table_b 页应该存在");

        assertArrayEquals(rowA, loadedA.readRow(0), "table_a 数据应该完整");
        assertArrayEquals(rowB, loadedB.readRow(0), "table_b 数据应该完整");

        // 验证两表独立
        assertEquals(1, loadedA.slotCount(), "table_a 应该有 1 行");
        assertEquals(1, loadedB.slotCount(), "table_b 应该有 1 行");
    }
}