package com.minidb.buffer;

import com.minidb.storage.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolStrategyTest {

    @Test
    void newPageReturnsDifferentPageIds() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(10, BufferPoolWithStrategy.Strategy.LRU);

        Page page1 = pool.newPage("test");
        Page page2 = pool.newPage("test");

        assertNotEquals(page1.pageId(), page2.pageId());
    }

    @Test
    void getPageNonExistentReturnsNull() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(10, BufferPoolWithStrategy.Strategy.LRU);

        assertNull(pool.getPage("test", 999));
    }

    @Test
    void getPageAfterNewPageReturnsPage() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(10, BufferPoolWithStrategy.Strategy.LRU);

        Page page = pool.newPage("test");
        Page retrieved = pool.getPage("test", page.pageId());

        assertNotNull(retrieved);
        assertEquals(page.pageId(), retrieved.pageId());
    }

    @Test
    void lruEvictsLeastRecentlyUsed() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.LRU);

        Page a = pool.newPage("test");
        Page b = pool.newPage("test");
        Page c = pool.newPage("test");

        pool.getPage("test", a.pageId());

        Page d = pool.newPage("test");

        assertNotNull(pool.getPage("test", a.pageId()), "A 应该在缓冲池中");
        assertNull(pool.getPage("test", b.pageId()), "B 应该被淘汰");
        assertNotNull(pool.getPage("test", c.pageId()), "C 应该在缓冲池中");
        assertNotNull(pool.getPage("test", d.pageId()), "D 应该在缓冲池中");
    }

    @Test
    void fifoEvictsFirstIn() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.FIFO);

        Page a = pool.newPage("test");
        Page b = pool.newPage("test");
        Page c = pool.newPage("test");

        pool.getPage("test", a.pageId());

        Page d = pool.newPage("test");

        assertNull(pool.getPage("test", a.pageId()), "A 应该被淘汰");
        assertNotNull(pool.getPage("test", b.pageId()), "B 应该在缓冲池中");
        assertNotNull(pool.getPage("test", c.pageId()), "C 应该在缓冲池中");
        assertNotNull(pool.getPage("test", d.pageId()), "D 应该在缓冲池中");
    }

    @Test
    void lruAndFifoEvictDifferentPages() {
        BufferPoolWithStrategy lruPool = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.LRU);
        BufferPoolWithStrategy fifoPool = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.FIFO);

        Page lruA = lruPool.newPage("test");
        Page lruB = lruPool.newPage("test");
        Page lruC = lruPool.newPage("test");
        lruPool.getPage("test", lruA.pageId());
        Page lruD = lruPool.newPage("test");

        Page fifoA = fifoPool.newPage("test");
        Page fifoB = fifoPool.newPage("test");
        Page fifoC = fifoPool.newPage("test");
        fifoPool.getPage("test", fifoA.pageId());
        Page fifoD = fifoPool.newPage("test");

        assertNotNull(lruPool.getPage("test", lruA.pageId()));
        assertNull(lruPool.getPage("test", lruB.pageId()));

        assertNull(fifoPool.getPage("test", fifoA.pageId()));
        assertNotNull(fifoPool.getPage("test", fifoB.pageId()));

        assertNotEquals(lruPool.getBufferContent(), fifoPool.getBufferContent());
    }

    @Test
    void statsWorksCorrectly() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.LRU);

        // 3 次 new -> 3 misses（newPage 不增加统计）
        Page a = pool.newPage("test");
        Page b = pool.newPage("test");
        Page c = pool.newPage("test");

        // 统计应该还是 0
        BufferPoolStats stats1 = pool.stats();
        assertEquals(0, stats1.hits());
        assertEquals(0, stats1.misses());

        // 3 次 get -> 3 hits
        pool.getPage("test", a.pageId());
        pool.getPage("test", b.pageId());
        pool.getPage("test", c.pageId());

        BufferPoolStats stats = pool.stats();
        assertEquals(3, stats.hits());
        assertEquals(0, stats.misses());  // 注意：misses 是未命中的次数，不是 new 的次数

        // 访问不存在的页面 -> miss
        pool.getPage("test", 999);
        stats = pool.stats();
        assertEquals(3, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(3.0 / 4.0, stats.hitRate(), 0.0001);
    }

    @Test
    void statsAfterNewPage() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(10, BufferPoolWithStrategy.Strategy.LRU);

        pool.newPage("test");
        BufferPoolStats stats = pool.stats();

        assertEquals(0, stats.hits());
        assertEquals(0, stats.misses());
    }

    @Test
    void flushAllDoesNotThrow() {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(10, BufferPoolWithStrategy.Strategy.LRU);

        pool.newPage("test");
        pool.newPage("test");

        assertDoesNotThrow(() -> pool.flushAll());
    }
}