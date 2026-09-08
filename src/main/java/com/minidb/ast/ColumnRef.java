package com.minidb.ast;

import com.minidb.common.Position;

/** 列引用。table 为 null 表示无限定名（col），非 null 为限定名（t.col）。 */
public record ColumnRef(String table, String column, Position pos) implements Expression {
}
