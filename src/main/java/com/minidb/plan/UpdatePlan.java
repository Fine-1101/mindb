package com.minidb.plan;

import com.minidb.ast.Expression;
import com.minidb.ast.SetClause;

import java.util.List;

/**
 * 更新计划（D5 M0 冻结，第 8 节点）。
 *
 * <p>sets 为 SET 赋值列表（AST SetClause）；condition 为 WHERE 过滤条件，null 表示全表更新。
 * 执行拍板：两阶段（先收集命中行，后逐行求值 SET 重编码 deleteRow+insertRow）。
 */
public record UpdatePlan(String tableName, List<SetClause> sets, Expression condition) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
