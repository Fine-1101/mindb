package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/**
 * UPDATE 表名 SET 列 = 值[, ...] [WHERE 条件]（D5 M0 冻结契约）。
 *
 * <p>SET 值可为字面量 / NULL / 引用本行列的表达式（如 score + 5）；
 * where 为 null 表示全表更新。
 * <p>pos 约定 = 表名 token 位置（同其他语句）。
 */
public record UpdateStmt(String tableName, List<SetClause> sets, Expression where,
                         Position pos) implements Statement {
}
