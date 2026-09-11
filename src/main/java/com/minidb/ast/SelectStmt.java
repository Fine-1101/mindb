package com.minidb.ast;

import com.minidb.common.Position;

import java.util.List;

/**
 * SELECT [DISTINCT] 列列表 FROM 表名 [JOIN 表名 ON 条件] [WHERE 条件]
 * [GROUP BY 列[, ...]] [ORDER BY 列 [ASC|DESC][, ...]]（D5 M0 冻结契约）。
 *
 * <p>columns 为 null 表示 SELECT *（或纯聚合）；列表元素为 ColumnRef，携带列名位置。
 *
 * <p>aggregates 为 SELECT 列表中的聚合项（COUNT(*) 等），与 columns 互斥——
 * 语义层拒绝混写（"聚合函数不能与普通列混写"），契约上同置一处便于 Semantic/Planner 分流。
 *
 * <p>distinct 对应 SELECT DISTINCT（仅普通列投影路径生效，聚合路径无 DISTINCT）。
 *
 * <p>groupBy / orderBy / joinTable / joinOn 为 D5 扩展字段（M0 冻结，默认 null = 未使用）：
 * groupBy 分组列；orderBy 排序键；joinTable/joinOn 为 INNER JOIN 的右表与 ON 条件（单层）。
 */
public record SelectStmt(List<ColumnRef> columns, List<FuncCall> aggregates,
                         String tableName, Expression where, boolean distinct,
                         List<ColumnRef> groupBy, List<OrderKey> orderBy,
                         String joinTable, Expression joinOn,
                         Position pos) implements Statement {

    /** 兼容构造器：含聚合/DISTINCT 的既有形态（新字段全 null，既有调用点不破坏）。 */
    public SelectStmt(List<ColumnRef> columns, List<FuncCall> aggregates,
                      String tableName, Expression where, boolean distinct, Position pos) {
        this(columns, aggregates, tableName, where, distinct, null, null, null, null, pos);
    }

    /** 兼容构造器：无聚合、无 DISTINCT 的普通 SELECT。 */
    public SelectStmt(List<ColumnRef> columns, String tableName, Expression where, Position pos) {
        this(columns, null, tableName, where, false, pos);
    }
}
