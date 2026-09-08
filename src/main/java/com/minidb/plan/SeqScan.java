package com.minidb.plan;

import java.util.List;

/** 顺序扫描单表。 */
public record SeqScan(String tableName) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
