package com.minidb.planner;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.Expression;
import com.minidb.ast.FuncCall;
import com.minidb.ast.Literal;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.common.DataType;
import com.minidb.common.Position;
import com.minidb.plan.AggregatePlan;
import com.minidb.plan.CreateTablePlan;
import com.minidb.plan.DeletePlan;
import com.minidb.plan.Filter;
import com.minidb.plan.InsertPlan;
import com.minidb.plan.PlanNode;
import com.minidb.plan.Project;
import com.minidb.plan.SeqScan;
import com.minidb.plan.SortPlan;
import com.minidb.plan.UpdatePlan;
import com.minidb.plan.JoinPlan;
import com.minidb.semantic.TypeRules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Optimizer：规则式优化（返回新树，不改输入）。
 *
 * <p>规则 1 常量折叠：条件中纯字面量子树先算——算术（1+2→3）、比较（1=1→TRUE）、
 * NOT TRUE→FALSE、NEG 字面量取负；含列引用的子树不动。
 *
 * <p>规则 2 布尔化简：x AND TRUE→x、x AND FALSE→FALSE、x OR FALSE→x、x OR TRUE→TRUE。
 *
 * <p>规则 3 Filter 合并：Filter(Filter(x,p),q) → Filter(x, q AND p)（Planner 不产嵌套 Filter，
 * 作为可展示的等价变换规则，与手工构造计划互通）。
 *
 * <p>规则 4 投影裁剪：从 Project/AggregatePlan 顶层收集全树实际引用的列集，
 * 标注到 SeqScan{cols}（SELECT * 透传不标注）。行式存储无列裁剪执行收益，
 * 标注即"读了哪些列"的裁剪证明（.trace 演示载体）。
 *
 * <p>结构消除（P1）：条件折叠为 TRUE 的 Filter 节点整体消除（child 顶上）；
 * SELECT * 的透传 Project 消除。
 * 条件统一处理：Filter.condition 与 DeletePlan.condition 都做折叠。
 * 折叠结果字面量的位置 = 被折叠表达式自己的位置。
 */
public class Optimizer {

    public PlanNode optimize(PlanNode plan) {
        return annotateColumns(rewrite(plan));
    }

    // ==================================================================
    // 结构重写：规则 1/2/3 + 节点消除
    // ==================================================================

    private PlanNode rewrite(PlanNode plan) {
        return switch (plan) {
            case Filter filter -> {
                PlanNode child = rewrite(filter.child());
                Expression condition = fold(filter.condition());
                // 规则 3：Filter(Filter(x, p), q) → Filter(x, q AND p)（AND 可交换，结果等价）
                if (child instanceof Filter inner) {
                    condition = fold(new BinaryExpr(condition, BinaryOp.AND,
                            inner.condition(), filter.condition().pos()));
                    child = inner.child();
                }
                // P1：Filter(TRUE) 消除（恒真过滤冗余）
                yield isTrue(condition) ? child : new Filter(child, condition);
            }
            case Project project -> {
                // SELECT * 的透传 Project 删除（columns==null 表示透传全部列，消除后执行器整行输出）
                PlanNode child = rewrite(project.child());
                yield project.columns() == null ? child
                        : new Project(child, project.columns(), project.distinct());
            }
            case AggregatePlan agg -> new AggregatePlan(rewrite(agg.input()), agg.aggregates(), agg.groupBy());
            case DeletePlan delete -> new DeletePlan(delete.tableName(),
                    delete.condition() == null ? null : fold(delete.condition()));
            case SeqScan scan -> scan;
            case CreateTablePlan create -> create;
            case InsertPlan insert -> insert;
            // D5 新节点 M0 透传（编译安全占位）；并行阶段 B 换真实现（子树递归重写 + 规则4 覆盖）
            case UpdatePlan update -> update;
            case SortPlan sort -> sort;
            case JoinPlan join -> join;
        };
    }

    // ==================================================================
    // 规则 4 投影裁剪：引用列集收集并标注到 SeqScan
    // ==================================================================

    private PlanNode annotateColumns(PlanNode node) {
        boolean prunable = (node instanceof Project p && p.columns() != null)
                || node instanceof AggregatePlan;
        if (!prunable) {
            return node; // SELECT * 透传 / DML / DDL 不标注
        }
        Set<String> cols = new LinkedHashSet<>();
        collectPlanColumns(node, cols);
        return withScanCols(node, List.copyOf(cols));
    }

    private void collectPlanColumns(PlanNode node, Set<String> cols) {
        if (node instanceof Project p) {
            if (p.columns() != null) {
                cols.addAll(p.columns());
            }
            collectPlanColumns(p.child(), cols);
        } else if (node instanceof AggregatePlan a) {
            for (FuncCall f : a.aggregates()) {
                collectExprColumns(f.arg(), cols);
            }
            collectPlanColumns(a.input(), cols);
        } else if (node instanceof Filter f) {
            collectExprColumns(f.condition(), cols);
            collectPlanColumns(f.child(), cols);
        }
        // SeqScan：终点
    }

    private void collectExprColumns(Expression expr, Set<String> cols) {
        if (expr instanceof ColumnRef c) {
            cols.add(c.column());
        } else if (expr instanceof BinaryExpr b) {
            collectExprColumns(b.left(), cols);
            collectExprColumns(b.right(), cols);
        } else if (expr instanceof UnaryExpr u) {
            collectExprColumns(u.operand(), cols);
        } else if (expr instanceof FuncCall f) {
            collectExprColumns(f.arg(), cols);
        }
    }

    private PlanNode withScanCols(PlanNode node, List<String> cols) {
        if (node instanceof SeqScan s) {
            return s.cols() == null ? new SeqScan(s.tableName(), cols) : s;
        }
        if (node instanceof Filter f) {
            return new Filter(withScanCols(f.child(), cols), f.condition());
        }
        if (node instanceof Project p) {
            return new Project(withScanCols(p.child(), cols), p.columns(), p.distinct());
        }
        if (node instanceof AggregatePlan a) {
            return new AggregatePlan(withScanCols(a.input(), cols), a.aggregates(), a.groupBy());
        }
        return node;
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
                // IS [NOT] NULL 谓词 M0 无折叠；并行阶段视需要处理字面量操作数
                case IS_NULL, IS_NOT_NULL -> null;
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
