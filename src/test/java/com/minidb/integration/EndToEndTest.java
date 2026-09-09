package com.minidb.integration;

import com.minidb.MiniDB;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端测试：CLI 手跑 demo.sql 输出存档进报告。
 */
class EndToEndTest {

    @Test
    void testDemoSqlScript() {
        // 读取 demo.sql
        InputStream is = getClass().getClassLoader().getResourceAsStream("demo.sql");
        assertNotNull(is, "demo.sql 应存在于 test resources");

        String script;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            script = sb.toString();
        } catch (IOException e) {
            fail("读取 demo.sql 失败: " + e.getMessage());
            return;
        }

        // 将脚本写入临时文件，通过 MiniDB.runScript 执行
        File tempFile;
        try {
            tempFile = File.createTempFile("demo", ".sql");
            tempFile.deleteOnExit();
            try (FileWriter fw = new FileWriter(tempFile)) {
                fw.write(script);
            }
        } catch (IOException e) {
            fail("创建临时文件失败: " + e.getMessage());
            return;
        }

        // 捕获 stdout
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            MiniDB db = new MiniDB();
            db.runScript(tempFile.getAbsolutePath());
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        // 验证输出非空
        assertFalse(output.isEmpty(), "CLI 输出不应为空");

        // 验证包含关键输出
        assertTrue(output.contains("已创建") || output.contains("插入") || output.contains("行"),
                "输出应包含 DDL/DML 反馈");
    }

    @Test
    void testTraceOutput() {
        // 捕获 .trace 输出
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            MiniDB db = new MiniDB();
            // 先建表
            db.executeSql("CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT)");
            db.executeSql("INSERT INTO student VALUES (1, 'Tom', 90.5)");
            db.executeSql("INSERT INTO student VALUES (2, 'Alice', 85.0)");
            // trace 一条带 WHERE 的 SELECT
            db.traceSql("SELECT name FROM student WHERE score >= 90.0");
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        // 验证五段输出
        assertTrue(output.contains("── Tokens ──"), "应包含 Tokens 段");
        assertTrue(output.contains("── AST ──"), "应包含 AST 段");
        assertTrue(output.contains("── Plan(优化前) ──"), "应包含 Plan(优化前) 段");
        assertTrue(output.contains("── Plan(优化后) ──"), "应包含 Plan(优化后) 段");
        assertTrue(output.contains("── Result ──"), "应包含 Result 段");
    }
}
