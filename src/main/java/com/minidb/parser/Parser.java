package com.minidb.parser;

import com.minidb.ast.BinaryExpr;
import com.minidb.ast.BinaryOp;
import com.minidb.ast.ColumnRef;
import com.minidb.ast.CreateTableStmt;
import com.minidb.ast.DeleteStmt;
import com.minidb.ast.Expression;
import com.minidb.ast.FuncCall;
import com.minidb.ast.InsertStmt;
import com.minidb.ast.Literal;
import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.ast.UnaryExpr;
import com.minidb.ast.UnaryOp;
import com.minidb.catalog.ColumnDef;
import com.minidb.common.DataType;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Token;
import com.minidb.lexer.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 递归下降 SQL Parser。
 *
 * <p>支持四类语句：CREATE TABLE / INSERT INTO ... VALUES / SELECT ... FROM [WHERE] /
 * DELETE FROM [WHERE]，以及表达式优先级：
 *
 * <pre>
 * OR
 *  &lt; AND
 *  &lt; NOT（一元）
 *  &lt; 比较（= == != &lt; &lt;= &gt; &gt;=，其中 = 与 == 都映射 BinaryOp.EQ）
 *  &lt; 加减（+ -）
 *  &lt; 乘除（* /）
 *  &lt; NEG（一元负号）
 *  &lt; 原子（字面量 / 列引用 / 括号）
 * </pre>
 *
 * <p>错误诊断：语法错误抛出 MiniDbException(Phase.PARSER, 出错Token.pos, message)。
 * message 包含 unexpected token 原文（如 FROM (KW_FROM)）、行列号与可读的 expected 集合
 * （如 [INT / FLOAT / VARCHAR]、[* / identifier]）。
 *
 * <p>parseScript 具备单条语句错误恢复：坏语句记录错误后跳到下一个 ';' 继续解析后续语句；
 * 若存在错误，最终抛第一条错误的 MiniDbException，且 message 汇总全部错误位置。
 * 公共 API（parse / parseScript 签名）保持不变。
 */
public class Parser {

    private static final Position SYNTHETIC_EOF_POS = new Position(1, 1);

    /** 单条已记录的语法错误：位置 + 完整诊断文本。 */
    private record ParseIssue(Position pos, String message) {
    }

    private List<Token> tokens;
    private int index;

    /** 最近一次 parseScript 中成功解析出的语句（即使最终因错误抛异常也保留，供测试取证）。 */
    private List<Statement> recoveredStatements = List.of();

    /** 最近一次 parseScript 记录到的全部错误。 */
    private List<ParseIssue> recordedIssues = List.of();

    // ==================================================================
    // 公共 API
    // ==================================================================

    /**
     * 解析单条语句（允许带或不带结尾分号），多余输入报错。
     *
     * @param tokens Lexer 输出（通常以 EOF 结尾）
     * @return 单条语句 AST
     * @throws MiniDbException 语法错误（phase = PARSER）
     */
    public Statement parse(List<Token> tokens) throws MiniDbException {
        init(tokens);
        while (check(TokenType.SEMI)) {
            advance();
        }
        if (check(TokenType.EOF)) {
            throw error(peek(), TokenType.KW_CREATE, TokenType.KW_INSERT,
                    TokenType.KW_SELECT, TokenType.KW_DELETE);
        }
        Statement stmt = parseStatement();
        while (check(TokenType.SEMI)) {
            advance();
        }
        if (!check(TokenType.EOF)) {
            throw error(peek(), TokenType.SEMI);
        }
        return stmt;
    }

