package com.minidb.plan;

import com.minidb.ast.Expression;

import java.util.List;

/** 插入计划。targetColumns 为语义分析展开后的目标列序，与 rows 中每行的值一一对应。 */
public record InsertPlan(String tableName, List<String> targetColumns, List<List<Expression>> rows) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
