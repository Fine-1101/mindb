package com.minidb.catalog;

import com.minidb.common.DataType;

/** 列定义。maxLength 仅 VARCHAR 有效，其余类型为 0。 */
public record ColumnDef(String name, DataType type, int maxLength) {
}
