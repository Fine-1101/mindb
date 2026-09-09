package com.minidb.storage;

/** 页接口：页头/槽/行数据/空闲空间的统一契约。 */
public interface Page {
    /** 页大小常量 */
    int PAGE_SIZE = 4096;
    /** 槽目录项大小（4字节：2字节偏移 + 2字节长度） */
    int SLOT_ENTRY_SIZE = 4;
    /** 磁盘页前缀大小（4字节：槽数） */
    int DISK_PREFIX_SIZE = 4;

    int pageId();

    /** 插入一行，返回槽号；空闲空间不足返回 -1（由调用方换页）。 */
    int insertRow(byte[] row);

    byte[] readRow(int slot);

    /** 删除行：标记删除，readRow 返回 null，freeSpace 不变 */
    void deleteRow(int slot);

    int freeSpace();

    /** 页是否脏（被修改过） */
    boolean isDirty();

    /** 标记页为干净（通常由 BufferPool 在写盘后调用） */
    void markClean();

    /** 标记页为脏（修改性操作自动调用） */
    void markDirty();
}