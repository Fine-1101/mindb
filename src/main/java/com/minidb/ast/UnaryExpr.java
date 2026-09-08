package com.minidb.ast;

import com.minidb.common.Position;

/** 一元表达式。 */
public record UnaryExpr(UnaryOp op, Expression operand, Position pos) implements Expression {
}
