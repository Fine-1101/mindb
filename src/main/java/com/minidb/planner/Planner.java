package com.minidb.planner;

import com.minidb.ast.ColumnRef;
import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.FuncCall;
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
import com.minidb.plan.JoinPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import com.minidb.plan.SortKey;
import com.minidb.plan.SortPlan;
import com.minidb.plan.UpdatePlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Planner：AST → 逻辑计划树（十节点）。
 *
 * <p>转换规则：CREATE→CreateTablePlan、INSERT→InsertPlan（targetColumns 展开列序）、
 * SELECT→SortPlan(Project(...(Filter(SeqScan|JoinPlan))))（按需叠加：JOIN 数据源 → WHERE Filter →
 * 聚合/GROUP BY → Project → 最外层 Sort）、DELETE→DeletePlan、UPDATE→UpdatePlan。
 *
 * <p>GROUP BY 形态（D5 拍板 12）：Project(AggregatePlan(input, aggregates, groupBy))——
 * AggregatePlan 输出行 = [组键值...] ++ [聚合值...]（列名 FuncCall.display()），顶层 Project
 * 按 SELECT 项重排（契约限制：普通列与聚合分列存储，输出序 = [普通列..., 聚合项...]）。
 *
 * <p>SortPlan 最外层（D5 拍板 1/7）：DISTINCT 之后、输出前；键列须 ⊆ 输出列（Semantic 已查）。
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
            case UpdateStmt update -> new UpdatePlan(update.tableName(), update.sets(), update.where());
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
        // 数据源：单表 SeqScan 或 INNER JOIN（拍板 9：单层，ON 必填）
        PlanNode source;
        if (select.joinTable() != null) {
            source = new JoinPlan(new SeqScan(select.tableName()),
                    new SeqScan(select.joinTable()), select.joinOn());
        } else {
            source = new SeqScan(select.tableName());
        }
        if (select.where() != null) {
            source = new Filter(source, select.where());
        }

        PlanNode result;
        if (select.aggregates() != null || select.groupBy() != null) {
            List<FuncCall> aggregates = select.aggregates() == null ? List.of() : select.aggregates();
            List<String> groupBy = select.groupBy() == null ? null
                    : select.groupBy().stream().map(ColumnRef::column).toList();
            PlanNode agg = new AggregatePlan(source, aggregates, groupBy);
            if (select.groupBy() == null) {
                // 标量聚合：D4 形态（无 Project 层；语义层已拒绝与普通列混写）
                result = agg;
            } else {
                // GROUP BY：顶层 Project 重排（[组键...] ++ [聚合值...] → [普通列..., 聚合项...]）
                List<String> columns = new ArrayList<>();
                if (select.columns() != null) {
                    select.columns().forEach(c -> columns.add(c.column()));
                }
                aggregates.forEach(f -> columns.add(f.display()));
                result = new Project(agg, columns, select.distinct());
            }
        } else {
            List<String> columns = select.columns() == null ? null
                    : select.columns().stream().map(ColumnRef::column).toList();
            result = new Project(source, columns, select.distinct());
        }

        // ORDER BY：最外层（DISTINCT 之后输出前，拍板 1/7）
        if (select.orderBy() != null) {
            List<SortKey> keys = select.orderBy().stream()
                    .map(k -> new SortKey(k.column().column(), k.asc())).toList();
            result = new SortPlan(result, keys);
        }
        return result;
    }
}