    /**
     * 解析多条以 ; 分隔的语句；空语句（;;）、纯注释输入返回空列表。
     *
     * <p>错误恢复语义：某条语句语法错误时，记录该错误并跳到下一个 ';'，
     * 从下一条语句继续解析，尽量保留后面合法的语句。若整个脚本存在错误，
     * 最终抛出第一条错误的 MiniDbException（pos 指向第一处出错 Token），
     * 且 message 汇总脚本中发现的全部错误位置清单。
     *
     * @param tokens Lexer 输出
     * @return 语句 AST 列表（保持源码顺序；无错误时）
     * @throws MiniDbException 脚本中存在语法错误（phase = PARSER）
     */
    public List<Statement> parseScript(List<Token> tokens) throws MiniDbException {
        init(tokens);
        List<Statement> statements = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>();
        while (true) {
            // 跳过空语句（;; 以及开头/结尾多余的 ;）
            while (check(TokenType.SEMI)) {
                advance();
            }
            if (check(TokenType.EOF)) {
                break;
            }
            try {
                Statement stmt = parseStatement();
                statements.add(stmt);
            } catch (MiniDbException e) {
                // 记录错误 -> 跳到下一个 ';' -> 继续解析后续语句
                issues.add(new ParseIssue(e.pos(), e.getMessage()));
                synchronize();
                continue;
            }
            if (check(TokenType.EOF)) {
                break;
            }
            if (!check(TokenType.SEMI)) {
                // 语句之间缺少分号
                MiniDbException e = error(peek(), TokenType.SEMI);
                issues.add(new ParseIssue(e.pos(), e.getMessage()));
                synchronize();
                continue;
            }
            advance(); // 消费 ';'
        }
        this.recoveredStatements = List.copyOf(statements);
        this.recordedIssues = List.copyOf(issues);
        if (!issues.isEmpty()) {
            throw aggregatedException(issues);
        }
        return statements;
    }

    /**
     * 最近一次 parseScript 成功解析出的语句（即使最终抛异常也保留）。
     * 仅包内测试取证使用，不属于公共 API。
     */
    List<Statement> recoveredStatements() {
        return recoveredStatements;
    }

    /** 最近一次 parseScript 记录到的错误个数。仅包内测试取证使用。 */
    int recordedIssueCount() {
        return recordedIssues.size();
    }

    // ==================================================================
    // 错误恢复
    // ==================================================================

    /**
     * 把解析位置推进到下一个语句边界：跳过当前 Token 直到 ';'（含），
     * 到 EOF 则停止。保证每次错误恢复都推进 token index，避免死循环。
     */
    private void synchronize() {
        while (!check(TokenType.EOF) && !check(TokenType.SEMI)) {
            advance();
        }
        if (check(TokenType.SEMI)) {
            advance();
        }
    }

    /** 汇总全部错误：异常 pos 取第一条错误，message 列出每条错误的位置与内容。 */
    private MiniDbException aggregatedException(List<ParseIssue> issues) {
        ParseIssue first = issues.get(0);
        StringBuilder msg = new StringBuilder("解析脚本时发现 ")
                .append(issues.size()).append(" 处语法错误（已尝试恢复并继续解析）:");
        for (int i = 0; i < issues.size(); i++) {
            ParseIssue issue = issues.get(i);
            msg.append("\n  错误 ").append(i + 1).append(" @ ").append(issue.pos())
                    .append(" (line ").append(issue.pos().line())
                    .append(", column ").append(issue.pos().col())
                    .append("): ").append(issue.message());
        }
        return new MiniDbException(MiniDbException.Phase.PARSER, first.pos(), msg.toString());
    }

    // ==================================================================
    // 语句
    // ==================================================================

    private Statement parseStatement() throws MiniDbException {
        switch (peek().type()) {
            case KW_CREATE:
                return parseCreateTable();
            case KW_INSERT:
                return parseInsert();
            case KW_SELECT:
                return parseSelect();
            case KW_DELETE:
                return parseDelete();
            default:
                throw error(peek(), TokenType.KW_CREATE, TokenType.KW_INSERT,
                        TokenType.KW_SELECT, TokenType.KW_DELETE);
        }
    }

    // ------------------------------------------------------------------
    // CREATE TABLE
    // ------------------------------------------------------------------

