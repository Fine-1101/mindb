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
                // 标量聚合：无 Project 层；语义层已拒绝与普通列混写
                result = agg;
            } else {
                // Aggregate 输出所有分组键，Project只选取select需要的
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

        // ORDER BY：最外层（DISTINCT 之后输出前）
        if (select.orderBy() != null) {
            List<SortKey> keys = select.orderBy().stream()
                    .map(k -> new SortKey(k.column().column(), k.asc())).toList();
            result = new SortPlan(result, keys);
        }
        return result;
    }
}
