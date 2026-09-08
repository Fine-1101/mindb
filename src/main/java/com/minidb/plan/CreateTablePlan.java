package com.minidb.plan;

import com.minidb.catalog.TableDef;

import java.util.List;

/** 建表计划。 */
public record CreateTablePlan(TableDef table) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
