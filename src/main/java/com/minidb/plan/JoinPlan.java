package com.minidb.plan;

import com.minidb.ast.Expression;

import java.util.List;

/**
 * INNER JOIN 计划（D5 M0 冻结，第 10 节点）。
 *
 * <p>单层：FROM t1 JOIN t2 ON condition。condition 为 ON 表达式（必填）。
 * 执行拍板：嵌套循环 O(n×m)；合成行 = 左表列 ++ 右表列；列映射双注册
 * （限定名恒注册，非限定名两表唯一时注册，二义由 Semantic 拒绝）。
 */
public record JoinPlan(PlanNode left, PlanNode right, Expression condition) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of(left, right);
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
