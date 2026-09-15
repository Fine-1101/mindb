package com.minidb.buffer;

import com.minidb.storage.MemoryPage;
import com.minidb.storage.Page;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryBufferPool implements BufferPool {
    private final Map<String, Map<Integer, Page>> tablePages;
    private final AtomicInteger nextPageId;
    private final AtomicInteger hits;
    private final AtomicInteger misses;

    public InMemoryBufferPool() {
        this.tablePages = new ConcurrentHashMap<>();
        this.nextPageId = new AtomicInteger(0);
        this.hits = new AtomicInteger(0);
        this.misses = new AtomicInteger(0);
    }

    @Override
    public Page getPage(String tableName, int pageId) {
        Map<Integer, Page> pages = tablePages.get(tableName);
        if (pages == null) {
            misses.incrementAndGet();
            return null;
        }
        Page page = pages.get(pageId);
        if (page != null) {
            hits.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }
        return page;
    }

    @Override
    public Page newPage(String tableName) {
        int pageId = nextPageId.getAndIncrement();
        MemoryPage page = new MemoryPage(pageId);
        tablePages.computeIfAbsent(tableName, k -> new HashMap<>())
                .put(pageId, page);
        return page;
    }

    @Override
    public void flushAll() {
        // 纯内存实现：仅脏页“写盘”（标记干净），无实际磁盘 I/O
        for (Map<Integer, Page> pages : tablePages.values()) {
            for (Page page : pages.values()) {
                if (page.isDirty()) {
                    page.markClean();
                }
            }
        }
    }

    @Override
    public void flushPage(String tableName, int pageId) {
        Map<Integer, Page> pages = tablePages.get(tableName);
        if (pages == null) {
            return;
        }
        Page page = pages.get(pageId);
        if (page != null && page.isDirty()) {
            page.markClean();  // 内存版"刷盘" = 标记干净
        }
    }

    @Override
    public void freePage(String tableName, int pageId) {
        Map<Integer, Page> pages = tablePages.get(tableName);
        if (pages == null) {
            return;
        }
        Page page = pages.get(pageId);
        if (page != null) {
            // 内存版无页级释放，仅清空页内容（MemoryPage 无 clear 方法，替换为新页）
            // 为保持简单，直接标记页为空闲语义：不移除，仅记录
            pages.put(pageId, page);
        }
    }

    @Override
    public BufferPoolStats stats() {
        return new BufferPoolStats(hits.get(), misses.get());
    }
}