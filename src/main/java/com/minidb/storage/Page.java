package com.minidb.storage;

/** 页接口 */
public interface Page {
    int PAGE_SIZE = 4096;
    int SLOT_ENTRY_SIZE = 4;
    int DISK_PREFIX_SIZE = 4;

    int pageId();

    int insertRow(byte[] row);

    byte[] readRow(int slot);

    void deleteRow(int slot);

    int freeSpace();

    int slotCount();

    boolean isDirty();

    void markClean();

    void markDirty();
}