package com.minidb.parser;

import com.minidb.ast.SelectStmt;
import com.minidb.ast.Statement;
import com.minidb.common.MiniDbException;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser 测试反馈输出的公共支撑（D4 起成为约定：每个 Parser 测试类都提供 feedbackReport）。
 *
 * <p>逐条打印「输入 SQL + 具体解析结果（AST 或 PARSER/LEXER 错误）」，
 * 统计通过/未通过，并写入 target/&lt;reportFile&gt;（UTF-8）。
 * 失败详情包含 seed 无关的输入原文，便于直接复制复现。
 */
final class ParserTestFeedback {

    private ParserTestFeedback() {}

    /** 用例执行形态。 */
    enum Mode {
        /** 单条语句：Parser.parse。 */
        STATEMENT,
        /** 多语句脚本：Parser.parseScript。 */
        SCRIPT,
        /** WHERE 条件表达式：自动包裹为 SELECT * FROM t WHERE &lt;sql&gt;。 */
        WHERE_EXPR
    }

    /** 单条演示用例：名称 + SQL + 形态 + 是否期望抛 PARSER 错误。 */
    record DemoCase(String name, String sql, Mode mode, boolean expectError) {
    }

    record Result(int pass, int fail) {
    }

    /**
     * 生成反馈报告：打印到控制台并写文件，返回通过/未通过计数。
     * 调用方应断言 {@code result.fail() == 0}。
     */
    static Result report(String title, String reportFile, List<DemoCase> cases) {
        StringBuilder sb = new StringBuilder();
        List<String> failures = new ArrayList<>();
        int pass = 0;
        int fail = 0;

        sb.append("================================================================\n");
        sb.append(' ').append(title).append('\n');
        sb.append(" 生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append(" 用例总数: ").append(cases.size()).append('\n');
        sb.append("================================================================\n");

        for (int i = 0; i < cases.size(); i++) {
            DemoCase c = cases.get(i);
            sb.append("\n------------ 用例 ").append(String.format("%02d", i + 1))
                    .append(" [").append(c.name()).append("] ------------\n");
            sb.append("输入 SQL: ").append(clip(esc(c.sql()))).append('\n');

            try {
                List<Statement> stmts = execute(c);
                if (c.expectError()) {
                    fail++;
                    failures.add(c.name() + "（期望抛 PARSER 错误，实际解析成功）");
                    sb.append("结果: 期望抛错，但解析成功  >>> 未通过 <<<\n");
                    appendStatements(sb, c, stmts);
                } else {
                    pass++;
                    sb.append("结果: 成功（").append(stmts.size()).append(" 条语句）  >>> 通过 <<<\n");
                    appendStatements(sb, c, stmts);
                }
            } catch (MiniDbException e) {
                if (c.expectError() && e.phase() == MiniDbException.Phase.PARSER) {
                    pass++;
                    sb.append("结果: 抛 PARSER 错误（符合预期）  >>> 通过 <<<\n");
                } else if (c.expectError() && e.phase() == MiniDbException.Phase.LEXER) {
                    pass++; // 词法层错误也算“按预期报错”（如未闭合字符串）
                    sb.append("结果: 抛 LEXER 错误（符合预期）  >>> 通过 <<<\n");
                } else {
                    fail++;
                    failures.add(c.name() + "（实际异常: " + e + "）");
                    sb.append("结果: 异常不符预期  >>> 未通过 <<<\n");
                }
                sb.append("  异常: [").append(e.phase()).append(" @ ").append(e.pos())
                        .append("] ").append(oneLine(e.getMessage())).append('\n');
            } catch (Exception other) {
                fail++;
                failures.add(c.name() + "（意外异常: " + other + "）");
                sb.append("结果: 意外异常  >>> 未通过 <<<\n");
                sb.append("  异常: ").append(other).append('\n');
            }
        }

        sb.append("\n================================================================\n");
        sb.append(" 汇总: 共 ").append(cases.size()).append(" 条用例, 通过 ")
                .append(pass).append(" 条, 未通过 ").append(fail).append(" 条\n");
        if (fail > 0) {
            sb.append(" 未通过用例:\n");
            for (String f : failures) {
                sb.append("   - ").append(f).append('\n');
            }
        }
        sb.append("================================================================\n");

        String text = sb.toString();
        System.out.print(text);
        writeReport(reportFile, text);
        return new Result(pass, fail);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static List<Statement> execute(DemoCase c) throws MiniDbException {
        return switch (c.mode()) {
            case STATEMENT -> List.of(new Parser().parse(lex(c.sql())));
            case SCRIPT -> new Parser().parseScript(lex(c.sql()));
            case WHERE_EXPR -> List.of(new Parser().parse(
                    lex("SELECT * FROM t WHERE " + c.sql() + ";")));
        };
    }

    /** 打印成功结果：WHERE 形态只展示表达式；其余展示每条语句的完整 AST。 */
    private static void appendStatements(StringBuilder sb, DemoCase c, List<Statement> stmts) {
        if (c.mode() == Mode.WHERE_EXPR) {
            SelectStmt stmt = (SelectStmt) stmts.get(0);
            sb.append("  where = ").append(stmt.where()).append('\n');
            return;
        }
        for (Statement s : stmts) {
            sb.append("  [").append(s.getClass().getSimpleName()).append("] ")
                    .append(s).append('\n');
        }
    }

    private static List<Token> lex(String sql) throws MiniDbException {
        return new Lexer().tokenize(sql);
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replace("\r", " ").replace("\n", " | ");
        return t.length() <= 220 ? t : t.substring(0, 220) + "...";
    }

    private static String clip(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "...(截断, 实际 " + s.length() + " 字符)";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static void writeReport(String reportFile, String content) {
        try {
            Path p = Paths.get("target", reportFile).toAbsolutePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("[ParserTestFeedback] 报告已写入: " + p + " (UTF-8)");
        } catch (IOException e) {
            System.out.println("[ParserTestFeedback] 报告文件写入失败: " + e);
        }
    }
}
