package com.minidb.ast;

import com.minidb.common.Position;

/**
 * 函数调用表达式。用于聚合函数 COUNT/SUM/AVG/MIN/MAX。
 * arg 为 null 表示 COUNT(*)（星号无列参数）。
 */
public record FuncCall(String func, Expression arg, Position pos) implements Expression {
}
