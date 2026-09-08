package com.minidb.plan;

import java.util.List;

/** 投影：SELECT 列列表。columns 为 null 表示透传全部列（SELECT *）。 */
public record Project(PlanNode child, List<String> columns) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
