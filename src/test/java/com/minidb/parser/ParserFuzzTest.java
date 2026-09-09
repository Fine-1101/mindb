package com.minidb.parser;

import com.minidb.ast.Statement;
import com.minidb.common.MiniDbException;
import com.minidb.common.Position;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.lexer.TokenType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser/Lexer Fuzz 测试（固定种子，可复现）。
 *
 * <p>从 samples.sql 与语法模板生成随机变异输入，每轮检查不变量：
 * <ol>
 *   <li>除 MiniDbException 外不允许任何异常/Error（崩溃即缺陷）；</li>
 *   <li>Lexer 成功时 Token 流末尾必须存在 EOF；</li>
 *   <li>每个 Token 的 line/column >= 1；</li>
 *   <li>Token 位置非递减（后一个不能跑到前一个前面）；</li>
 *   <li>STRING Token 的 value 是正确解码结果（'' 转义不残留）。</li>
 * </ol>
 */
class ParserFuzzTest {

    /** 固定种子：同 seed + 同代码 => 完全相同的变异序列，便于复现失败。 */
    private static final long SEED = 20260909L;

    /** 总轮数（要求 >= 10000）。 */
    private static final int ROUNDS = 20000;

    /** 反馈报告里展示的样例条数（fuzz 全量太多，仅展示前 N 条）。 */
    private static final int SAMPLE_SHOW = 30;

    private static final Lexer LEXER = new Lexer();
    private static final Parser PARSER = new Parser();

    /** 随机插入/替换用的字符表（覆盖关键字字母、运算符、分隔符、引号、空白）。 */
    private static final String CHARSET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ ,;.()=<>!+-*/'\"\t\n";

    private static final String OPERATORS = "+-*/=<>!";

    /** 字面量模板（插入/替换用）。 */
    private static final String[] LITERALS = {
            "123", "0", "99999", "3.14", "0.5", "'Tom''s book'", "'你好世界'",
            "''", "'a;b'", "'Tom'",
    };

    // ==================================================================
    // 语料
    // ==================================================================

    private static List<String> corpus() {
        List<String> base = new ArrayList<>();
        String samples = readResource("/samples.sql");
        if (samples != null && !samples.isBlank()) {
            base.add(samples);
        }
        base.add("SELECT * FROM t;");
        base.add("CREATE TABLE users (id INT, name VARCHAR(32), score FLOAT);");
        base.add("INSERT INTO users (id, name) VALUES (1, 'Tom''s book'), (2, 'Alice');");
        base.add("DELETE FROM users WHERE id = 1;");
        base.add("SELECT id, name FROM student WHERE score >= 90.0 AND name != 'x' OR NOT id = 3;");
        base.add("SELECT * FROM t WHERE (a = 1 OR b = 2) AND NOT (c < 3 OR d >= 4);");
        base.add("SELECT * FROM t WHERE a + b * c - d / e > 10 AND x <= -5;");
        base.add("-- only comment\n/* block */");
        base.add(";;");
        base.add("INSERT INTO t VALUES (1), (2), (3);");
        return base;
    }

