package com.minidb.buffer;

import com.minidb.storage.Page;
import com.minidb.storage.SlottedPage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BufferPoolWithStrategy implements BufferPool {
    public enum Strategy { LRU, FIFO }

    private final int capacity;
    private final Strategy strategy;
    private final Map<String, Map<Integer, Page>> tablePages;
    private final Map<Integer, Page> allPages;
    private final List<Integer> accessOrder;
    private final Set<Integer> inBuffer;
    private final AtomicInteger nextPageId;
    private final AtomicLong hits;
    private final AtomicLong misses;

    public BufferPoolWithStrategy(int capacity, Strategy strategy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.strategy = strategy;
        this.tablePages = new ConcurrentHashMap<>();
        this.allPages = new ConcurrentHashMap<>();
        this.accessOrder = Collections.synchronizedList(new ArrayList<>());
        this.inBuffer = ConcurrentHashMap.newKeySet();
        this.nextPageId = new AtomicInteger(0);
        this.hits = new AtomicLong(0);
        this.misses = new AtomicLong(0);
    }

    @Override
    public Page getPage(String tableName, int pageId) {
        // 检查是否在缓冲池中
        if (inBuffer.contains(pageId)) {
            hits.incrementAndGet();
            if (strategy == Strategy.LRU) {
                synchronized (accessOrder) {
                    accessOrder.remove(Integer.valueOf(pageId));
                    accessOrder.add(pageId);
                }
            }
            return allPages.get(pageId);
        }

        // 未命中
        misses.incrementAndGet();
        return null;
    }

    @Override
    public Page newPage(String tableName) {
        int pageId = nextPageId.getAndIncrement();
        Page page = new SlottedPage(pageId);

        allPages.put(pageId, page);
        tablePages.computeIfAbsent(tableName, k -> new HashMap<>())
                .put(pageId, page);

        // 如果缓冲池已满，需要淘汰
        if (inBuffer.size() >= capacity) {
            evict();
        }

        inBuffer.add(pageId);
        synchronized (accessOrder) {
            accessOrder.add(pageId);
        }

        return page;
    }

    private void evict() {
        if (inBuffer.isEmpty()) {
            return;
        }

        int victimId;
        synchronized (accessOrder) {
            // LRU：getPage 命中时把页移到队尾，队首=最久未访问；
            // FIFO：命中不刷新，队首=最早进入缓冲池。淘汰逻辑统一取队首。
            victimId = accessOrder.remove(0);
        }

        inBuffer.remove(victimId);
    }

    @Override
    public void flushAll() {
        // 纯内存实现，无需 flush
    }

    @Override
    public BufferPoolStats stats() {
        return new BufferPoolStats(hits.get(), misses.get());
    }

    public Set<Integer> getBufferContent() {
        return new HashSet<>(inBuffer);
    }
}