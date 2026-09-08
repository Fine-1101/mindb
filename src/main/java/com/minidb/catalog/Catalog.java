package com.minidb.catalog;

import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;

import java.util.Optional;

/** 符号表：表/列存在性检查、名字绑定、类型查询。 */
public interface Catalog {
    /** 注册表定义，表已存在抛 SEMANTIC 错误。 */
    void createTable(TableDef def) throws MiniDbException;

    Optional<TableDef> findTable(String name);

    Optional<ColumnDef> findColumn(String table, String col);

    /** 列不存在时抛 SEMANTIC 错误。 */
    DataType getType(String table, String col) throws MiniDbException;
}
