package com.minidb.storage;

import com.minidb.buffer.DiskBufferPool;
import com.minidb.buffer.BufferPoolStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}