package com.minidb.buffer;

import com.minidb.storage.Page;

/** 缓冲池 */
public interface BufferPool {
    Page getPage(String tableName, int pageId);

    Page newPage(String tableName);

    void flushAll();

    BufferPoolStats stats();

    void flushPage(String tableName, int pageId);

    void freePage(String tableName, int pageId);
}