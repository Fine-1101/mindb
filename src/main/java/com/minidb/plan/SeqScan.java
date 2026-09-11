package com.minidb.plan;

import java.util.List;

/**
 * 顺序扫描单表。
 *
 * <p>cols 为优化器规则4（投影裁剪）标注的"本树实际引用的列集"：
 * null = 全部列（未标注 / SELECT * 透传）；非 null 时仅供展示与裁剪证明，
 * 执行仍整行解码（行式存储无列裁剪收益，标注即等价性证据本身）。
 */
public record SeqScan(String tableName, List<String> cols) implements PlanNode {

    /** 兼容构造器：未标注列集的扫描（既有调用点与 record equals 不破坏）。 */
    public SeqScan(String tableName) {
        this(tableName, null);
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public <R> R accept(PlanVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
