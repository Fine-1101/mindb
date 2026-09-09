package com.minidb.storage;

import java.util.Arrays;

public class MemoryPage implements Page {
    public static final int PAGE_SIZE = 4096;

    private final int pageId;
    private final byte[][] slots;
    private int usedSpace;
    private int nextSlot;
    private static final int MAX_SLOTS = 1024;
    private boolean dirty;

    public MemoryPage(int pageId) {
        this.pageId = pageId;
        this.slots = new byte[MAX_SLOTS][];
        this.usedSpace = 0;
        this.nextSlot = 0;
        this.dirty = false;
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

        int rowSize = row.length;
        if (freeSpace() < rowSize) {
            return -1;
        }

        if (nextSlot >= MAX_SLOTS) {
            return -1;
        }

        slots[nextSlot] = Arrays.copyOf(row, row.length);
        usedSpace += rowSize;
        dirty = true;
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

    @Override
    public void deleteRow(int slot) {
        if (slot < 0 || slot >= nextSlot) {
            return;
        }
        if (slots[slot] != null) {
            slots[slot] = null;
            dirty = true;
        }
    }

    @Override
    public int freeSpace() {
        return PAGE_SIZE - usedSpace;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void markClean() {
        this.dirty = false;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
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