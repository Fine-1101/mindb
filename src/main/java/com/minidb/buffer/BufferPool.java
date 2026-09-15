package com.minidb.buffer;

import com.minidb.storage.Page;

/** 缓冲池：LRU/FIFO 替换策略可切换，命中率可统计，供执行引擎统一调用。 */
public interface BufferPool {
    Page getPage(String tableName, int pageId);

    Page newPage(String tableName);

    /** 所有脏页落盘。 */
    void flushAll();

    BufferPoolStats stats();

    /** 精确刷一页：若该页在缓存且为脏页，则写盘。 */
    void flushPage(String tableName, int pageId);

    /** 释放一页：标记页为空闲（槽数清 0），后续 newPage 可复用。 */
    void freePage(String tableName, int pageId);
}