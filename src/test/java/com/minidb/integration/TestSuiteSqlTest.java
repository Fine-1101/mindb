package com.minidb.integration;

import com.minidb.MiniDB;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量 SQL 测试集装置（隐藏测试集模拟）。
 *
 * <p>读取 classpath:testsuite.sql（标注格式 {@code -- expect: OK|LEXER|PARSER|SEMANTIC}），
 * 按文件顺序在同一 MiniDB 实例上逐条执行，校验实际结果与标注一致：
 * OK 语句应有输出且无错误；错误语句应打印对应 Phase（printError 格式 "[LEXER 错误 @ pos] ..."）。
 *
 * <p>全部用例跑完后统一汇总失败项，一次给出完整失败清单。
 */
class TestSuiteSqlTest {

    private static final Pattern EXPECT = Pattern.compile("^--\\s*expect:\\s*(OK|LEXER|PARSER|SEMANTIC)\\s*$");

    private record Case(String expect, String sql) {
    }

    @Test
    void runSqlTestSuite() throws Exception {
        List<Case> cases = loadCases();
        assertTrue(cases.size() >= 50, "测试集应至少 50 条，实际 " + cases.size());

        MiniDB db = new MiniDB();
        List<String> failures = new ArrayList<>();
        int okCount = 0;

        for (Case c : cases) {
            String out = run(db, c.sql());
            if ("OK".equals(c.expect())) {
                if (out.contains("错误")) {
                    failures.add("[应成功却报错] " + c.sql().strip() + "\n    输出: " + out.strip());
                } else if (out.isBlank()) {
                    failures.add("[应成功却无输出] " + c.sql().strip());
                } else {
                    okCount++;
                }
            } else {
                if (!out.contains("[" + c.expect())) {
                    failures.add("[期望 " + c.expect() + "] " + c.sql().strip() + "\n    输出: " + out.strip());
                } else {
                    okCount++;
                }
            }
        }

        assertTrue(failures.isEmpty(),
                "测试集 " + cases.size() + " 条，通过 " + okCount + "，失败 " + failures.size() + " 条:\n"
                        + String.join("\n", failures));
    }

    @Test
    void runScriptContinuesAfterLexError() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            MiniDB db = new MiniDB();
            db.runScript("src/test/resources/testsuite.sql");
        } finally {
            System.setOut(originalOut);
        }
        String out = baos.toString();
        // 词法错误之前的正常语句有输出
        assertTrue(out.contains("已创建"), "建表语句应有成功输出，实际: " + out);
        // 词法错误不中断脚本：其后各阶段的错误语句照常逐条报告
        assertTrue(out.contains("[LEXER 错误"), "应含词法错误，实际: " + out);
        assertTrue(out.contains("[PARSER 错误"), "词法错误后脚本应继续到语法错误段，实际: " + out);
        assertTrue(out.contains("[SEMANTIC 错误"), "脚本应继续到语义错误段，实际: " + out);
    }

    /** 捕获 stdout 执行一条 SQL（可含多语句），返回输出。 */
    private String run(MiniDB db, String sql) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            db.executeSql(sql);
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString();
    }

    /** 解析 testsuite.sql：标注行的下一条语句（以 ; 结束，可跨行）为一个用例。 */
    private List<Case> loadCases() throws Exception {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/testsuite.sql")) {
            assertTrue(in != null, "找不到 testsuite.sql");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String expect = null;
            StringBuilder sql = new StringBuilder();
            for (String line : content.split("\\R")) {
                String stripped = line.strip();
                Matcher m = EXPECT.matcher(stripped);
                if (m.matches()) {
                    expect = m.group(1);
                    sql = new StringBuilder();
                    continue;
                }
                if (expect == null || stripped.isEmpty() || stripped.startsWith("--")) {
                    continue; // 节横幅 / 空行 / 用例间注释
                }
                sql.append(line).append('\n');
                if (stripped.endsWith(";")) {
                    cases.add(new Case(expect, sql.toString()));
                    expect = null;
                }
            }
        }
        return cases;
    }
}
