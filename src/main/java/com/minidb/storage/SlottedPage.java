package com.minidb.storage;

/**
 * 槽式页（内存版）：槽目录从头向后生长，行数据从页尾向前生长。
 *
 * <p>槽数保存在 Java 字段中，不占页内空间（与 MemoryPage 的 freeSpace 语义物理一致：
 * 初始 == PAGE_SIZE，减量 == 行字节 + 槽目录项）；D3 磁盘化时页头（槽数）放文件层。
 */
public class SlottedPage implements Page {
    private final int pageId;
    private final byte[] data;
    private int slotCount;
    private int freeStart;
    private int freeEnd;

    public SlottedPage(int pageId) {
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        this.slotCount = 0;
        this.freeStart = 0;
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
        int requiredSpace = rowSize + SLOT_ENTRY_SIZE;
        if (freeSpace() < requiredSpace) {
            return -1;
        }

        int rowStart = freeEnd - rowSize;
        System.arraycopy(row, 0, data, rowStart, rowSize);

        writeShort(freeStart, (short) rowStart);
        writeShort(freeStart + 2, (short) rowSize);

        slotCount++;
        freeStart += SLOT_ENTRY_SIZE;
        freeEnd = rowStart;

        return slotCount - 1;
    }

    @Override
    public byte[] readRow(int slot) {
        if (slot < 0 || slot >= slotCount) {
            return null;
        }

        int slotOffset = slot * SLOT_ENTRY_SIZE;
        int rowOffset = readShort(slotOffset);
        int rowLength = readShort(slotOffset + 2);

        if (rowOffset < 0 || rowLength <= 0 || rowOffset + rowLength > PAGE_SIZE) {
            return null;
        }

        byte[] row = new byte[rowLength];
        System.arraycopy(data, rowOffset, row, 0, rowLength);
        return row;
    }

    /** 标记删除：槽目录项置无效（偏移 -1），readRow 之后返回 null，freeSpace 不变。 */
    @Override
    public void deleteRow(int slot) {
        if (slot < 0 || slot >= slotCount) {
            return;
        }

        int slotOffset = slot * SLOT_ENTRY_SIZE;
        writeShort(slotOffset, (short) -1);
        writeShort(slotOffset + 2, (short) 0);
    }

    @Override
    public int freeSpace() {
        return freeEnd - freeStart;
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