    private Statement parseCreateTable() throws MiniDbException {
        expect(TokenType.KW_CREATE);
        expect(TokenType.KW_TABLE);
        Token table = expect(TokenType.IDENT);
        expect(TokenType.LPAREN);

        List<ColumnDef> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (check(TokenType.COMMA)) {
            advance();
            columns.add(parseColumnDef());
        }
        expect(TokenType.RPAREN, TokenType.COMMA);
        // 约定：stmt.pos 为表名 token 位置（语义错误"表已存在"定位到表名，非语句首）
        return new CreateTableStmt(table.text(), columns, table.pos());
    }

    /** ident type，type 为 INT / FLOAT / VARCHAR(INT_LIT)。 */
    private ColumnDef parseColumnDef() throws MiniDbException {
        Token name = expect(TokenType.IDENT);
        Token typeToken = expectAny(TokenType.KW_INT, TokenType.KW_FLOAT, TokenType.KW_VARCHAR);
        switch (typeToken.type()) {
            case KW_INT:
                return new ColumnDef(name.text(), DataType.INT, 0);
            case KW_FLOAT:
                return new ColumnDef(name.text(), DataType.FLOAT, 0);
            default: // KW_VARCHAR
                expect(TokenType.LPAREN);
                Token len = expect(TokenType.INT_LIT);
                int maxLength = ((Number) len.value()).intValue();
                expect(TokenType.RPAREN);
                return new ColumnDef(name.text(), DataType.VARCHAR, maxLength);
        }
    }

    // ------------------------------------------------------------------
    // INSERT
    // ------------------------------------------------------------------

    private Statement parseInsert() throws MiniDbException {
        expect(TokenType.KW_INSERT);
        expect(TokenType.KW_INTO);
        Token table = expect(TokenType.IDENT);

        List<ColumnRef> columns = null;
        if (check(TokenType.LPAREN)) {
            advance();
            columns = new ArrayList<>();
            Token col = expect(TokenType.IDENT);
            columns.add(new ColumnRef(null, col.text(), col.pos()));
            while (check(TokenType.COMMA)) {
                advance();
                col = expect(TokenType.IDENT);
                columns.add(new ColumnRef(null, col.text(), col.pos()));
            }
            expect(TokenType.RPAREN, TokenType.COMMA);
        }

        expect(TokenType.KW_VALUES);
        List<List<Expression>> rows = new ArrayList<>();
        rows.add(parseRow());
        while (check(TokenType.COMMA)) {
            advance();
            rows.add(parseRow());
        }
        // 约定：stmt.pos 为表名 token 位置
        return new InsertStmt(table.text(), columns, rows, table.pos());
    }

    private List<Expression> parseRow() throws MiniDbException {
        expect(TokenType.LPAREN);
        List<Expression> row = new ArrayList<>();
        row.add(parseValueLiteral());
        while (check(TokenType.COMMA)) {
            advance();
            row.add(parseValueLiteral());
        }
        expect(TokenType.RPAREN, TokenType.COMMA);
        return row;
    }

    /** INSERT 的值只能是字面量：INT_LIT / FLOAT_LIT / STRING。 */
    private Expression parseValueLiteral() throws MiniDbException {
        Token t = peek();
        switch (t.type()) {
            case INT_LIT:
                advance();
                return new Literal(t.value(), DataType.INT, t.pos());
            case FLOAT_LIT:
                advance();
                return new Literal(t.value(), DataType.FLOAT, t.pos());
            case STRING:
                advance();
                return new Literal(t.value(), DataType.VARCHAR, t.pos());
            default:
                throw error(t, TokenType.INT_LIT, TokenType.FLOAT_LIT, TokenType.STRING);
        }
    }

    // ------------------------------------------------------------------
    // SELECT
    // ------------------------------------------------------------------

