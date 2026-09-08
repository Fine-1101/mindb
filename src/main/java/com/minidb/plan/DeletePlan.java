package com.minidb.plan;

import com.minidb.ast.Expression;

import java.util.List;

/** 删除计划。condition 为 WHERE 过滤条件（语义检查通过），null 表示无 WHERE（全表删除）。 */
public record DeletePlan(String tableName, Expression condition) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
