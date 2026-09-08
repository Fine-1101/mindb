package com.minidb.ast;

import com.minidb.common.Position;

/** AST 顶层节点，所有节点携带位置信息（record 组件 pos 即实现）。 */
public sealed interface AstNode permits Statement, Expression {
    Position pos();
}
