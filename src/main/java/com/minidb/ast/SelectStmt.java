package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/**
 * SELECT [DISTINCT] 列列表 FROM 表名 [WHERE 条件]。
 * columns 为 null 表示 SELECT *；列表元素可以是 ColumnRef（普通列）或 FuncCall（聚合函数）。
 */
public record SelectStmt(boolean distinct, List<Expression> columns, String tableName, Expression where, Position pos) implements Statement {
}
