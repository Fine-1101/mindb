package com.minidb.planner;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.Expression;
import com.minidb.ast.Literal;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.common.DataType;
import com.minidb.common.Position;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import com.minidb.semantic.TypeRules;

import java.util.Optional;

/**
 * Optimizer：规则式优化（返回新树，不改输入）。
 *
 * <p>规则 1 常量折叠：条件中纯字面量子树先算——算术（1+2→3）、比较（1=1→TRUE）、
 * NOT TRUE→FALSE、NEG 字面量取负；含列引用的子树不动。
 *
 * <p>规则 2 布尔化简：x AND TRUE→x、x AND FALSE→FALSE、x OR FALSE→x、x OR TRUE→TRUE。
 *
 * <p>结构消除（P1）：条件折叠为 TRUE 的 Filter 节点整体消除（child 顶上）。
 * 条件统一处理：Filter.condition 与 DeletePlan.condition 都做折叠。
 * 折叠结果字面量的位置 = 被折叠表达式自己的位置。
 */
public class Optimizer {

    public PlanNode optimize(PlanNode plan) {
        return switch (plan) {
            case Filter filter -> {
                PlanNode child = optimize(filter.child());
                Expression condition = fold(filter.condition());
                // P1：Filter(TRUE) 消除（恒真过滤冗余）
                yield isTrue(condition) ? child : new Filter(child, condition);
            }
            case Project project -> {
                // SELECT * 的透传 Project 删除（columns==null 表示透传全部列，消除后执行器整行输出）
                PlanNode child = optimize(project.child());
                yield project.columns() == null ? child : new Project(child, project.columns());
            }
            case DeletePlan delete -> new DeletePlan(delete.tableName(),
                    delete.condition() == null ? null : fold(delete.condition()));
            case SeqScan scan -> scan;
            case CreateTablePlan create -> create;
            case InsertPlan insert -> insert;
        };
    }

    // ==================================================================
    // 表达式折叠：常量折叠 + 布尔化简。无可优化时返回原对象（保持幂等）。
    // ==================================================================

    private Expression fold(Expression expr) {
        if (expr instanceof BinaryExpr bin) {
            Expression left = fold(bin.left());
            Expression right = fold(bin.right());
            Expression folded = switch (bin.op()) {
                case ADD, SUB, MUL, DIV -> foldArithmetic(bin, left, right);
                case EQ, NE, LT, LE, GT, GE -> foldComparison(bin, left, right);
                case AND -> simplifyAnd(left, right, bin.pos());
                case OR -> simplifyOr(left, right, bin.pos());
            };
            if (folded != null) {
                return folded;
            }
            return left == bin.left() && right == bin.right() ? bin
                    : new BinaryExpr(left, bin.op(), right, bin.pos());
        }
        if (expr instanceof UnaryExpr un) {
            Expression operand = fold(un.operand());
            Expression folded = switch (un.op()) {
                case NOT -> {
                    // NOT NOT x → x（x 取 NOT 折叠后的操作数）
                    if (operand instanceof UnaryExpr inner && inner.op() == UnaryOp.NOT) {
                        yield inner.operand();
                    }
                    if (isTrue(operand)) {
                        yield new Literal(false, DataType.BOOLEAN, un.pos());
                    }
                    if (isFalse(operand)) {
                        yield new Literal(true, DataType.BOOLEAN, un.pos());
                    }
                    yield null;
                }
                case NEG -> {
                    if (operand instanceof Literal l && l.value() instanceof Number n) {
                        Object value = n instanceof Integer i ? -i : -n.doubleValue();
                        yield new Literal(value, l.type(), un.pos());
                    }
                    yield null;
                }
            };
            if (folded != null) {
                return folded;
            }
            return operand == un.operand() ? un : new UnaryExpr(un.op(), operand, un.pos());
        }
        return expr; // Literal / ColumnRef
    }

    /** 算术折叠：两侧均为数值字面量。结果类型查 TypeRules（INT+INT→INT，含 FLOAT→FLOAT）；
     *  除数为 0 不折叠保留原节点（INT 会抛 ArithmeticException，FLOAT 折成 Infinity 同样保守处理）。 */
    private Expression foldArithmetic(BinaryExpr bin, Expression left, Expression right) {
        if (!(left instanceof Literal l) || !(right instanceof Literal r)
                || !(l.value() instanceof Number ln) || !(r.value() instanceof Number rn)) {
            return null;
        }
        if (bin.op() == BinaryOp.DIV && rn.doubleValue() == 0.0) {
            return null;
        }
        Optional<DataType> type = TypeRules.arithmetic(l.type(), r.type());
        if (type.isEmpty()) {
            return null; // 语义检查已通过，正常不会发生
        }
        Object value;
        if (type.get() == DataType.FLOAT) {
            value = switch (bin.op()) {
                case ADD -> ln.doubleValue() + rn.doubleValue();
                case SUB -> ln.doubleValue() - rn.doubleValue();
                case MUL -> ln.doubleValue() * rn.doubleValue();
                default -> ln.doubleValue() / rn.doubleValue();
            };
        } else {
            value = switch (bin.op()) {
                case ADD -> ln.intValue() + rn.intValue();
                case SUB -> ln.intValue() - rn.intValue();
                case MUL -> ln.intValue() * rn.intValue();
                default -> ln.intValue() / rn.intValue();
            };
        }
        return new Literal(value, type.get(), bin.pos());
    }

    /** 比较折叠：数值×数值（按 double 比较，含 INT/FLOAT 提升）或 VARCHAR×VARCHAR。 */
    private Expression foldComparison(BinaryExpr bin, Expression left, Expression right) {
        if (!(left instanceof Literal l) || !(right instanceof Literal r)) {
            return null;
        }
        int cmp;
        if (l.value() instanceof Number ln && r.value() instanceof Number rn) {
            cmp = Double.compare(ln.doubleValue(), rn.doubleValue());
        } else if (l.value() instanceof String ls && r.value() instanceof String rs) {
            cmp = ls.compareTo(rs);
        } else {
            return null; // 语义检查已限制比较组合，正常不会发生
        }
        boolean result = switch (bin.op()) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            default -> cmp >= 0; // GE
        };
        return new Literal(result, DataType.BOOLEAN, bin.pos());
    }

    /** x AND TRUE→x；x AND FALSE→FALSE。 */
    private Expression simplifyAnd(Expression left, Expression right, Position pos) {
        if (isTrue(left)) {
            return right;
        }
        if (isTrue(right)) {
            return left;
        }
        if (isFalse(left) || isFalse(right)) {
            return new Literal(false, DataType.BOOLEAN, pos);
        }
        return null;
    }

    /** x OR FALSE→x；x OR TRUE→TRUE。 */
    private Expression simplifyOr(Expression left, Expression right, Position pos) {
        if (isFalse(left)) {
            return right;
        }
        if (isFalse(right)) {
            return left;
        }
        if (isTrue(left) || isTrue(right)) {
            return new Literal(true, DataType.BOOLEAN, pos);
        }
        return null;
    }

    private static boolean isTrue(Expression e) {
        return e instanceof Literal l && l.type() == DataType.BOOLEAN
                && Boolean.TRUE.equals(l.value());
    }

    private static boolean isFalse(Expression e) {
        return e instanceof Literal l && l.type() == DataType.BOOLEAN
                && Boolean.FALSE.equals(l.value());
    }
}