    private Statement parseSelect() throws MiniDbException {
        expect(TokenType.KW_SELECT);

        boolean distinct = false;
        if (check(TokenType.KW_DISTINCT)) {
            advance();
            distinct = true;
        }

        List<ColumnRef> columns = null;
        List<FuncCall> aggregates = null;
        if (check(TokenType.STAR)) {
            advance(); // SELECT *：columns == null
        } else {
            List<ColumnRef> cols = new ArrayList<>();
            List<FuncCall> aggs = new ArrayList<>();
            parseSelectItem(cols, aggs);
            while (check(TokenType.COMMA)) {
                advance();
                parseSelectItem(cols, aggs);
            }
            // 纯聚合时 columns 保持 null（与 SELECT * 同形，Planner 按 aggregates 分流）
            if (!cols.isEmpty()) {
                columns = cols;
            }
            if (!aggs.isEmpty()) {
                aggregates = aggs;
            }
        }

        expect(TokenType.KW_FROM);
        Token table = expect(TokenType.IDENT);

        Expression where = null;
        if (check(TokenType.KW_WHERE)) {
            advance();
            where = parseExpression();
        }
        // 约定：stmt.pos 为表名 token 位置
        return new SelectStmt(columns, aggregates, table.text(), where, distinct, table.pos());
    }

    /** SELECT 列表项：IDENT( → 聚合 FuncCall；否则普通列。混写由 Semantic 报"聚合函数不能与普通列混写"。 */
    private void parseSelectItem(List<ColumnRef> columns, List<FuncCall> aggregates) throws MiniDbException {
        if (!check(TokenType.IDENT)) {
            // 诊断：明确提示这里期待 * 或列名/聚合项（例如 SELECT FROM t）
            throw error(peek(), TokenType.STAR, TokenType.IDENT);
        }
        Token t = expect(TokenType.IDENT);
        if (check(TokenType.LPAREN)) {
            aggregates.add(parseFuncCallRest(t));
        } else {
            columns.add(new ColumnRef(null, t.text(), t.pos()));
        }
    }

    /** IDENT( 已消费到 '(' 处：解析 ) / * ) / expr )。func 大写规范化（未知名交 Semantic 报"未知聚合函数"）。 */
    private FuncCall parseFuncCallRest(Token name) throws MiniDbException {
        advance(); // 消费 '('
        Expression arg = null;
        if (check(TokenType.STAR)) {
            advance(); // COUNT(*)
        } else if (!check(TokenType.RPAREN)) {
            arg = parseExpression();
        }
        expect(TokenType.RPAREN);
        return new FuncCall(name.text().toUpperCase(Locale.ROOT), arg, name.pos());
    }

    // ------------------------------------------------------------------
    // DELETE
    // ------------------------------------------------------------------

    private Statement parseDelete() throws MiniDbException {
        expect(TokenType.KW_DELETE);
        expect(TokenType.KW_FROM);
        Token table = expect(TokenType.IDENT);

        Expression where = null;
        if (check(TokenType.KW_WHERE)) {
            advance();
            where = parseExpression();
        }
        // 约定：stmt.pos 为表名 token 位置
        return new DeleteStmt(table.text(), where, table.pos());
    }

    // ==================================================================
    // 表达式：OR < AND < NOT < 比较 < 加减 < 乘除 < NEG < 原子
    // ==================================================================

    private Expression parseExpression() throws MiniDbException {
        return parseOr();
    }

    private Expression parseOr() throws MiniDbException {
        Expression left = parseAnd();
        while (check(TokenType.KW_OR)) {
            Token op = advance();
            Expression right = parseAnd();
            left = new BinaryExpr(left, BinaryOp.OR, right, op.pos());
        }
        return left;
    }

    private Expression parseAnd() throws MiniDbException {
        Expression left = parseNot();
        while (check(TokenType.KW_AND)) {
            Token op = advance();
            Expression right = parseNot();
            left = new BinaryExpr(left, BinaryOp.AND, right, op.pos());
        }
        return left;
    }

