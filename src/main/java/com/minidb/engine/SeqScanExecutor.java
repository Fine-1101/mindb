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
            while (currentSlot < currentPage.slotCount()) {
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
}
