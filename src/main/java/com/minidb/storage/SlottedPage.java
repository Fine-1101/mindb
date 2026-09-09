package com.minidb.storage;

public class SlottedPage implements Page {
    private final int pageId;
    private final byte[] data;
    private int slotCount;
    private int freeStart;
    private int freeEnd;
    private boolean dirty;
    private static final int HEADER_SIZE = 4;

    public SlottedPage(int pageId) {
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        this.slotCount = 0;
        this.freeStart = HEADER_SIZE;
        this.freeEnd = PAGE_SIZE;
        this.dirty = false;
    }

    /**
     * 从磁盘数据构造 SlottedPage
     */
    public SlottedPage(int pageId, byte[] diskData, int slotCount) {
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        // diskData 包含槽数前缀 + 页体，复制页体（跳过前4字节）
        int dataOffset = DISK_PREFIX_SIZE;
        int copyLen = Math.min(diskData.length - dataOffset, PAGE_SIZE);
        System.arraycopy(diskData, dataOffset, this.data, 0, copyLen);
        this.slotCount = slotCount;
        // 重建 freeStart/freeEnd
        rebuildFreeSpace();
        this.dirty = false;
    }

    private void rebuildFreeSpace() {
        // 根据 slotCount 计算 freeStart
        this.freeStart = HEADER_SIZE + slotCount * SLOT_ENTRY_SIZE;

        // 找所有有效行的最小偏移（尾部生长）
        int minRowStart = PAGE_SIZE;
        boolean hasValidRow = false;
        for (int i = 0; i < slotCount; i++) {
            int slotOffset = HEADER_SIZE + i * SLOT_ENTRY_SIZE;
            int rowOffset = readShort(slotOffset);
            if (rowOffset >= 0) {
                hasValidRow = true;
                if (rowOffset < minRowStart) {
                    minRowStart = rowOffset;
                }
            }
        }
        if (hasValidRow) {
            this.freeEnd = minRowStart;
        } else {
            this.freeEnd = PAGE_SIZE;
        }

        // 确保 freeStart 不超过 freeEnd
        if (this.freeStart > this.freeEnd) {
            this.freeStart = this.freeEnd;
        }
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
        dirty = true;

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
        dirty = true;
    }

    @Override
    public int freeSpace() {
        // freeSpace = freeEnd - freeStart + HEADER_SIZE
        // 初始: 4096 - 4 + 4 = 4096
        // 插入后精确追踪
        return freeEnd - freeStart + HEADER_SIZE;
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

    public int getSlotCount() {
        return slotCount;
    }

    /**
     * 获取页体数据（不含槽数前缀），用于写盘
     */
    public byte[] getPageData() {
        return data;
    }

    private void writeShort(int offset, short value) {
        data[offset] = (byte) (value >> 8);
        data[offset + 1] = (byte) value;
    }

    private short readShort(int offset) {
        return (short) ((data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF));
    }
}