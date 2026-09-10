package com.minidb.ast;

/** 表达式节点：常量、列引用、二元、一元、函数调用。 */
public sealed interface Expression extends AstNode
        permits Literal, ColumnRef, BinaryExpr, UnaryExpr, FuncCall {
}
