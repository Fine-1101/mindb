package com.minidb.ast;

import com.minidb.common.Position;

/** 二元表达式。 */
public record BinaryExpr(Expression left, BinaryOp op, Expression right, Position pos) implements Expression {
}
