package com.minidb.ast;

<<<<<<< HEAD
/** 表达式节点：常量、列引用、二元、一元、函数调用（D4 聚合）。 */
=======
/** 表达式节点：常量、列引用、二元、一元、函数调用。 */
>>>>>>> origin/D4-D-aggregate-function
public sealed interface Expression extends AstNode
        permits Literal, ColumnRef, BinaryExpr, UnaryExpr, FuncCall {
}
