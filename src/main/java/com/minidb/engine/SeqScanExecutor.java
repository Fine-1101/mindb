package com.minidb.engine;

import com.minidb.buffer.BufferPool;
import com.minidb.catalog.ColumnDef;
import com.minidb.common.MiniDbException;
import com.minidb.storage.Page;

import java.util.List;

/**
 * 顺序扫描执行器：逐页逐槽 readRow，null 跳过（deleteRow 语义）。
 * RowEncoder.decode 出 Object[]。
 */
public class SeqScanExecutor implements Executor {

    private final BufferPool pool;
    private final String tableName;
    private final List<Integer> pageIds;
    private final List<ColumnDef> columns;

    private int currentPageIdx;
    private Page currentPage;
    private int currentSlot;
    private int maxSlot;

    public SeqScanExecutor(BufferPool pool, String tableName,
                           List<Integer> pageIds, List<ColumnDef> columns) {
        this.pool = pool;
        this.tableName = tableName;
        this.pageIds = pageIds;
        this.columns = columns;
    }

    @Override
    public void open() throws MiniDbException {
        currentPageIdx = 0;
        currentSlot = 0;
        if (!pageIds.isEmpty()) {
            currentPage = pool.getPage(tableName, pageIds.get(0));
            if (currentPage == null) {
                throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                        "页不存在: table=" + tableName + ", pageId=" + pageIds.get(0));
            }
        } else {
            currentPage = null;
        }
    }

    @Override
    public Object[] next() throws MiniDbException {
        while (currentPage != null) {
            // 扫描当前页的所有槽
            while (currentSlot < getMaxSlot()) {
                byte[] raw = currentPage.readRow(currentSlot);
                currentSlot++;
                if (raw != null) {
                    return RowEncoder.decode(columns, raw);
                }
                // raw == null → 已删除的槽，跳过
            }
            // 当前页扫描完毕，移到下一页
            currentPageIdx++;
            currentSlot = 0;
            if (currentPageIdx < pageIds.size()) {
                currentPage = pool.getPage(tableName, pageIds.get(currentPageIdx));
                if (currentPage == null) {
                    throw new MiniDbException(MiniDbException.Phase.PLAN, null,
                            "页不存在: table=" + tableName + ", pageId=" + pageIds.get(currentPageIdx));
                }
            } else {
                currentPage = null;
            }
        }
        return null;  // 全部扫描完毕
    }

    @Override
    public void close() {
        // 内存实现无需释放资源
    }

    /** 获取当前页的最大槽号（nextSlot），MemoryPage 暴露此信息。 */
    private int getMaxSlot() {
        if (currentPage instanceof com.minidb.storage.MemoryPage mp) {
            // 通过反射或直接方式获取 nextSlot
            // MemoryPage 没有公开的 nextSlot getter，但我们可以通过 getRowCount 和 slots 数组长度推断
            // 实际上我们需要遍历到 nextSlot 而不是 PAGE_SIZE
            // 使用 getUsedSpace 来推断也不可靠
            // 最安全的方式：给 MemoryPage 加一个 getNextSlot() 方法
            return mp.getNextSlot();
        }
        return 0;
    }
}
