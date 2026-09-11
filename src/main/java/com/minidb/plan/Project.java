package com.minidb.plan;

import java.util.List;

/**
 * 投影：SELECT 列列表。columns 为 null 表示透传全部列（SELECT *）。
 *
 * <p>distinct 为 SELECT DISTINCT 的行值去重标记（执行端 LinkedHashSet 保序去重）。
 */
public record Project(PlanNode child, List<String> columns, boolean distinct) implements PlanNode {

    /** 兼容构造器：无 DISTINCT 的投影（既有调用点与 record equals 不破坏）。 */
    public Project(PlanNode child, List<String> columns) {
        this(child, columns, false);
    }

    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
