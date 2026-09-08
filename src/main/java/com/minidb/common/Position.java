package com.minidb.common;

/** 源码位置（行号+列号，1 起始），Token/AST/错误报告共用。 */
public record Position(int line, int col) {
    @Override
    public String toString() {
        return line + ":" + col;
    }
}
