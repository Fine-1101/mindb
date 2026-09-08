package com.minidb.lexer;

import com.minidb.common.Position;

/** 词法单元。value 为字面量解析值（Integer/Double/String），其余类型为 null。 */
public record Token(TokenType type, String text, Object value, Position pos) {
}
