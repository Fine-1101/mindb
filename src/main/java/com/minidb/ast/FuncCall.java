package com.minidb.ast;

import com.minidb.common.Position;

/**
<<<<<<< HEAD
 * 函数调用（D4 聚合函数契约）。
 *
 * <p>func 统一保存大写函数名（如 "COUNT"、"SUM"），arg 为参数表达式：
 * COUNT(*) 的 arg == null（特殊语法，不构造成 ColumnRef("*")）；
 * COUNT(col) 的 arg 为 ColumnRef；SUM(a + 1) 的 arg 为 BinaryExpr。
 *
 * <p>语法层不限制函数名，未知函数（如 FOO(x)）同样构造 FuncCall，
 * 交由 Semantic 阶段判断。
=======
 * 函数调用表达式。用于聚合函数 COUNT/SUM/AVG/MIN/MAX。
 * arg 为 null 表示 COUNT(*)（星号无列参数）。
>>>>>>> origin/D4-D-aggregate-function
 */
public record FuncCall(String func, Expression arg, Position pos) implements Expression {
}
