package com.minidb.ast;

/** UPDATE 的单个 SET 赋值：col = expr。列位置由 column.pos() 承载（错误报到列自己的 token）。 */
public record SetClause(ColumnRef column, Expression value) {
}
