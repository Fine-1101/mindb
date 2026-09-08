package com.minidb.plan;

import com.minidb.ast.Expression;

import java.util.List;

/** 过滤：WHERE 条件。condition 为语义检查通过后的 AST 表达式，由执行器求值。 */
public record Filter(PlanNode child, Expression condition) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
