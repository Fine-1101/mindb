package com.minidb.plan;

/** 计划树 visitor，优化规则与 EXPLAIN/可视化共用。 */
public interface PlanVisitor<R> {
    R visit(SeqScan node);

    R visit(Filter node);

    R visit(Project node);

    R visit(CreateTablePlan node);

    R visit(InsertPlan node);

    R visit(DeletePlan node);

    R visit(AggregatePlan node);
}
