package com.minidb.ast;

/** 语句节点。D5 M0 冻结：五类 SQL。 */
public sealed interface Statement extends AstNode
        permits CreateTableStmt, InsertStmt, SelectStmt, DeleteStmt, UpdateStmt {
}
