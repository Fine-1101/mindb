package com.minidb.ast;

import com.minidb.common.DataType;
import com.minidb.common.Position;

/** 常量。value 为 Integer/Double/String，与 type 对应。 */
public record Literal(Object value, DataType type, Position pos) implements Expression {
}
