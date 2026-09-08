package com.minidb.catalog;

import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 内存版 Catalog。表名/列名大小写不敏感：表名折叠小写作 key、列名忽略大小写比较，
 * 规范化收敛在此（AST/Token 保留原始拼写，用于报错与展示）。 */
public class MemoryCatalog implements Catalog {

    private final Map<String, TableDef> tables = new HashMap<>();

    @Override
    public void createTable(TableDef def) throws MiniDbException {
        String key = def.tableName().toLowerCase();
        if (tables.containsKey(key)) {
            // Catalog 不含源码位置：位置由 Semantic 层预检查时带上（D1 拍板项2），此处仅兜底
            throw new MiniDbException(MiniDbException.Phase.SEMANTIC, null,
                "表已存在: " + def.tableName());
        }
        tables.put(key, def);
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
}