    /** NOT 只作用于其后的一个比较（再低一层），从而 NOT 紧于 AND/OR。 */
    private Expression parseNot() throws MiniDbException {
        if (check(TokenType.KW_NOT)) {
            Token op = advance();
            Expression operand = parseNot();
            return new UnaryExpr(UnaryOp.NOT, operand, op.pos());
        }
        return parseComparison();
    }

    private Expression parseComparison() throws MiniDbException {
        Expression left = parseAdditive();
        if (isComparisonOperator(peek().type())) {
            Token op = advance();
            Expression right = parseAdditive();
            return new BinaryExpr(left, comparisonOp(op), right, op.pos());
        }
        return left;
    }

    private Expression parseAdditive() throws MiniDbException {
        Expression left = parseMultiplicative();
        while (check(TokenType.OP_ADD) || check(TokenType.OP_SUB)) {
            Token op = advance();
            BinaryOp binOp = op.type() == TokenType.OP_ADD ? BinaryOp.ADD : BinaryOp.SUB;
            Expression right = parseMultiplicative();
            left = new BinaryExpr(left, binOp, right, op.pos());
        }
        return left;
    }

    private Expression parseMultiplicative() throws MiniDbException {
        Expression left = parseUnary();
        while (check(TokenType.STAR) || check(TokenType.OP_DIV)) {
            Token op = advance();
            BinaryOp binOp = op.type() == TokenType.STAR ? BinaryOp.MUL : BinaryOp.DIV;
            Expression right = parseUnary();
            left = new BinaryExpr(left, binOp, right, op.pos());
        }
        return left;
    }

    /** 一元负号 NEG：-x。 */
    private Expression parseUnary() throws MiniDbException {
        if (check(TokenType.OP_SUB)) {
            Token op = advance();
            Expression operand = parseUnary();
            return new UnaryExpr(UnaryOp.NEG, operand, op.pos());
        }
        return parsePrimary();
    }

