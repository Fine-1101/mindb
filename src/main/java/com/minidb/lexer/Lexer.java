package com.minidb.lexer;

import com.minidb.common.MiniDbException;
import com.minidb.common.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 逐字符扫描的 SQL 词法分析器。
 *
 * <p>支持：大小写不敏感关键字（27 个 KW_*，D5 M0 冻结）、标识符、INT/FLOAT/STRING 字面量、
 * 单行(--)与多行(/* *&#47;)注释、双字符运算符(>= <= != ==)、单字符运算符与分隔符、
 * 1 起始的 line/column 位置记录，以及按统一 MiniDbException(Phase.LEXER) 报错。
 *
 * <p>注意：Token.text 一律保留输入中的原始文本（含原始大小写、含字符串引号），
 * Token.value 为字面量解析后的值（Integer/Double/String）。
 */
public class Lexer {

    /** 全部关键字（小写 -> TokenType），查找时大小写不敏感。D5 M0 冻结为 27 个。 */
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("create", TokenType.KW_CREATE),
            Map.entry("table", TokenType.KW_TABLE),
            Map.entry("insert", TokenType.KW_INSERT),
            Map.entry("into", TokenType.KW_INTO),
            Map.entry("values", TokenType.KW_VALUES),
            Map.entry("select", TokenType.KW_SELECT),
            Map.entry("from", TokenType.KW_FROM),
            Map.entry("where", TokenType.KW_WHERE),
            Map.entry("delete", TokenType.KW_DELETE),
            Map.entry("and", TokenType.KW_AND),
            Map.entry("or", TokenType.KW_OR),
            Map.entry("not", TokenType.KW_NOT),
            Map.entry("int", TokenType.KW_INT),
            Map.entry("float", TokenType.KW_FLOAT),
            Map.entry("varchar", TokenType.KW_VARCHAR),
            Map.entry("distinct", TokenType.KW_DISTINCT),
            Map.entry("update", TokenType.KW_UPDATE),
            Map.entry("set", TokenType.KW_SET),
            Map.entry("order", TokenType.KW_ORDER),
            Map.entry("by", TokenType.KW_BY),
            Map.entry("group", TokenType.KW_GROUP),
            Map.entry("join", TokenType.KW_JOIN),
            Map.entry("on", TokenType.KW_ON),
            Map.entry("null", TokenType.KW_NULL),
            Map.entry("is", TokenType.KW_IS),
            Map.entry("asc", TokenType.KW_ASC),
            Map.entry("desc", TokenType.KW_DESC));

    private String sql;
    private int length;
    private int index;
    private int line;
    private int col;

    public Lexer() {
    }

    /**
     * 将整段 SQL 切分为 Token 列表，末尾恒为一个 EOF Token。
     *
     * @param sql 待分析的 SQL 文本
     * @return Token 列表（最后一项必为 EOF）
     * @throws MiniDbException 遇到非法字符、未闭合字符串、非法数字、未闭合注释等词法错误
     *                         （phase 恒为 LEXER，pos 指向出错位置）
     */
    public List<Token> tokenize(String sql) throws MiniDbException {
        this.sql = sql == null ? "" : sql;
        this.length = this.sql.length();
        this.index = 0;
        this.line = 1;
        this.col = 1;

        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (atEnd()) {
                tokens.add(new Token(TokenType.EOF, "", null, currentPos()));
                break;
            }
            tokens.add(nextToken());
        }
        return tokens;
    }

    // ------------------------------------------------------------------
    // 主分派
    // ------------------------------------------------------------------

    private Token nextToken() throws MiniDbException {
        char c = current();
        if (isIdentStart(c)) {
            return scanIdentifierOrKeyword();
        }
        if (isDigit(c)) {
            return scanNumber();
        }
        if (c == '\'') {
            return scanString();
        }
        return scanOperatorOrDelimiter();
    }

    // ------------------------------------------------------------------
    // 空白与注释
    // ------------------------------------------------------------------

    /** 跳过空白与注释（单行 --、多行块注释），期间正确推进 line/col。 */
    private void skipWhitespaceAndComments() throws MiniDbException {
        while (!atEnd()) {
            char c = current();
            if (c == ' ' || c == '\t' || c == '\f') {
                advance();
            } else if (c == '\n' || c == '\r') {
                advanceLine(); // 统一处理 \n、\r、\r\n
            } else if (c == '-' && peekNext() == '-') {
                skipLineComment();
            } else if (c == '/' && peekNext() == '*') {
                skipBlockComment();
            } else {
                break;
            }
        }
    }

    /** 跳过从 -- 到行尾的注释（不含行终止符，终止符交给空白处理）。 */
    private void skipLineComment() {
        while (!atEnd()) {
            char c = current();
            if (c == '\n' || c == '\r') {
                break;
            }
            advance();
        }
    }

    /** 跳过跨行块注释，未闭合时报错。 */
    private void skipBlockComment() throws MiniDbException {
        Position start = currentPos();
        advance(); // '/'
        advance(); // '*'
        while (!atEnd()) {
            if (current() == '*' && peekNext() == '/') {
                advance();
                advance();
                return;
            }
            if (current() == '\n' || current() == '\r') {
                advanceLine();
            } else {
                advance();
            }
        }
        throw new MiniDbException(MiniDbException.Phase.LEXER, start,
                "未闭合的多行注释：缺少结束符 */");
    }

    // ------------------------------------------------------------------
    // 标识符 / 关键字
    // ------------------------------------------------------------------

    private Token scanIdentifierOrKeyword() {
        Position start = currentPos();
        int startIndex = index;
        while (!atEnd() && isIdentPart(current())) {
            advance();
        }
        String text = sql.substring(startIndex, index);
        TokenType keyword = KEYWORDS.get(text.toLowerCase(Locale.ROOT));
        TokenType type = keyword != null ? keyword : TokenType.IDENT;
        return new Token(type, text, null, start);
    }

    // ------------------------------------------------------------------
    // 数字（INT / FLOAT）
    // ------------------------------------------------------------------

    /**
     * 扫描数字字面量：整数或小数。数字后紧跟字母/数字/下划线/小数点视为非法数字，
     * 例如 123abc、1.2.3 均直接报 LEXER 错误，绝不拆成多个 Token。
     */
    private Token scanNumber() throws MiniDbException {
        Position start = currentPos();
        int startIndex = index;
        boolean isFloat = false;
        while (!atEnd() && isDigit(current())) {
            advance();
        }
        // 小数点：仅当其后紧跟数字才算浮点；否则（如 1.、1.x）视为非法数字。
        if (!atEnd() && current() == '.') {
            if (!(index + 1 < length && isDigit(sql.charAt(index + 1)))) {
                throw new MiniDbException(MiniDbException.Phase.LEXER, currentPos(),
                        "非法数字 '" + sql.substring(startIndex, Math.min(index + 1, length))
                                + "'：小数点后缺少数字");
            }
            advance(); // '.'
            isFloat = true;
            while (!atEnd() && isDigit(current())) {
                advance();
            }
        }
        // 数字直接后接标识符字符或另一个小数点 => 非法数字，例如 123abc / 1.2.3。
        if (!atEnd() && (current() == '.' || isIdentPart(current()))) {
            throw new MiniDbException(MiniDbException.Phase.LEXER, start,
                    "非法数字 '" + sql.substring(startIndex, index) + "'：后跟意外字符 '"
                            + current() + "'");
        }
        String text = sql.substring(startIndex, index);
        try {
            if (isFloat) {
                return new Token(TokenType.FLOAT_LIT, text, Double.valueOf(text), start);
            }
            return new Token(TokenType.INT_LIT, text, Integer.valueOf(text), start);
        } catch (NumberFormatException e) {
            throw new MiniDbException(MiniDbException.Phase.LEXER, start,
                    "数字字面量超出范围: '" + text + "'");
        }
    }

    // ------------------------------------------------------------------
    // 字符串（单引号，'' 转义）
    // ------------------------------------------------------------------

    /** 扫描单引号字符串，支持 SQL 的 '' 转义为单引号；value 为解码后的内容。 */
    private Token scanString() throws MiniDbException {
        Position start = currentPos();
        int startIndex = index;
        advance(); // 开引号
        StringBuilder content = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw new MiniDbException(MiniDbException.Phase.LEXER, start,
                        "未闭合的字符串字面量：缺少结束引号 '");
            }
            char c = current();
            if (c == '\'') {
                if (peekNext() == '\'') { // '' => 字符串中的一个单引号
                    content.append('\'');
                    advance();
                    advance();
                } else { // 结束引号
                    advance();
                    break;
                }
            } else if (c == '\n' || c == '\r') {
                // 允许字符串跨行，并正确推进行列。
                content.append(c);
                advanceLine();
            } else {
                content.append(c);
                advance();
            }
        }
        String text = sql.substring(startIndex, index);
        return new Token(TokenType.STRING, text, content.toString(), start);
    }

    // ------------------------------------------------------------------
    // 运算符与分隔符
    // ------------------------------------------------------------------

    /** 运算符：先匹配双字符(>= <= != ==，注释已先行处理)，再匹配单字符。 */
    private Token scanOperatorOrDelimiter() throws MiniDbException {
        Position start = currentPos();
        char c = current();

        // 双字符运算符优先
        if (c == '>' && peekNext() == '=') {
            return twoChar(TokenType.OP_GE, start);
        }
        if (c == '<' && peekNext() == '=') {
            return twoChar(TokenType.OP_LE, start);
        }
        if (c == '!' && peekNext() == '=') {
            return twoChar(TokenType.OP_NE, start);
        }
        if (c == '=' && peekNext() == '=') {
            return twoChar(TokenType.OP_EQEQ, start);
        }

        // 单字符运算符 / 分隔符
        TokenType type = singleCharType(c);
        if (type != null) {
            advance();
            return new Token(type, String.valueOf(c), null, start);
        }

        throw new MiniDbException(MiniDbException.Phase.LEXER, start,
                "非法字符 '" + c + "'");
    }

    private Token twoChar(TokenType type, Position start) {
        String text = sql.substring(index, index + 2);
        advance();
        advance();
        return new Token(type, text, null, start);
    }

    /** 单字符 Token 的类型表；不属于任何类型的字符返回 null（作为非法字符报错）。 */
    private TokenType singleCharType(char c) {
        switch (c) {
            case '<': return TokenType.OP_LT;
            case '>': return TokenType.OP_GT;
            case '=': return TokenType.OP_EQ;
            case '+': return TokenType.OP_ADD;
            case '-': return TokenType.OP_SUB;
            case '/': return TokenType.OP_DIV;
            case '(': return TokenType.LPAREN;
            case ')': return TokenType.RPAREN;
            case ',': return TokenType.COMMA;
            case ';': return TokenType.SEMI;
            case '.': return TokenType.DOT;
            case '*': return TokenType.STAR;
            default: return null;
        }
    }

    // ------------------------------------------------------------------
    // 字符与位置基础操作
    // ------------------------------------------------------------------

    private boolean atEnd() {
        return index >= length;
    }

    private char current() {
        return sql.charAt(index);
    }

    /** 返回下一个字符；已到末尾时返回 '\0'。 */
    private char peekNext() {
        return index + 1 < length ? sql.charAt(index + 1) : '\0';
    }

    private Position currentPos() {
        return new Position(line, col);
    }

    /** 前进一个普通字符（列号 +1）。 */
    private void advance() {
        index++;
        col++;
    }

    /**
     * 前进一个换行（\n 或 \r；\r\n 只算一次换行）。
     * 调用方需保证当前位置是换行符。
     */
    private void advanceLine() {
        if (current() == '\r' && index + 1 < length && sql.charAt(index + 1) == '\n') {
            index += 2;
        } else {
            index++;
        }
        line++;
        col = 1;
    }

    // ------------------------------------------------------------------
    // 字符类别
    // ------------------------------------------------------------------

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
