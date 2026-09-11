package com.minidb.buffer;

import com.minidb.storage.Page;
import com.minidb.storage.SlottedPage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DiskBufferPool implements BufferPool {
    private static final String DATA_DIR = "data";
    private final int capacity;
    private final Map<String, TableFile> tableFiles;
    private final Map<String, Page> cache;
    private final List<String> accessOrder;
    private final AtomicInteger nextPageId;
    private final AtomicLong hits;
    private final AtomicLong misses;
    private boolean closed;
    private final AtomicLong evictions;
    private final BufferLogger logger;

    public DiskBufferPool(int capacity) {
        this(capacity, null);
    }

    public DiskBufferPool(int capacity,BufferLogger logger) {
        this.capacity = capacity;
        this.tableFiles = new ConcurrentHashMap<>();
        this.cache = new ConcurrentHashMap<>();
        this.accessOrder = Collections.synchronizedList(new ArrayList<>());
        this.nextPageId = new AtomicInteger(0);
        this.hits = new AtomicLong(0);
        this.misses = new AtomicLong(0);
        this.closed = false;
        this.evictions = new AtomicLong(0);
        this.logger = logger;

        try {
            Files.createDirectories(Paths.get(DATA_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directory", e);
        }
    }

    @Override
    public Page getPage(String tableName, int pageId) {
        if (closed) {
            throw new IllegalStateException("BufferPool is closed");
        }

        String key = tableName + ":" + pageId;
        if (cache.containsKey(key)) {
            hits.incrementAndGet();
            if (logger != null) {
                logger.logHit(tableName, pageId);
            }
            synchronized (accessOrder) {
                accessOrder.remove(key);
                accessOrder.add(key);
            }
            return cache.get(key);
        }

        misses.incrementAndGet();
        if (logger != null) {
            logger.logMiss(tableName, pageId);
        }
        TableFile tf = getTableFile(tableName);

        // 3. 检查 pageId 是否存在于文件中
        if (pageId < tf.getPageCount()) {
            SlottedPage page = tf.loadPage(pageId);
            if (page != null) {
                addToCache(tableName, pageId, page);  // ← 加 tableName
                return page;
            }
        }

        // 4. pageId 不存在于文件中 -> 返回 null（兜底，调用方应保证 pageId 合法）
        return null;
    }

    @Override
    public Page newPage(String tableName) {
        if (closed) {
            throw new IllegalStateException("BufferPool is closed");
        }

        TableFile tf = getTableFile(tableName);
        int pageId = tf.getPageCount();
        SlottedPage page = new SlottedPage(pageId);
        page.markDirty();

        tf.appendPage(page);
        addToCache(tableName, pageId, page);

        return page;
    }

    private void addToCache(String tableName, int pageId, Page page) {
        if (cache.size() >= capacity) {
            evict(tableName);
        }
        String key = tableName + ":" + pageId;
        cache.put(key, page);
        synchronized (accessOrder) {
            accessOrder.add(key);
        }
    }

    private void evict(String tableName) {
        if (cache.isEmpty()) {
            return;
        }

        String victimKey;
        synchronized (accessOrder) {
            victimKey = accessOrder.remove(0);
        }

        Page victim = cache.remove(victimKey);
        if (victim != null && victim.isDirty()) {
            writePage(victimKey.substring(0, victimKey.indexOf(':')), victim);
        }
        evictions.incrementAndGet();
        if (logger != null) {
            int sep = victimKey.indexOf(':');
            logger.logEvict(victimKey.substring(0, sep),
                    Integer.parseInt(victimKey.substring(sep + 1)));
        }
    }

    private void writePage(String tableName, Page page) {
        if (!(page instanceof SlottedPage)) {
            return;
        }
        // 复合缓存键后按表定向写盘：原实现遍历所有表文件试写，多表同号页会写错文件
        if (getTableFile(tableName).writePage((SlottedPage) page)) {
            page.markClean();
        }
    }

    @Override
    public void flushAll() {
        if (closed) {
            return;
        }

        for (Map.Entry<String, Page> entry : cache.entrySet()) {
            if (entry.getValue().isDirty()) {
                String key = entry.getKey();
                writePage(key.substring(0, key.indexOf(':')), entry.getValue());
                entry.getValue().markClean();
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

    private TableFile getTableFile(String tableName) {
        return tableFiles.computeIfAbsent(tableName, k -> new TableFile(tableName));
    }

    public int getTablePageCount(String tableName) {
        // computeIfAbsent：重启后的新 pool tableFiles 为空，TableFile 构造器会从磁盘文件长度恢复 pageCount
        return getTableFile(tableName).getPageCount();
    }

    public Set<String> getCacheContent() {
        return new HashSet<>(cache.keySet());
    }

    /**
     * 关闭缓冲池，释放所有文件资源
     */
    public void close() {
        if (closed) {
            return;
        }
        // 必须先刷盘再置 closed：flushAll 开头有 if(closed) return，顺序反了会导致脏页永远不落盘
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

    private static class TableFile {
        private final String tableName;
        private final Path filePath;
        private RandomAccessFile raf;
        private int pageCount;

        TableFile(String tableName) {
            this.tableName = tableName;
            this.filePath = Paths.get(DATA_DIR, tableName + ".dat");
            try {
                if (Files.exists(filePath)) {
                    this.raf = new RandomAccessFile(filePath.toFile(), "rw");
                    this.pageCount = (int) (raf.length() / (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE));
                } else {
                    this.raf = new RandomAccessFile(filePath.toFile(), "rw");
                    this.pageCount = 0;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to open table file: " + tableName, e);
            }
        }

        int getPageCount() {
            return pageCount;
        }

        SlottedPage loadPage(int pageId) {
            try {
                long offset = (long) pageId * (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE);
                raf.seek(offset);

                byte[] prefix = new byte[Page.DISK_PREFIX_SIZE];
                int read = raf.read(prefix);
                if (read != Page.DISK_PREFIX_SIZE) {
                    return null;
                }
                int slotCount = ((prefix[0] & 0xFF) << 24) |
                        ((prefix[1] & 0xFF) << 16) |
                        ((prefix[2] & 0xFF) << 8) |
                        (prefix[3] & 0xFF);

                byte[] pageData = new byte[Page.PAGE_SIZE];
                read = raf.read(pageData);
                if (read != Page.PAGE_SIZE) {
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
                if (pageId >= pageCount) {
                    return false;
                }

                long offset = (long) pageId * (Page.PAGE_SIZE + Page.DISK_PREFIX_SIZE);
                raf.seek(offset);

                int slotCount = page.slotCount();
                byte[] prefix = new byte[Page.DISK_PREFIX_SIZE];
                prefix[0] = (byte) (slotCount >> 24);
                prefix[1] = (byte) (slotCount >> 16);
                prefix[2] = (byte) (slotCount >> 8);
                prefix[3] = (byte) slotCount;
                raf.write(prefix);
                raf.write(page.getPageData());

                return true;
            } catch (IOException e) {
                throw new RuntimeException("Failed to write page: " + page.pageId(), e);
            }
        }

        void appendPage(SlottedPage page) {
            try {
                raf.seek(raf.length());

                int slotCount = page.slotCount();
                byte[] prefix = new byte[Page.DISK_PREFIX_SIZE];
                prefix[0] = (byte) (slotCount >> 24);
                prefix[1] = (byte) (slotCount >> 16);
                prefix[2] = (byte) (slotCount >> 8);
                prefix[3] = (byte) slotCount;
                raf.write(prefix);
                raf.write(page.getPageData());

                pageCount++;
            } catch (IOException e) {
                throw new RuntimeException("Failed to append page", e);
            }
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