package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/**
<<<<<<< HEAD
 * SELECT 列列表 FROM 表名 [WHERE 条件]。
 * columns 为 null 表示 SELECT *；列表元素为表达式：
 * 普通列为无限定名 ColumnRef（table==null），聚合函数为 FuncCall（如 COUNT(*)）。
 */
public record SelectStmt(List<Expression> columns, String tableName, Expression where, Position pos) implements Statement {
=======
 * SELECT [DISTINCT] 列列表 FROM 表名 [WHERE 条件]。
 * columns 为 null 表示 SELECT *；列表元素可以是 ColumnRef（普通列）或 FuncCall（聚合函数）。
 */
public record SelectStmt(boolean distinct, List<Expression> columns, String tableName, Expression where, Position pos) implements Statement {
>>>>>>> origin/D4-D-aggregate-function
}
