package com.minidb.storage;

/**
 * 页接口：页头/槽/行数据/空闲空间的统一契约。
 *
 * <p>freeSpace 统一公式（两实现对拍一致）：
 * freeSpace() = PAGE_SIZE − Σ(每行字节 + 槽目录项 SLOT_ENTRY_SIZE)，页头不计，
 * 新页初始 freeSpace() == PAGE_SIZE。
 *
 * <p>删除为标记式：deleteRow 之后 readRow 返回 null、freeSpace 不变
 * （页内压缩/空间复用是 Extension 级）。
 */
public interface Page {
    int PAGE_SIZE = 4096;

    /** 槽目录项大小：偏移 2B + 长度 2B（SlottedPage 布局同此）。 */
    int SLOT_ENTRY_SIZE = 4;

    int pageId();

    /** 插入一行，返回槽号；空闲空间不足（行字节+槽目录项放不下）返回 -1（由调用方换页）。 */
    int insertRow(byte[] row);

    byte[] readRow(int slot);

    /** 标记删除指定槽：之后 readRow(slot) 返回 null，freeSpace 不变。 */
    void deleteRow(int slot);

    int freeSpace();

    /** 是否为脏页：insertRow/deleteRow 自动置脏，新建/从磁盘载入后为干净。 */
    boolean isDirty();

    /** 清除脏标记（flush/淘汰写盘后调用）。 */
    void clearDirty();
}
