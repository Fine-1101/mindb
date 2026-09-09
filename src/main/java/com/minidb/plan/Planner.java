package com.minidb.plan;

import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.catalog.TableDef;

import java.util.List;
import java.util.Optional;

/**
 * 计划生成器：将语义通过的 AST 语句转为逻辑计划树。
 *
 * <p>SELECT 计划结构（火山模型自底向上）：
 * <pre>
 *   SeqScan(tableName)
 *   [→ Filter(condition)]        若有 WHERE
 *   [→ Project(columnNames)]     若非 SELECT *
 * </pre>
 */
public class Planner {

    /**
     * 将 AST 语句转为计划节点。
     *
     * @param stmt 语义分析通过的语句
     * @param tableDef 表定义（SELECT 用于展开 * 的列名，可为 null 对 DDL/DML）
     * @return 逻辑计划树
     */
    public PlanNode buildPlan(Statement stmt, TableDef tableDef) {
        return switch (stmt) {
            case CreateTableStmt s -> new CreateTablePlan(
                    new TableDef(s.tableName(), s.columns()));
            case InsertStmt s -> {
                List<String> targetCols = s.columns() != null
                        ? s.columns().stream().map(ColumnRef::column).toList()
                        : List.of();
                yield new InsertPlan(s.tableName(), targetCols, s.rows());
            }
            case SelectStmt s -> buildSelectPlan(s, tableDef);
            case DeleteStmt s -> new DeletePlan(s.tableName(), s.where());
        };
    }

    private PlanNode buildSelectPlan(SelectStmt stmt, TableDef tableDef) {
        // 1. 底层 SeqScan
        PlanNode plan = new SeqScan(stmt.tableName());

        // 2. WHERE → Filter
        if (stmt.where() != null) {
            plan = new Filter(plan, stmt.where());
        }

        // 3. 列投影
        if (stmt.columns() != null) {
            // SELECT col1, col2, ... → Project
            List<String> colNames = stmt.columns().stream()
                    .map(ColumnRef::column).toList();
            plan = new Project(plan, colNames);
        }
        // SELECT * → 不加 Project（columns == null 透传全部列）

        return plan;
    }
}
