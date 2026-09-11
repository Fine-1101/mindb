package com.minidb.ast;

/** 一元运算符：NOT 逻辑非，NEG 取负（如 -1）；
 *  IS_NULL / IS_NOT_NULL 为 x IS [NOT] NULL 谓词（D5 M0 冻结，实现复用 UnaryExpr）。 */
public enum UnaryOp {
    NOT, NEG, IS_NULL, IS_NOT_NULL
}
