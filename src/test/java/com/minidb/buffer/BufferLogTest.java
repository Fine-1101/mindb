package com.minidb.buffer;

import com.minidb.storage.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class BufferLogTest {

    private BufferLogger logger;
    private BufferPoolWithStrategy pool;

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(Paths.get("buffer.log"));
        logger = new BufferLogger();
        pool = new BufferPoolWithStrategy(3, BufferPoolWithStrategy.Strategy.LRU, logger);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.flushAll();
        }
        if (logger != null) {
            logger.close();
        }
        try {
            Files.deleteIfExists(Paths.get("buffer.log"));
        } catch (IOException e) {
            // ignore
        }
    }

    @Test
    void logLineCountMatchesHitsAndMisses() {
        // 固定访问序列
        Page a = pool.newPage("test");
        Page b = pool.newPage("test");
        Page c = pool.newPage("test");

        pool.getPage("test", a.pageId());  // hit
        pool.getPage("test", b.pageId());  // hit
        pool.getPage("test", c.pageId());  // hit
        pool.getPage("test", 999);         // miss
        pool.getPage("test", 888);         // miss

        // 日志行数 == 命中 + 未命中次数
        int hitCount = logger.getHitCount();
        int missCount = logger.getMissCount();
        int totalEvents = hitCount + missCount;

        assertEquals(3, hitCount, "命中次数应为 3");
        assertEquals(2, missCount, "未命中次数应为 2");
        assertEquals(5, totalEvents, "日志事件总数应为 5");
        assertEquals(5, logger.getLines().size(), "日志行数应为 5");
    }

    @Test
    void evictLogCountMatchesEvictions() {
        // 容量 3，创建 5 个页面触发淘汰
        pool.newPage("test");  // 0
        pool.newPage("test");  // 1
        pool.newPage("test");  // 2
        pool.newPage("test");  // 3 - 触发淘汰
        pool.newPage("test");  // 4 - 触发淘汰

        // 淘汰行数 == 统计器淘汰数
        int evictCount = logger.getEvictCount();
        long evictions = pool.getEvictions();

        assertEquals(2, evictCount, "淘汰日志应为 2 条");
        assertEquals(2, evictions, "统计器淘汰数应为 2");
        assertEquals(evictCount, evictions, "淘汰日志行数应等于统计器淘汰数");
    }

    @Test
    void loggerRecordsCorrectEventTypes() {
        Page a = pool.newPage("test");
        pool.getPage("test", a.pageId());  // hit
        pool.getPage("test", 999);         // miss

        var lines = logger.getLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("HIT")), "应包含 HIT 事件");
        assertTrue(lines.stream().anyMatch(l -> l.contains("MISS")), "应包含 MISS 事件");
    }
}