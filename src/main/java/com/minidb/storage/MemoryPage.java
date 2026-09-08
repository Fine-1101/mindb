package com.minidb.storage;

import java.util.Arrays;

/** 纯内存页（D1 交付）。freeSpace/删除语义按 Page 契约，与 SlottedPage 对拍一致。 */
public class MemoryPage implements Page {

    /** 槽数组容量：按契约公式每行至少 1B+4B，最多约 819 行，槽数组不会触顶。 */
    private static final int MAX_SLOTS = 1024;

    private final int pageId;
    private final byte[][] slots;
    private int usedSpace;
    private int nextSlot;

    public MemoryPage(int pageId) {
        this.pageId = pageId;
        this.slots = new byte[MAX_SLOTS][];
        this.usedSpace = 0;
        this.nextSlot = 0;
    }

    @Override
    public int pageId() {
        return pageId;
    }

    @Override
    public int insertRow(byte[] row) {
        if (row == null) {
            return -1;
        }

        int cost = row.length + SLOT_ENTRY_SIZE;
        if (freeSpace() < cost) {
            return -1;
        }

        slots[nextSlot] = Arrays.copyOf(row, row.length);
        usedSpace += cost;
        return nextSlot++;
    }

    @Override
    public byte[] readRow(int slot) {
        if (slot < 0 || slot >= nextSlot) {
            return null;
        }
        byte[] row = slots[slot];
        if (row == null) {
            return null;
        }
        return Arrays.copyOf(row, row.length);
    }

    /** 标记删除：槽置空、readRow 返回 null；按契约不回收 freeSpace。 */
    @Override
    public void deleteRow(int slot) {
        if (slot < 0 || slot >= nextSlot) {
            return;
        }
        slots[slot] = null;
    }

    @Override
    public int freeSpace() {
        return PAGE_SIZE - usedSpace;
    }

    public int getUsedSpace() {
        return usedSpace;
    }

    public int getRowCount() {
        int count = 0;
        for (int i = 0; i < nextSlot; i++) {
            if (slots[i] != null) {
                count++;
            }
        }
        return count;
    }
}
