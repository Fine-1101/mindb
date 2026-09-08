package com.minidb.ast;

/** 二元运算符：逻辑 > 比较 > 算术，优先级由 Parser 处理。 */
public enum BinaryOp {
    AND, OR,
    EQ, NE, LT, LE, GT, GE,
    ADD, SUB, MUL, DIV
}
