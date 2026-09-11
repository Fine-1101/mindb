package com.minidb.plan;

import com.minidb.ast.FuncCall;

import java.util.List;

/**
 * 聚合计划节点：对子计划输出执行聚合函数，产出单行结果。
 * 无 GROUP BY，整表聚合为一条。
 */
public record AggregatePlan(PlanNode input, List<FuncCall> aggregates) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of(input);
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