    private static String readResource(String name) {
        try (InputStream in = ParserFuzzTest.class.getResourceAsStream(name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ==================================================================
    // 主测试
    // ==================================================================

    @Test
    void fuzzTenThousandRoundsKeepsInvariants() throws Exception {
        Random random = new Random(SEED);
        List<String> corpus = corpus();
        List<String> failures = new ArrayList<>();

        for (int round = 0; round < ROUNDS; round++) {
            String input;
            try {
                input = mutate(random, corpus);
            } catch (RuntimeException e) {
                // 变异器自身出问题也算失败
                failures.add(formatFailure(round, "", e, null));
                continue;
            }
            try {
                checkRound(round, input);
            } catch (MiniDbException expected) {
                // 语法/词法错误允许，但异常自身的位置必须合法
                Position p = expected.pos();
                if (p == null || p.line() < 1 || p.col() < 1) {
                    failures.add(formatFailure(round, input, null,
                            "MiniDbException 位置非法: " + p));
                }
            } catch (Throwable t) {
                failures.add(formatFailure(round, input, t, null));
            }
        }

        // ---- 反馈：固定 seed 重放前 SAMPLE_SHOW 轮样例（与全量完全同一序列）----
        String report = buildFuzzSampleReport(random, corpus, failures.size());
        writeFuzzReportFile(report);
        System.out.print(report);

        assertTrue(failures.isEmpty(),
                "Fuzz 发现 " + failures.size() + " 处不变量破坏，前 5 条：\n"
                        + String.join("\n====\n", failures.subList(0, Math.min(5, failures.size())))
                        + "\n（完整清单已打印到 stderr）");
    }

    /** 固定 seed 重放前 30 条变异输入并逐一打印输入与结果，用于人工检视。 */
    private static String buildFuzzSampleReport(Random seedSource, List<String> corpus,
                                                int crashCount) {
        // 独立 Random(SEED) 重放 => 与主循环前 30 轮输入逐字相同
        Random replay = new Random(SEED);
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================\n");
        sb.append(" MiniDB Parser/Lexer Fuzz 样例报告（seed=").append(SEED)
                .append("，固定种子可复现）\n");
        sb.append(" 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append(" 全量轮数: ").append(ROUNDS).append(" | 崩溃数: ").append(crashCount)
                .append(" | 下列展示前 ").append(Math.min(SAMPLE_SHOW, ROUNDS))
                .append(" 条样例\n");
        sb.append("================================================================\n");

        int shown = Math.min(SAMPLE_SHOW, ROUNDS);
        for (int round = 0; round < shown; round++) {
            String sql = mutate(replay, corpus);
            sb.append("\n------------ Fuzz 样例 ").append(String.format("%02d", round + 1))
                    .append('/').append(shown).append(" ------------\n");
            sb.append("输入 SQL: ").append(fuzzClip(fuzzEsc(sql))).append('\n');
            sb.append("结果: ").append(describeRound(sql)).append('\n');
        }

        sb.append("\n================================================================\n");
        sb.append(" 说明: 上述 30 条与全量 20000 轮使用同一 Random(SEED=").append(SEED)
                .append(") 序列的前 30 条；"
                + "除 MiniDbException 外任何异常都视为缺陷，全量运行已断言为零。\n");
        sb.append("================================================================\n");
        return sb.toString();
    }

    /** 对单条输入给出结果描述：LEXER/PARSER 错误，或 Token 统计 + 解析状态。 */
    private static String describeRound(String sql) {
        List<Token> tokens;
        try {
            tokens = LEXER.tokenize(sql);
        } catch (MiniDbException e) {
            return "LEXER 错误 [" + e.phase() + " @ " + e.pos() + "] "
                    + fuzzOneLine(e.getMessage());
        }
        Position eof = tokens.get(tokens.size() - 1).pos();
        String parseInfo;
        try {
            List<Statement> stmts = PARSER.parseScript(tokens);
            parseInfo = "PARSER OK（" + stmts.size() + " 条语句）";
        } catch (MiniDbException e) {
            parseInfo = "PARSER 错误 [" + e.phase() + " @ " + e.pos() + "] "
                    + fuzzOneLine(e.getMessage());
        }
        return "LEXER OK（" + tokens.size() + " Tokens, EOF @ " + eof + "）; " + parseInfo;
    }

    private static String fuzzOneLine(String s) {
        String t = s == null ? "" : s.replace("\r", " ").replace("\n", " | ");
        return t.length() <= 180 ? t : t.substring(0, 180) + "...";
    }

    private static String fuzzClip(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "...(截断, 实际 " + s.length() + " 字符)";
    }

    private static String fuzzEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static void writeFuzzReportFile(String content) {
        try {
            Path p = Paths.get("target", "parser-fuzz-report.txt").toAbsolutePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("[ParserFuzzTest] 报告已写入: " + p + " (UTF-8)");
        } catch (IOException e) {
            System.out.println("[ParserFuzzTest] 报告文件写入失败: " + e);
        }
    }

    /** 单轮校验：Lexer + Parser 全链路 + 五项不变量。 */
    private static void checkRound(int round, String sql) throws MiniDbException {
        List<Token> tokens = LEXER.tokenize(sql);

        // 不变量 2：末尾 EOF
        Token last = tokens.get(tokens.size() - 1);
        if (last.type() != TokenType.EOF) {
            throw new AssertionError("Token 流末尾不是 EOF: " + last);
        }

        // 不变量 3 + 4：位置合法且非递减
        Position prev = null;
        for (Token t : tokens) {
            Position p = t.pos();
            if (p.line() < 1 || p.col() < 1) {
                throw new AssertionError("Token 位置非法: " + p + " token=" + t);
            }
            if (prev != null && compare(prev, p) > 0) {
                throw new AssertionError("Token 位置非递减被破坏: " + prev + " -> " + p
                        + " token=" + t);
            }
            prev = p;
            // 不变量 5：STRING value 必须正确解码
            if (t.type() == TokenType.STRING) {
                String decoded = decodeSqlString(t.text());
                if (decoded == null || !decoded.equals(t.value())) {
                    throw new AssertionError("STRING value 错误: text=" + t.text()
                            + " value=" + t.value() + " 期望=" + decoded);
                }
            }
        }

        // 再跑 Parser（含 parseScript 错误恢复路径），仅允许 MiniDbException
        PARSER.parseScript(tokens);
    }

    /** 位置字典序比较：a>b 返回正数。 */
    private static int compare(Position a, Position b) {
        if (a.line() != b.line()) {
            return Integer.compare(a.line(), b.line());
        }
        return Integer.compare(a.col(), b.col());
    }

    /** 用 SQL 规则解码单引号字符串（'' -> '），非法/未闭合返回 null。 */
    private static String decodeSqlString(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '\''
                || raw.charAt(raw.length() - 1) != '\'') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int i = 1;
        int end = raw.length() - 1;
        while (i < end) {
            char c = raw.charAt(i);
            if (c == '\'') {
                if (i + 1 < end && raw.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i += 2;
                } else {
                    return null; // 内部出现孤立引号 => 非法
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String formatFailure(int round, String input, Throwable t, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("seed=").append(SEED).append(" round=").append(round)
                .append('\n').append("input=[").append(esc(input)).append(']');
        if (note != null) {
            sb.append("\nnote=").append(note);
        }
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append("\nexception=").append(t.getClass().getName())
                    .append(": ").append(t.getMessage()).append('\n').append(sw);
        }
        System.err.println(sb);
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\r", "\\r").replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    // ==================================================================
    // 变异器
    // ==================================================================

    private static String mutate(Random random, List<String> corpus) {
        // 取一个语料基准，50% 概率拼第二条
        StringBuilder sb = new StringBuilder(corpus.get(random.nextInt(corpus.size())));
        if (random.nextBoolean()) {
            sb.append(random.nextBoolean() ? "; " : "\n")
                    .append(corpus.get(random.nextInt(corpus.size())));
        }

        int mutations = 1 + random.nextInt(6);
        for (int m = 0; m < mutations; m++) {
            applyRandomMutation(random, sb);
            if (sb.length() > 400) {
                sb.setLength(200 + random.nextInt(sb.length() - 200)); // 截断控长
            }
        }
        return sb.toString();
    }

    private static void applyRandomMutation(Random random, StringBuilder sb) {
        if (sb.isEmpty()) {
            sb.append(corpus().get(0));
            return;
        }
        switch (random.nextInt(10)) {
            case 0: // 1. 随机删除字符
                sb.deleteCharAt(random.nextInt(sb.length()));
                break;
            case 1: // 2. 随机插入字符
                sb.insert(random.nextInt(sb.length() + 1), randChar(random, CHARSET));
                break;
            case 2: // 3. 随机替换字符
                sb.setCharAt(random.nextInt(sb.length()), randChar(random, CHARSET));
                break;
            case 3: // 4. 截断 SQL
                sb.setLength(random.nextInt(sb.length() + 1));
                break;
            case 4: { // 5. 拼接（追加一段新内容）
                List<String> c = corpus();
                String tail = c.get(random.nextInt(c.size()));
                if (random.nextBoolean() && sb.length() > 0 && sb.charAt(sb.length() - 1) != ';') {
                    sb.append(';');
                }
                sb.append(tail);
                break;
            }
            case 5: // 6. 随机增加/删除括号
                toggleChar(random, sb, '(', ')');
                break;
            case 6: // 7. 随机增加/删除分号
                toggleChar(random, sb, ';', ';');
                break;
            case 7: { // 8. 关键字大小写随机变换（随机字母翻转大小写）
                int idx = random.nextInt(sb.length());
                char c = sb.charAt(idx);
                if (Character.isLetter(c)) {
                    sb.setCharAt(idx, Character.isUpperCase(c)
                            ? Character.toLowerCase(c) : Character.toUpperCase(c));
                } else {
                    sb.insert(idx, randChar(random, "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"));
                }
                break;
            }
            case 8: { // 9. 运算符随机替换
                int idx = random.nextInt(sb.length());
                char c = sb.charAt(idx);
                if (OPERATORS.indexOf(c) >= 0) {
                    sb.setCharAt(idx, OPERATORS.charAt(random.nextInt(OPERATORS.length())));
                } else {
                    int at = random.nextInt(sb.length() + 1);
                    sb.insert(at, OPERATORS.charAt(random.nextInt(OPERATORS.length())));
                }
                break;
            }
            default: { // 10. 字面量随机替换（覆盖随机区间为某个字面量模板）
                int len = Math.min(1 + random.nextInt(5), Math.max(1, sb.length()));
                int start = random.nextInt(sb.length() - len + 1);
                sb.replace(start, start + len,
                        LITERALS[random.nextInt(LITERALS.length)]);
                break;
            }
        }
    }

    private static char randChar(Random random, String charset) {
        return charset.charAt(random.nextInt(charset.length()));
    }

    /** 随机插入一个目标字符；若文本里已存在目标字符，则 50% 概率删除一个。 */
    private static void toggleChar(Random random, StringBuilder sb, char insert, char remove) {
        if (random.nextBoolean() || sb.indexOf(String.valueOf(remove)) < 0) {
            sb.insert(random.nextInt(sb.length() + 1), insert);
        } else {
            int idx;
            do {
                idx = random.nextInt(sb.length());
            } while (sb.charAt(idx) != remove);
            sb.deleteCharAt(idx);
        }
    }
}
