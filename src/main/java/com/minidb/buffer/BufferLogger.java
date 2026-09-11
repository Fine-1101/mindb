package com.minidb.buffer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 缓存日志：记录命中/未命中/淘汰事件。
 * 格式：[时间戳] [事件类型] table=xxx pageId=xxx
 * 事件类型：HIT / MISS / EVICT
 */
public class BufferLogger implements AutoCloseable {
    public enum EventType { HIT, MISS, EVICT }

    private static final String LOG_FILE = "buffer.log";
    private final List<String> lines;
    private final BufferedWriter writer;
    private boolean closed;

    public BufferLogger() {
        this.lines = new ArrayList<>();
        this.closed = false;
        try {
            Path path = Paths.get(LOG_FILE);
            Files.createDirectories(path.getParent() != null ? path.getParent() : Paths.get("."));
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open buffer.log", e);
        }
    }

    public void logHit(String tableName, int pageId) {
        log(EventType.HIT, tableName, pageId);
    }

    public void logMiss(String tableName, int pageId) {
        log(EventType.MISS, tableName, pageId);
    }

    public void logEvict(String tableName, int pageId) {
        log(EventType.EVICT, tableName, pageId);
    }

    private void log(EventType type, String tableName, int pageId) {
        if (closed) {
            return;
        }
        String line = String.format("[%d] %s table=%s pageId=%d",
                System.currentTimeMillis(), type, tableName, pageId);
        lines.add(line);
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // 日志写入失败不影响主流程
        }
    }

    public List<String> getLines() {
        return new ArrayList<>(lines);
    }

    public int getHitCount() {
        return (int) lines.stream().filter(l -> l.contains("HIT")).count();
    }

    public int getMissCount() {
        return (int) lines.stream().filter(l -> l.contains("MISS")).count();
    }

    public int getEvictCount() {
        return (int) lines.stream().filter(l -> l.contains("EVICT")).count();
    }

    public void clear() {
        lines.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.close();
        } catch (IOException e) {
            // ignore
        }
    }
}