    /** 原子：INT/FLOAT/STRING 字面量、列引用、括号表达式。 */
    private Expression parsePrimary() throws MiniDbException {
        Token t = peek();
        switch (t.type()) {
            case INT_LIT:
                advance();
                return new Literal(t.value(), DataType.INT, t.pos());
            case FLOAT_LIT:
                advance();
                return new Literal(t.value(), DataType.FLOAT, t.pos());
            case STRING:
                advance();
                return new Literal(t.value(), DataType.VARCHAR, t.pos());
            case IDENT:
                advance();
                if (check(TokenType.LPAREN)) {
                    // IDENT( → 聚合函数（如 WHERE 中的 COUNT(x)，Semantic 层判定合法性并报错）
                    return parseFuncCallRest(t);
                }
                return new ColumnRef(null, t.text(), t.pos());
            case LPAREN:
                advance();
                Expression inner = parseExpression();
                expect(TokenType.RPAREN);
                return inner;
            default:
                throw error(t, TokenType.IDENT, TokenType.INT_LIT, TokenType.FLOAT_LIT,
                        TokenType.STRING, TokenType.LPAREN);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void init(List<Token> input) {
        if (input == null || input.isEmpty()) {
            this.tokens = List.of(new Token(TokenType.EOF, "", null, SYNTHETIC_EOF_POS));
        } else {
            this.tokens = input;
        }
        this.index = 0;
        this.recoveredStatements = List.of();
        this.recordedIssues = List.of();
    }

    private Token peek() {
        return tokens.get(index);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token advance() {
        Token t = peek();
        if (t.type() != TokenType.EOF) {
            index++;
        }
        return t;
    }

    /** 断言当前 Token 属于 expected 中的一种，否则抛出 PARSER 错误。 */
    private Token expectAny(TokenType first, TokenType... rest) throws MiniDbException {
        TokenType cur = peek().type();
        if (cur == first) {
            return advance();
        }
        for (TokenType t : rest) {
            if (cur == t) {
                return advance();
            }
        }
        TokenType[] expected = new TokenType[rest.length + 1];
        expected[0] = first;
        System.arraycopy(rest, 0, expected, 1, rest.length);
        throw error(peek(), expected);
    }

    private Token expect(TokenType type) throws MiniDbException {
        if (!check(type)) {
            throw error(peek(), type);
        }
        return advance();
    }

    /** 与 expect 相同，但给出多个合法后继（用于错误信息更友好）。 */
    private Token expect(TokenType type, TokenType alternative) throws MiniDbException {
        if (!check(type) && !check(alternative)) {
            throw error(peek(), type, alternative);
        }
        return advance();
    }

    private boolean isComparisonOperator(TokenType type) {
        return type == TokenType.OP_EQ || type == TokenType.OP_EQEQ
                || type == TokenType.OP_NE || type == TokenType.OP_LT
                || type == TokenType.OP_LE || type == TokenType.OP_GT
                || type == TokenType.OP_GE;
    }

    /** = 与 == 都映射为 EQ；其余一一对应。 */
    private BinaryOp comparisonOp(Token opToken) {
        switch (opToken.type()) {
            case OP_EQ:
            case OP_EQEQ:
                return BinaryOp.EQ;
            case OP_NE:
                return BinaryOp.NE;
            case OP_LT:
                return BinaryOp.LT;
            case OP_LE:
                return BinaryOp.LE;
            case OP_GT:
                return BinaryOp.GT;
            default:
                return BinaryOp.GE;
        }
    }

    // ==================================================================
    // 错误诊断
    // ==================================================================

    /**
     * 构造 PARSER 错误：unexpected token（含原文与类型）、行列号、可读 expected 集合。
     * 例如：unexpected token FROM (KW_FROM) at line 1, column 8, expected [* / identifier]
     */
    private MiniDbException error(Token unexpected, TokenType... expected) {
        Set<TokenType> expectedSet = new LinkedHashSet<>(List.of(expected));
        StringBuilder sb = new StringBuilder();
        sb.append("unexpected token ").append(describeToken(unexpected));
        sb.append(" at line ").append(unexpected.pos().line())
                .append(", column ").append(unexpected.pos().col());
        sb.append(", expected ").append(formatExpected(expectedSet));
        return new MiniDbException(MiniDbException.Phase.PARSER, unexpected.pos(), sb.toString());
    }

    /** 展示 unexpected Token：优先输出原文，并附 TokenType，如 FROM (KW_FROM)、t (IDENT)。 */
    private String describeToken(Token t) {
        if (t.type() == TokenType.EOF) {
            return "end of input (EOF)";
        }
        if (t.text() == null || t.text().isEmpty()) {
            return t.type().name();
        }
        return t.text() + " (" + t.type().name() + ")";
    }

    /** expected 集合的可读展示，如 [INT / FLOAT / VARCHAR]、[* / identifier]。 */
    private String formatExpected(Set<TokenType> expected) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (TokenType type : expected) {
            if (i++ > 0) {
                sb.append(" / ");
            }
            sb.append(friendlyName(type));
        }
        sb.append(']');
        return sb.toString();
    }

    /** 把 TokenType 翻译为用户可读名称：关键字大写、符号原样、其余给语义名称。 */
    private String friendlyName(TokenType type) {
        switch (type) {
            case IDENT: return "identifier";
            case INT_LIT: return "integer literal";
            case FLOAT_LIT: return "float literal";
            case STRING: return "string literal";
            case EOF: return "end of input";
            case STAR: return "*";
            case LPAREN: return "(";
            case RPAREN: return ")";
            case COMMA: return ",";
            case SEMI: return ";";
            case DOT: return ".";
            case OP_LT: return "<";
            case OP_LE: return "<=";
            case OP_GT: return ">";
            case OP_GE: return ">=";
            case OP_EQ: return "=";
            case OP_EQEQ: return "==";
            case OP_NE: return "!=";
            case OP_ADD: return "+";
            case OP_SUB: return "-";
            case OP_DIV: return "/";
            default:
                if (type.name().startsWith("KW_")) {
                    return type.name().substring("KW_".length());
                }
                return type.name();
        }
    }
}
