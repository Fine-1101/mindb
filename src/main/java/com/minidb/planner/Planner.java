package com.minidb.planner;

import com.minidb.ast.ColumnRef;
import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.ast.UpdateStmt;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.TableDef;
import com.minidb.common.MiniDbException;
import com.minidb.plan.AggregatePlan;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;

import java.util.List;

/**
 * Planner：AST → 逻辑计划树（六类节点）。
 *
 * <p>转换规则：CREATE→CreateTablePlan、INSERT→InsertPlan（targetColumns 展开列序）、
 * SELECT→Project(Filter(SeqScan))（无 WHERE 去掉 Filter，SELECT * 时 Project.columns 为 null 透传）、
 * DELETE→DeletePlan（无 WHERE 时 condition 为 null）。
 *
 * <p>前置：stmt 已通过 SemanticAnalyzer.analyze（存在性/类型已检查，此处不再重复校验）。
 */
public class Planner {

    private final Catalog catalog;

    public Planner(Catalog catalog) {
        this.catalog = catalog;
    }

    public PlanNode plan(Statement stmt) throws MiniDbException {
        return switch (stmt) {
            case CreateTableStmt create ->
                    new CreateTablePlan(new TableDef(create.tableName(), create.columns()));
            case InsertStmt insert -> planInsert(insert);
            case SelectStmt select -> planSelect(select);
            case DeleteStmt delete -> new DeletePlan(delete.tableName(), delete.where());
            // D5 M0 桩：UPDATE 计划生成在并行阶段实现（Semantic 桩先行拦截，正常不可达）
            case UpdateStmt update -> throw new MiniDbException(MiniDbException.Phase.PLAN, update.pos(),
                    "UPDATE 计划生成未实现（D5 并行阶段）");
        };
    }

    /** 指定列保持书写序；未指定列取 Catalog 表定义全列序。 */
    private PlanNode planInsert(InsertStmt insert) throws MiniDbException {
        List<String> targetColumns;
        if (insert.columns() != null) {
            targetColumns = insert.columns().stream().map(ColumnRef::column).toList();
        } else {
            TableDef table = catalog.findTable(insert.tableName()).orElseThrow(() ->
                    new MiniDbException(MiniDbException.Phase.SEMANTIC, null,
                            "表不存在: " + insert.tableName()));
            targetColumns = table.columns().stream().map(ColumnDef::name).toList();
        }
        return new InsertPlan(insert.tableName(), targetColumns, insert.rows());
    }

    private PlanNode planSelect(SelectStmt select) {
        PlanNode source = new SeqScan(select.tableName());
        if (select.where() != null) {
            source = new Filter(source, select.where());
        }
        if (select.aggregates() != null) {
            // SELECT 含聚合 → AggregatePlan（语义层已拒绝与普通列混写，无 Project 层）
            return new AggregatePlan(source, select.aggregates());
        }
        List<String> columns = select.columns() == null ? null
                : select.columns().stream().map(ColumnRef::column).toList();
        return new Project(source, columns, select.distinct());
    }
}
