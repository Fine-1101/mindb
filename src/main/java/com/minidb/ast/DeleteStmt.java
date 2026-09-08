package com.minidb.ast;

import com.minidb.common.Position;

/** DELETE FROM 表名 [WHERE 条件]。 */
public record DeleteStmt(String tableName, Expression where, Position pos) implements Statement {
}
