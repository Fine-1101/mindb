package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/** INSERT INTO 表名 [(列, ...)] VALUES (值, ...), (值, ...)。columns 为 null 表示未指定列。 */
public record InsertStmt(String tableName, List<String> columns, List<List<Expression>> rows, Position pos) implements Statement {
}
