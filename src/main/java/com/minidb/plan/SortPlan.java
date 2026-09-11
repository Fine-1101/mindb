package com.minidb.plan;

import java.util.List;

/**
 * 排序计划（D5 M0 冻结，第 9 节点）。
 *
 * <p>keys 按书写序多级比较；位于计划树最外层（DISTINCT 之后、输出前）。
 * 执行拍板：open 拉全 → 稳定排序 → 逐行输出；NULL 视为最小值。
 */
public record SortPlan(PlanNode child, List<SortKey> keys) implements PlanNode {
    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
