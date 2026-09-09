package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/**
 * INSERT INTO 表名 [(列, ...)] VALUES (值, ...), (值, ...)。
 * columns 为 null 表示未指定列（= 表定义序全列）；
 * 列表元素为无限定名 ColumnRef（table==null），携带列名自己的位置供语义错误定位。
 */
public record InsertStmt(String tableName, List<ColumnRef> columns, List<List<Expression>> rows, Position pos) implements Statement {
}
