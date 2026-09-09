package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/**
 * SELECT 列列表 FROM 表名 [WHERE 条件]。
 * columns 为 null 表示 SELECT *；列表元素为无限定名 ColumnRef（table==null），携带列名位置。
 */
public record SelectStmt(List<ColumnRef> columns, String tableName, Expression where, Position pos) implements Statement {
}
