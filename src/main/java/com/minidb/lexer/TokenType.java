package com.minidb.lexer;

/** Token 类型全集。关键字大小写不敏感。 */
public enum TokenType {
    // 关键字
    KW_CREATE, KW_TABLE, KW_INSERT, KW_INTO, KW_VALUES, KW_SELECT, KW_FROM,
    KW_WHERE, KW_DELETE, KW_AND, KW_OR, KW_NOT, KW_INT, KW_FLOAT, KW_VARCHAR,

    // 字面量与标识符
    INT_LIT, FLOAT_LIT, STRING, IDENT,

    // 运算符：比较 + 算术
    OP_LT, OP_LE, OP_GT, OP_GE, OP_EQ, OP_NE, OP_EQEQ, OP_ADD, OP_SUB, OP_DIV,

    // 分隔符（STAR 双用途：SELECT * 与乘法，由 Parser 按上下文区分）
    LPAREN, RPAREN, COMMA, SEMI, DOT, STAR,

    EOF
}
