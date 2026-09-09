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
}