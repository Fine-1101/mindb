package com.minidb.planner;

import com.minidb.ast.ColumnRef;
import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.Expression;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.catalog.Catalog;
import com.minidb.catalog.ColumnDef;
import com.minidb.catalog.TableDef;
import com.minidb.common.MiniDbException;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;

import java.util.ArrayList;
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

    private PlanNode planSelect(SelectStmt select) throws MiniDbException {
        PlanNode source = new SeqScan(select.tableName());
        if (select.where() != null) {
            source = new Filter(source, select.where());
        }
        List<String> columns = null;
        if (select.columns() != null) {
            columns = new ArrayList<>();
            for (Expression col : select.columns()) {
                if (col instanceof ColumnRef ref) {
                    columns.add(ref.column());
                } else {
                    // D4-A 编译契约适配：SELECT 列表现可承载 FuncCall。
                    // 聚合计划（AggregatePlan 等）属于 D 的 D4 任务，这里仅最小占位。
                    throw new MiniDbException(MiniDbException.Phase.PLAN, col.pos(),
                            "聚合查询计划尚未支持: " + col);
                }
            }
        }
        return new Project(source, columns);
    }
}
