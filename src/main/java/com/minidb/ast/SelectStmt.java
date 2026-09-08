package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/** SELECT 列列表 FROM 表名 [WHERE 条件]。columns 为 null 表示 SELECT *。 */
public record SelectStmt(List<String> columns, String tableName, Expression where, Position pos) implements Statement {
}
