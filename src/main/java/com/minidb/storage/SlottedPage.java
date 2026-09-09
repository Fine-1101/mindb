package com.minidb.storage;

public class SlottedPage implements Page {
    private final int pageId;
    private final byte[] data;
    private int slotCount;
    private int freeStart;
    private int freeEnd;
    private static final int HEADER_SIZE = 4;

    public SlottedPage(int pageId) {
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        this.slotCount = 0;
        this.freeStart = HEADER_SIZE;
        this.freeEnd = PAGE_SIZE;
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
        int slotOffset = freeStart;

        int requiredSpace = rowSize + SLOT_ENTRY_SIZE;

        // freeSpace 包含了 HEADER_SIZE 区域，所以实际可用空间要加上 HEADER_SIZE
        if (freeSpace() < requiredSpace) {
            return -1;
        }

        int rowStart = freeEnd - rowSize;
        System.arraycopy(row, 0, data, rowStart, rowSize);

        writeShort(slotOffset, (short) rowStart);
        writeShort(slotOffset + 2, (short) rowSize);

        slotCount++;
        freeStart = slotOffset + SLOT_ENTRY_SIZE;
        freeEnd = rowStart;

        return slotCount - 1;
    }

    @Override
    public byte[] readRow(int slot) {
        if (slot < 0 || slot >= slotCount) {
            return null;
        }

        int slotOffset = HEADER_SIZE + slot * SLOT_ENTRY_SIZE;
        int rowOffset = readShort(slotOffset);
        int rowLength = readShort(slotOffset + 2);

        if (rowOffset < 0 || rowLength <= 0 || rowOffset + rowLength > PAGE_SIZE) {
            return null;
        }

        byte[] row = new byte[rowLength];
        System.arraycopy(data, rowOffset, row, 0, rowLength);
        return row;
    }

    @Override
    public void deleteRow(int slot) {
        if (slot < 0 || slot >= slotCount) {
            return;
        }

        int slotOffset = HEADER_SIZE + slot * SLOT_ENTRY_SIZE;
        writeShort(slotOffset, (short) -1);
        writeShort(slotOffset + 2, (short) 0);
    }

    @Override
    public int freeSpace() {
        // freeSpace = freeEnd - freeStart + HEADER_SIZE
        // 这样初始 freeSpace = 4096 - 4 + 4 = 4096
        return freeEnd - freeStart + HEADER_SIZE;
    }

    public int getSlotCount() {
        return slotCount;
    }

    private void writeShort(int offset, short value) {
        data[offset] = (byte) (value >> 8);
        data[offset + 1] = (byte) value;
    }

    private short readShort(int offset) {
        return (short) ((data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF));
    }
}