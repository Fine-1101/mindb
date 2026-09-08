package com.minidb.storage;

/** 页接口：页头/槽/行数据/空闲空间的统一契约。 */
public interface Page {
    int pageId();

    /** 插入一行，返回槽号；空闲空间不足返回 -1（由调用方换页）。 */
    int insertRow(byte[] row);

    byte[] readRow(int slot);

    int freeSpace();
}
