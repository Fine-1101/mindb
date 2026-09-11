package com.minidb.plan;

import com.minidb.ast.FuncCall;

import java.util.List;

/**
 * 聚合计划（无 GROUP BY 时输出单行；有 GROUP BY 时逐组输出）。
 *
 * <p>input 为过滤后的行源（SeqScan 或 Filter）；aggregates 与 SELECT 聚合项书写序一致。
 *
 * <p>groupBy 为 D5 分组列（M0 冻结，null = 标量聚合）。分组时输出行 = [组键值...] ++ [聚合值...]，
 * 顶层 Project 按 SELECT 书写序重排；输出序按组键首次出现序（LinkedHashMap）。
 *
 * <p>空值语义拍板（D5，作废 D4 空表拍板）：COUNT(*)=全部行数、COUNT(col)=非 NULL 值数；
 * SUM/AVG/MIN/MAX 忽略 NULL 输入，无任何非 NULL 输入→NULL；COUNT 永不 NULL（空表→0）。
 */
public record AggregatePlan(PlanNode input, List<FuncCall> aggregates, List<String> groupBy) implements PlanNode {

    /** 兼容构造器：标量聚合（既有调用点与 record equals 不破坏）。 */
    public AggregatePlan(PlanNode input, List<FuncCall> aggregates) {
        this(input, aggregates, null);
    }

    @Override
    public List<PlanNode> children() {
        return List.of(input);
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
