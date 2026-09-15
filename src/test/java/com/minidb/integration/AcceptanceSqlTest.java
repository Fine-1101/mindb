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

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcceptanceSqlTest {

    private static final Pattern EXPECT = Pattern.compile(
            "^--\\s*expect:\\s*(OK|LEXER|PARSER|SEMANTIC)\\s*$");

    private record Case(int num, String expect, String sql) {
    }

    @Test
    void runAcceptanceTestSuite() throws Exception {
        List<Case> cases = loadCases();
        System.out.println("\n========== MiniDB 验收测试 共 " + cases.size() + " 条 ==========\n");

        MiniDB db = new MiniDB();
        List<String> failures = new ArrayList<>();
        int pass = 0;

        for (Case c : cases) {
            String out = run(db, c.sql());
            String strippedOut = out.strip();
            String condensedOut = strippedOut.replace('\n', ' ').replace('\r', ' ');

            boolean ok;
            String detail;

            if ("OK".equals(c.expect())) {
                // 期望成功：输出不能含"错误"
                ok = !strippedOut.contains("错误") && !strippedOut.isBlank();
                detail = ok ? condensedOut : "❌ 应成功却报错 → " + condensedOut;
            } else {
                // 期望报错：输出必须包含 "[LEXER" / "[PARSER" / "[SEMANTIC"
                ok = strippedOut.contains("[" + c.expect());
                detail = ok ? condensedOut : "❌ 期望[" + c.expect() + "] 实际 → " + condensedOut;
            }

            String tag = ok ? "[PASS]" : "[FAIL]";
            System.out.printf("%s #%02d  %s%n", tag, c.num(), detail);

            if (ok) {
                pass++;
            } else {
                failures.add(String.format(
                        "#%02d 期望=%s  SQL=%s%n    输出: %s",
                        c.num(), c.expect(), c.sql().strip(), strippedOut));
            }
        }

        System.out.println("\n========== 结果: " + pass + "/" + cases.size()
                + " 通过" + (failures.isEmpty() ? " ✅" : " ❌") + " ==========\n");

        if (!failures.isEmpty()) {
            System.out.println("失败明细:\n" + String.join("\n", failures));
        }

        assertEquals(0, failures.size(), "有 " + failures.size() + " 条用例未通过");
    }

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

    private List<Case> loadCases() throws Exception {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/acceptance.sql")) {
            if (in == null) throw new RuntimeException("找不到 classpath:acceptance.sql");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String expect = null;
            StringBuilder sql = new StringBuilder();
            int num = 0;
            for (String line : content.split("\\R")) {
                String stripped = line.strip();
                Matcher m = EXPECT.matcher(stripped);
                if (m.matches()) {
                    expect = m.group(1);
                    sql = new StringBuilder();
                    continue;
                }
                if (expect == null || stripped.isEmpty() || stripped.startsWith("--")) {
                    continue;
                }
                sql.append(line).append('\n');
                if (stripped.endsWith(";")) {
                    num++;
                    cases.add(new Case(num, expect, sql.toString()));
                    expect = null;
                }
            }
        }
        return cases;
    }
}