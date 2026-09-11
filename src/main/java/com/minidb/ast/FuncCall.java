package com.minidb.ast;

import com.minidb.common.Position;

/**
 * 聚合函数调用：COUNT(*) / SUM(score) / AVG(a+1) 等。
 *
 * <p>func 为大写规范化后的函数名（COUNT/SUM/AVG/MIN/MAX，Parser 侧保证）；
 * arg == null 表示 COUNT(*) 的通配形式。pos 指向函数名 token。
 *
 * <p>设计拍板（D4）：标识符不进 Lexer 关键字表，Parser 按 IDENT+'(' 上下文识别——
 * 列名 count 不带括号时仍是普通标识符。
 */
public record FuncCall(String func, Expression arg, Position pos) implements Expression {

    /** 展示名（CLI 表头 / PlanPrinter 共用）：COUNT(*)、SUM(score)、AVG(a + 1)。 */
    public String display() {
        return func + "(" + displayArg(arg) + ")";
    }

    private static String displayArg(Expression e) {
        if (e == null) {
            return "*";
        }
        if (e instanceof ColumnRef c) {
            return c.column();
        }
        if (e instanceof Literal l) {
            return String.valueOf(l.value());
        }
        if (e instanceof BinaryExpr b) {
            return displayArg(b.left()) + " " + symbol(b.op()) + " " + displayArg(b.right());
        }
        if (e instanceof UnaryExpr u) {
            return u.op() + displayArg(u.operand());
        }
        if (e instanceof FuncCall f) {
            return f.display();
        }
        return e.toString();
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
