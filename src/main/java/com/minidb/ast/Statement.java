package com.minidb.ast;

/** 语句节点：四类 SQL。 */
public sealed interface Statement extends AstNode
        permits CreateTableStmt, InsertStmt, SelectStmt, DeleteStmt {
}
