package com.minidb.buffer;

import com.minidb.storage.Page;
import com.minidb.storage.SlottedPage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 磁盘缓冲池：每表一个 data/&lt;table&gt;.dat 文件，页定长 [4B 槽数][PAGE_SIZE 页体]。
 *
 * <p>缓存按 (表名, pageId) 复合键分桶——各表 pageId 独立编号，全局 int 键会撞号；
 * 淘汰/flushAll 仅写脏页且按页所属表直达写回。pageId 存在于文件时 getPage 永不返回 null
 * （未命中从盘重载）。
 */
public class DiskBufferPool implements BufferPool {
    private static final String DATA_DIR = "data";
    private final int capacity;
    private final Map<String, TableFile> tableFiles;
    /** 缓存：表名 → (pageId → 页)。页 ID 每表独立编号，必须按表分桶。 */
    private final Map<String, Map<Integer, Page>> cache;
    /** LRU 淘汰序：复合键 tableName#pageId，与 cache 一致。 */
    private final List<String> accessOrder;
    private final AtomicLong hits;
    private final AtomicLong misses;
    private boolean closed;

    public DiskBufferPool(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.tableFiles = new ConcurrentHashMap<>();
        this.cache = new ConcurrentHashMap<>();
        this.accessOrder = Collections.synchronizedList(new ArrayList<>());
        this.hits = new AtomicLong(0);
        this.misses = new AtomicLong(0);
        this.closed = false;

        try {
            Files.createDirectories(Paths.get(DATA_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directory", e);
        }
    }

    @Override
    public Page getPage(String tableName, int pageId) {
        ensureOpen();
        String key = cacheKey(tableName, pageId);

        // 1. 先查缓存
        Page cached = cache.getOrDefault(tableName, Map.of()).get(pageId);
        if (cached != null) {
            hits.incrementAndGet();
            touch(key);
            return cached;
        }

        // 2. 缓存未命中 → 从文件加载
        misses.incrementAndGet();
        SlottedPage loaded = getTableFile(tableName).loadPage(pageId);
        if (loaded == null) {
            // pageId 超出文件页数（调用方应保证合法），兜底返回 null
            return null;
        }
        addToCache(tableName, key, loaded);
        return loaded;
    }

    @Override
    public Page newPage(String tableName) {
        ensureOpen();
        TableFile tf = getTableFile(tableName);
        int pageId = tf.getPageCount();
        SlottedPage page = new SlottedPage(pageId);
        page.markDirty();

        tf.appendPage(page);
        addToCache(tableName, cacheKey(tableName, pageId), page);

        return page;
    }

    private void addToCache(String tableName, String key, Page page) {
        if (cache.size() >= capacity) {
            evict();
        }
        cache.computeIfAbsent(tableName, k -> new HashMap<>()).put(page.pageId(), page);
        synchronized (accessOrder) {
            accessOrder.add(key);
        }
    }

    private void touch(String key) {
        if (accessOrder.remove(key)) {
            synchronized (accessOrder) {
                accessOrder.add(key);
            }
        }
    }

    private void evict() {
        String victimKey;
        synchronized (accessOrder) {
            if (accessOrder.isEmpty()) {
                return;
            }
            victimKey = accessOrder.remove(0);
        }
        int sep = victimKey.indexOf('#');
        String tableName = victimKey.substring(0, sep);
        int pageId = Integer.parseInt(victimKey.substring(sep + 1));

        Map<Integer, Page> pages = cache.get(tableName);
        Page victim = pages == null ? null : pages.remove(pageId);
        if (pages != null && pages.isEmpty()) {
            cache.remove(tableName, pages);
        }
        if (victim != null && victim.isDirty()) {
            writePage(tableName, victim);
        }
    }

    /** 按页所属表直达写回。 */
    private void writePage(String tableName, Page page) {
        if (page instanceof SlottedPage sp) {
            getTableFile(tableName).writePage(sp);
            sp.markClean();
        }
    }

    @Override
    public void flushAll() {
        if (closed) {
            return;
        }
        for (Map.Entry<String, Map<Integer, Page>> entry : cache.entrySet()) {
            for (Page page : entry.getValue().values()) {
                if (page.isDirty()) {
                    writePage(entry.getKey(), page);
                }
            }
        }
        for (TableFile tf : tableFiles.values()) {
            tf.flush();
        }
    }

    @Override
    public BufferPoolStats stats() {
        return new BufferPoolStats(hits.get(), misses.get());
    }

    /** 表的磁盘页数：已打开的表用记录值，否则按文件长度推算（重启恢复入口，不产生打开副作用）。 */
    public int getTablePageCount(String tableName) {
        TableFile tf = tableFiles.get(tableName);
        if (tf != null) {
            return tf.getPageCount();
        }
        Path file = Paths.get(DATA_DIR, tableName + ".dat");
        if (!Files.exists(file)) {
            return 0;
        }
        return (int) (file.toFile().length() / (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE));
    }

    public Set<String> getCacheContent() {
        synchronized (accessOrder) {
            return new HashSet<>(accessOrder);
        }
    }

    /** 关闭缓冲池：先刷脏再释放文件资源。 */
    public void close() {
        if (closed) {
            return;
        }
        flushAll();
        closed = true;
        for (TableFile tf : tableFiles.values()) {
            tf.close();
        }
        tableFiles.clear();
        cache.clear();
        synchronized (accessOrder) {
            accessOrder.clear();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("BufferPool is closed");
        }
    }

    private TableFile getTableFile(String tableName) {
        return tableFiles.computeIfAbsent(tableName, k -> new TableFile(tableName));
    }

    private static String cacheKey(String tableName, int pageId) {
        return tableName + "#" + pageId;
    }

    /** 表文件：页定长 [4B 槽数][PAGE_SIZE 页体]，pageId 偏移 pageId*(PAGE_SIZE+4)。 */
    private static class TableFile {
        private final String tableName;
        private final Path filePath;
        private final RandomAccessFile raf;
        private int pageCount;

        TableFile(String tableName) {
            this.tableName = tableName;
            this.filePath = Paths.get(DATA_DIR, tableName + ".dat");
            try {
                this.raf = new RandomAccessFile(filePath.toFile(), "rw");
                this.pageCount = (int) (raf.length() / (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE));
            } catch (IOException e) {
                throw new RuntimeException("Failed to open table file: " + tableName, e);
            }
        }

        int getPageCount() {
            return pageCount;
        }

        SlottedPage loadPage(int pageId) {
            try {
                if (pageId < 0 || pageId >= pageCount) {
                    return null;
                }
                long offset = (long) pageId * (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE);
                raf.seek(offset);

                byte[] prefix = new byte[Page.DISK_PREFIX_SIZE];
                if (raf.read(prefix) != Page.DISK_PREFIX_SIZE) {
                    return null;
                }
                int slotCount = ((prefix[0] & 0xFF) << 24) |
                        ((prefix[1] & 0xFF) << 16) |
                        ((prefix[2] & 0xFF) << 8) |
                        (prefix[3] & 0xFF);

                byte[] pageData = new byte[Page.PAGE_SIZE];
                if (raf.read(pageData) != Page.PAGE_SIZE) {
                    return null;
                }

                byte[] diskData = new byte[Page.DISK_PREFIX_SIZE + Page.PAGE_SIZE];
                System.arraycopy(prefix, 0, diskData, 0, Page.DISK_PREFIX_SIZE);
                System.arraycopy(pageData, 0, diskData, Page.DISK_PREFIX_SIZE, Page.PAGE_SIZE);

                return new SlottedPage(pageId, diskData, slotCount);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load page: " + pageId, e);
            }
        }

        boolean writePage(SlottedPage page) {
            try {
                int pageId = page.pageId();
                if (pageId < 0 || pageId >= pageCount) {
                    return false;
                }
                long offset = (long) pageId * (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE);
                raf.seek(offset);
                writePageBytes(page);
                return true;
            } catch (IOException e) {
                throw new RuntimeException("Failed to write page: " + page.pageId(), e);
            }
        }

        void appendPage(SlottedPage page) {
            try {
                raf.seek(raf.length());
                writePageBytes(page);
                pageCount++;
            } catch (IOException e) {
                throw new RuntimeException("Failed to append page", e);
            }
        }

        private void writePageBytes(SlottedPage page) throws IOException {
            int slotCount = page.slotCount();
            byte[] prefix = new byte[Page.DISK_PREFIX_SIZE];
            prefix[0] = (byte) (slotCount >> 24);
            prefix[1] = (byte) (slotCount >> 16);
            prefix[2] = (byte) (slotCount >> 8);
            prefix[3] = (byte) slotCount;
            raf.write(prefix);
            raf.write(page.getPageData());
        }

        void flush() {
            try {
                raf.getFD().sync();
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush table file: " + tableName, e);
            }
        }

        void close() {
            try {
                if (raf != null) {
                    raf.close();
                }
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
