package com.minidb.plan;

import java.util.List;

/** 逻辑计划树节点。优化器通过 children()/accept 遍历与重写。D5 M0 冻结：十节点。 */
public sealed interface PlanNode permits SeqScan, Filter, Project, AggregatePlan, CreateTablePlan, InsertPlan, DeletePlan, UpdatePlan, SortPlan, JoinPlan {
    List<PlanNode> children();

    <R> R accept(PlanVisitor<R> visitor);
}
