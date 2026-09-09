package com.minidb.catalog;

import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 持久化 Catalog。
 * - data/catalog.dat 存储 TableDef 列表
 * - CREATE TABLE 时追加写
 * - 启动时加载
 * - 重启后重名表报错
 */
public class PersistentCatalog implements Catalog {
    private static final String CATALOG_FILE = "data/catalog.dat";
    private final Map<String, TableDef> tables;
    private boolean loaded;

    public PersistentCatalog() {
        this.tables = new HashMap<>();
        this.loaded = false;
        load();
    }

    @Override
    public void createTable(TableDef def) throws MiniDbException {
        String key = def.tableName().toLowerCase();
        if (tables.containsKey(key)) {
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, null,
                    "表已存在: " + def.tableName());
        }
        tables.put(key, def);
        // 持久化到文件
        save();
    }

    @Override
    public Optional<TableDef> findTable(String name) {
        return Optional.ofNullable(tables.get(name.toLowerCase()));
    }

    @Override
    public Optional<ColumnDef> findColumn(String table, String col) {
        return findTable(table).flatMap(def ->
                def.columns().stream().filter(c -> c.name().equalsIgnoreCase(col)).findFirst());
    }

    @Override
    public DataType getType(String table, String col) throws MiniDbException {
        TableDef def = findTable(table).orElseThrow(() ->
                new MiniDbException(MiniDbException.Phase.SEMANTIC, null, "表不存在: " + table));
        return def.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(col))
                .findFirst()
                .map(ColumnDef::type)
                .orElseThrow(() -> new MiniDbException(MiniDbException.Phase.SEMANTIC, null,
                        "列不存在: " + table + "." + col));
    }

    /**
     * 加载 Catalog 文件
     */
    private void load() {
        Path path = Paths.get(CATALOG_FILE);
        if (!Files.exists(path)) {
            loaded = true;
            return;
        }

        try (DataInputStream dis = new DataInputStream(Files.newInputStream(path))) {
            int tableCount = dis.readInt();
            for (int i = 0; i < tableCount; i++) {
                TableDef def = readTableDef(dis);
                tables.put(def.tableName().toLowerCase(), def);
            }
            loaded = true;
        } catch (IOException e) {
            // 文件损坏或不存在，使用空 catalog
            loaded = true;
        }
    }

    /**
     * 保存 Catalog 到文件
     */
    private void save() {
        try {
            Files.createDirectories(Paths.get("data"));
            try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(Paths.get(CATALOG_FILE)))) {
                dos.writeInt(tables.size());
                for (TableDef def : tables.values()) {
                    writeTableDef(dos, def);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save catalog", e);
        }
    }

    private TableDef readTableDef(DataInputStream dis) throws IOException {
        String tableName = dis.readUTF();
        int columnCount = dis.readInt();
        List<ColumnDef> columns = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            String name = dis.readUTF();
            DataType type = DataType.valueOf(dis.readUTF());
            int maxLength = dis.readInt();
            columns.add(new ColumnDef(name, type, maxLength));
        }
        return new TableDef(tableName, columns);
    }

    private void writeTableDef(DataOutputStream dos, TableDef def) throws IOException {
        dos.writeUTF(def.tableName());
        dos.writeInt(def.columns().size());
        for (ColumnDef col : def.columns()) {
            dos.writeUTF(col.name());
            dos.writeUTF(col.type().name());
            dos.writeInt(col.maxLength());
        }
    }

    /**
     * 获取所有表（用于测试/重启恢复）
     */
    public List<TableDef> getAllTables() {
        return new ArrayList<>(tables.values());
    }
}