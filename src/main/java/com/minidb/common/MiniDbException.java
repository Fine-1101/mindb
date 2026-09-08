package com.minidb.common;

/** 统一错误：阶段 + 位置 + 原因。CLI 按阶段分报告，验收要求现场判断错误属于哪个阶段。 */
public class MiniDbException extends Exception {
    public enum Phase { LEXER, PARSER, SEMANTIC, PLAN }

    private final Phase phase;
    private final Position pos;

    public MiniDbException(Phase phase, Position pos, String message) {
        super(message);
        this.phase = phase;
        this.pos = pos;
    }

    public Phase phase() {
        return phase;
    }

    public Position pos() {
        return pos;
    }

    @Override
    public String toString() {
        return "[" + phase + " 错误 @ " + pos + "] " + getMessage();
    }
}
