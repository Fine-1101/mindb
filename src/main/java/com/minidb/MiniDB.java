package com.minidb;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

/**
 * MiniDB CLI 骨架。
 * 功能：显示提示符、读行、exit 退出（当前只回显）。
 */
public class MiniDB {

    private static final String PROMPT = "MiniDB> ";

    public static void main(String[] args) {
        System.out.println("Welcome to MiniDB!");
        System.out.println("Type 'exit' to quit.\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print(PROMPT);
                System.out.flush();

                String line = reader.readLine();
                if (line == null) {
                    // EOF
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                    System.out.println("Bye!");
                    break;
                }

                // 当前只回显
                System.out.println("Echo: " + line);
            }
        } catch (IOException e) {
            System.err.println("Error reading input: " + e.getMessage());
        }
    }
}
