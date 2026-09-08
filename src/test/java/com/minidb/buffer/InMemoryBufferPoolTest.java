package com.minidb.buffer;

import com.minidb.storage.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryBufferPoolTest {

    @Test
    void newPageReturnsDifferentPages() {
        InMemoryBufferPool pool = new InMemoryBufferPool();

        Page page1 = pool.newPage("test_table");
        Page page2 = pool.newPage("test_table");
        Page page3 = pool.newPage("another_table");

        assertNotNull(page1);
        assertNotNull(page2);
        assertNotNull(page3);
        assertNotEquals(page1.pageId(), page2.pageId());
        assertNotEquals(page1.pageId(), page3.pageId());
    }

    @Test
    void getPageRetrievesNewlyCreatedPage() {
        InMemoryBufferPool pool = new InMemoryBufferPool();
        Page newPage = pool.newPage("test_table");
        int pageId = newPage.pageId();

        Page retrievedPage = pool.getPage("test_table", pageId);
        assertNotNull(retrievedPage);
        assertEquals(pageId, retrievedPage.pageId());
        assertSame(newPage, retrievedPage);
    }

    @Test
    void getPageNonExistentReturnsNull() {
        InMemoryBufferPool pool = new InMemoryBufferPool();

        assertNull(pool.getPage("test_table", 999));
        assertNull(pool.getPage("non_existent_table", 0));
    }

    @Test
    void getPageOnlyReturnsPagesForCorrectTable() {
        InMemoryBufferPool pool = new InMemoryBufferPool();

        Page page1 = pool.newPage("users");
        Page page2 = pool.newPage("users");
        Page page3 = pool.newPage("orders");

        Page retrieved1 = pool.getPage("users", page1.pageId());
        Page retrieved2 = pool.getPage("users", page2.pageId());
        Page retrieved3 = pool.getPage("orders", page3.pageId());

        assertNotNull(retrieved1, "应该能获取 users 表的第一个页面");
        assertNotNull(retrieved2, "应该能获取 users 表的第二个页面");
        assertNotNull(retrieved3, "应该能获取 orders 表的第一个页面");

        assertEquals(page1.pageId(), retrieved1.pageId());
        assertEquals(page2.pageId(), retrieved2.pageId());
        assertEquals(page3.pageId(), retrieved3.pageId());

        assertNull(pool.getPage("users", page3.pageId()), "users 表不应该有 orders 的页面");
        assertNull(pool.getPage("orders", page1.pageId()), "orders 表不应该有 users 的页面");
    }

    @Test
    void flushAllDoesNotThrowException() {
        InMemoryBufferPool pool = new InMemoryBufferPool();
        pool.newPage("test_table");
        pool.newPage("test_table");

        assertDoesNotThrow(() -> pool.flushAll());
    }

    @Test
    void statsInitiallyZero() {
        InMemoryBufferPool pool = new InMemoryBufferPool();
        BufferPoolStats stats = pool.stats();

        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
        assertEquals(0.0, stats.hitRate());
    }

    @Test
    void statsTracksHitsAndMisses() {
        InMemoryBufferPool pool = new InMemoryBufferPool();
        Page page = pool.newPage("test_table");

        pool.getPage("test_table", page.pageId());
        BufferPoolStats stats = pool.stats();
        assertEquals(1, stats.hits());
        assertEquals(0, stats.misses());
        assertEquals(1.0, stats.hitRate());

        pool.getPage("test_table", 999);
        stats = pool.stats();
        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(0.5, stats.hitRate());
    }

    @Test
    void statsMultipleOperations() {
        InMemoryBufferPool pool = new InMemoryBufferPool();
        Page page1 = pool.newPage("table1");
        Page page2 = pool.newPage("table2");

        pool.getPage("table1", page1.pageId());
        pool.getPage("table2", page2.pageId());
        pool.getPage("table1", 999);
        pool.getPage("table2", 888);

        BufferPoolStats stats = pool.stats();
        assertEquals(2, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(0.5, stats.hitRate());
    }

    @Test
    void newPageDoesNotAffectStats() {
        InMemoryBufferPool pool = new InMemoryBufferPool();

        pool.newPage("test_table");
        pool.newPage("test_table");

        BufferPoolStats stats = pool.stats();
        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
    }

    @Test
    void multiplePagesInSameTable() {
        InMemoryBufferPool pool = new InMemoryBufferPool();

        Page page1 = pool.newPage("users");
        Page page2 = pool.newPage("users");
        Page page3 = pool.newPage("users");

        assertNotNull(pool.getPage("users", page1.pageId()));
        assertNotNull(pool.getPage("users", page2.pageId()));
        assertNotNull(pool.getPage("users", page3.pageId()));
        assertNotEquals(page1.pageId(), page2.pageId());
        assertNotEquals(page2.pageId(), page3.pageId());
    }

    @Test
    void bufferPoolHandlesManyTables() {
        InMemoryBufferPool pool = new InMemoryBufferPool();

        for (int i = 0; i < 10; i++) {
            String tableName = "table" + i;
            Page page = pool.newPage(tableName);
            assertNotNull(pool.getPage(tableName, page.pageId()));
        }

        BufferPoolStats stats = pool.stats();
        assertEquals(10, stats.hits());
        assertEquals(0, stats.misses());
    }

    @Test
    void getPageAfterFlushAllStillWorks() {
        InMemoryBufferPool pool = new InMemoryBufferPool();
        Page page = pool.newPage("test_table");

        pool.flushAll();

        Page retrieved = pool.getPage("test_table", page.pageId());
        assertNotNull(retrieved);
        assertEquals(page.pageId(), retrieved.pageId());
    }
}