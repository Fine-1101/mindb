package com.minidb.plan;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.Literal;
import com.minidb.ast.UnaryExpr;
import com.minidb.common.DataType;

import java.util.List;

/**
 * 计划树 S-expression 单行打印（优化前后各打一次，作为"优化前后可展示、可证明等价"的载体）。
 *
 * <pre>
 * (CreateTable users)
 * (Insert users [id, name] [(1, 'Tom'), (2, 'Alice')])
 * (Delete users (= a 2))          无 WHERE 时打印 (Delete users *)
 * (SeqScan users)
 * (Filter (SeqScan users) (= a 2))
 * (Project (Filter (SeqScan users) (= a 2)) [a, b])    SELECT * 时列打印 *
 * </pre>
 */
public class PlanPrinter implements PlanVisitor<String> {

    public static String print(PlanNode plan) {
        return plan.accept(new PlanPrinter());
    }

    @Override
    public String visit(SeqScan node) {
        return "(SeqScan " + node.tableName() + ")";
    }

    @Override
    public String visit(Filter node) {
        return "(Filter " + node.child().accept(this) + " " + expr(node.condition()) + ")";
    }

    @Override
    public String visit(Project node) {
        return "(Project " + node.child().accept(this) + " " + columns(node.columns()) + ")";
    }

    @Override
    public String visit(CreateTablePlan node) {
        return "(CreateTable " + node.table().tableName() + ")";
    }

    @Override
    public String visit(InsertPlan node) {
        return "(Insert " + node.tableName() + " " + columns(node.targetColumns()) + " " + rows(node.rows()) + ")";
    }

    @Override
    public String visit(DeletePlan node) {
        String condition = node.condition() == null ? "*" : expr(node.condition());
        return "(Delete " + node.tableName() + " " + condition + ")";
    }

    // ==================================================================
    // 表达式打印
    // ==================================================================

    private static String columns(List<String> names) {
        return names == null ? "*" : names.toString();
    }

    private static String rows(List<List<Expression>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("(");
            List<Expression> row = rows.get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) {
                    sb.append(", ");
                }
                sb.append(expr(row.get(j)));
            }
            sb.append(")");
        }
        return sb.append("]").toString();
    }

    private static String expr(Expression e) {
        if (e instanceof Literal lit) {
            return lit.type() == DataType.VARCHAR ? "'" + lit.value() + "'"
                    : String.valueOf(lit.value());
        }
        if (e instanceof ColumnRef col) {
            return col.table() == null ? col.column() : col.table() + "." + col.column();
        }
        if (e instanceof BinaryExpr bin) {
            return "(" + symbol(bin.op()) + " " + expr(bin.left()) + " " + expr(bin.right()) + ")";
        }
        UnaryExpr un = (UnaryExpr) e;
        return "(" + un.op() + " " + expr(un.operand()) + ")";
    }

    private static String symbol(BinaryOp op) {
        return switch (op) {
            case AND -> "AND";
            case OR -> "OR";
            case EQ -> "=";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
        };
    }
}
