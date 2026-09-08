package com.minidb.buffer;

/** 缓存命中统计。 */
public record BufferPoolStats(long hits, long misses) {
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
