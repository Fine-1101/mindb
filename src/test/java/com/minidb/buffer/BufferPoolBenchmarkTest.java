package com.minidb.buffer;

import com.minidb.storage.Page;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolBenchmarkTest {

    private static final int TOTAL_ACCESS = 100_000;
    private static final int NUM_PAGES = 200;
    private static final int CAPACITY = 20;

    @Test
    void lruVsFifoBenchmark() {
        long seed = 42L;

        // LRU 压测
        double lruHitRate = runBenchmark(BufferPoolWithStrategy.Strategy.LRU, seed);
        long lruTime = timeBenchmark(BufferPoolWithStrategy.Strategy.LRU, seed);

        // FIFO 压测
        double fifoHitRate = runBenchmark(BufferPoolWithStrategy.Strategy.FIFO, seed);
        long fifoTime = timeBenchmark(BufferPoolWithStrategy.Strategy.FIFO, seed);

        // 输出对比表（进报告）
        System.out.println("=== LRU vs FIFO 压测对比 ===");
        System.out.printf("访问次数: %d, 页面数: %d, 容量: %d%n", TOTAL_ACCESS, NUM_PAGES, CAPACITY);
        System.out.printf("LRU 命中率: %.4f, 耗时: %d ms%n", lruHitRate, lruTime);
        System.out.printf("FIFO 命中率: %.4f, 耗时: %d ms%n", fifoHitRate, fifoTime);
        System.out.printf("命中率差值: %.4f%n", lruHitRate - fifoHitRate);

        // 断言：LRU 命中率 >= FIFO（局部性序列下）
        assertTrue(lruHitRate >= fifoHitRate,
                String.format("LRU 命中率 (%.4f) 应 >= FIFO (%.4f)", lruHitRate, fifoHitRate));
    }

    private double runBenchmark(BufferPoolWithStrategy.Strategy strategy, long seed) {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(CAPACITY, strategy);
        // 预创建所有页面
        for (int i = 0; i < NUM_PAGES; i++) {
            pool.newPage("bench");
        }
        // 重置统计
        long startHits = pool.stats().hits();
        long startMisses = pool.stats().misses();

        Random random = new Random(seed);
        for (int i = 0; i < TOTAL_ACCESS; i++) {
            int pageId = random.nextInt(NUM_PAGES);
            pool.getPage("bench", pageId);
        }

        BufferPoolStats stats = pool.stats();
        long hits = stats.hits() - startHits;
        long misses = stats.misses() - startMisses;
        return (double) hits / (hits + misses);
    }

    private long timeBenchmark(BufferPoolWithStrategy.Strategy strategy, long seed) {
        BufferPoolWithStrategy pool = new BufferPoolWithStrategy(CAPACITY, strategy);
        for (int i = 0; i < NUM_PAGES; i++) {
            pool.newPage("bench");
        }

        Random random = new Random(seed);
        long start = System.currentTimeMillis();
        for (int i = 0; i < TOTAL_ACCESS; i++) {
            int pageId = random.nextInt(NUM_PAGES);
            pool.getPage("bench", pageId);
        }
        return System.currentTimeMillis() - start;
    }

    @Test
    void localitySequenceLruBeatsFifo() {
        // 局部性序列：连续访问同一页面
        BufferPoolWithStrategy lru = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.LRU);
        BufferPoolWithStrategy fifo = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.FIFO);

        for (int i = 0; i < 5; i++) {
            lru.newPage("test");
            fifo.newPage("test");
        }

        // 局部性访问：A A A B B B C C C
        int[] sequence = {0, 0, 0, 1, 1, 1, 2, 2, 2, 0, 0, 1, 1, 2, 2};
        for (int pageId : sequence) {
            lru.getPage("test", pageId);
            fifo.getPage("test", pageId);
        }

        double lruRate = lru.stats().hitRate();
        double fifoRate = fifo.stats().hitRate();

        assertTrue(lruRate >= fifoRate,
                String.format("局部性序列下 LRU (%.4f) 应 >= FIFO (%.4f)", lruRate, fifoRate));
    }
}