package com.minidb.ast;

import com.minidb.catalog.ColumnDef;
import com.minidb.common.Position;

import java.util.List;

/** CREATE TABLE 表名 (列定义, ...)。 */
public record CreateTableStmt(String tableName, List<ColumnDef> columns, Position pos) implements Statement {
}
