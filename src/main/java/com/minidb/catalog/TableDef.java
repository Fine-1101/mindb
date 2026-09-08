package com.minidb.catalog;

import java.util.List;

/** 表定义：表名 + 有序列定义。 */
public record TableDef(String tableName, List<ColumnDef> columns) {
}
