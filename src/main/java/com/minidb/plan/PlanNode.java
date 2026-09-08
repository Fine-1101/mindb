package com.minidb.plan;

import java.util.List;

/** 逻辑计划树节点。优化器通过 children()/accept 遍历与重写。 */
public sealed interface PlanNode permits SeqScan, Filter, Project, CreateTablePlan, InsertPlan {
    List<PlanNode> children();

    <R> R accept(PlanVisitor<R> visitor);
}
