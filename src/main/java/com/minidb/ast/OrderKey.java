package com.minidb.ast;

/** ORDER BY 的单个排序键：列 + 方向（默认 ASC）。列位置由 column.pos() 承载。 */
public record OrderKey(ColumnRef column, boolean asc) {
}
