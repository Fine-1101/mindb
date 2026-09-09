package com.minidb;

import com.minidb.ast.*;
import com.minidb.buffer.BufferPool;
import com.minidb.buffer.DiskBufferPool;
import com.minidb.buffer.InMemoryBufferPool;
import com.minidb.catalog.*;
import com.minidb.common.MiniDbException;
import com.minidb.engine.Engine;
import com.minidb.lexer.Lexer;
import com.minidb.lexer.Token;
import com.minidb.parser.Parser;
import com.minidb.plan.*;
import com.minidb.planner.Optimizer;
import com.minidb.planner.Planner;
import com.minidb.semantic.SemanticAnalyzer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * MiniDB CLI 全链。
 *
 * <p>SQL → tokenize → parse → analyze → buildPlan → optimize → execute/executeQuery → 结果按表打印。
 * <p>点命令 {@code .trace <SQL>}：五段输出 Token 列表 → AST → 优化前 Plan → 优化后 Plan → 执行结果。
 * <p>{@code --script demo.sql} 批量执行；每条语句错误捕获打印后继续。
 */
public class MiniDB {

    private static final String PROMPT = "MiniDB> ";

    private final Lexer lexer = new Lexer();
    private final Parser parser = new Parser();
    private final Catalog catalog;
    private final BufferPool pool;
    private final Engine engine;
    private final SemanticAnalyzer semanticAnalyzer;
    private final Planner planner;
    private final Optimizer optimizer = new Optimizer();

    /** 默认内存模式。 */
    public MiniDB() {
        this(false);
    }

    /**
     * @param diskMode true = 磁盘模式（DiskBufferPool + PersistentCatalog + 重启恢复），
     *                 false = 内存模式（InMemoryBufferPool + MemoryCatalog）
     */
    public MiniDB(boolean diskMode) {
        if (diskMode) {
            this.catalog = new PersistentCatalog();
            this.pool = new DiskBufferPool(64);
        } else {
            this.catalog = new MemoryCatalog();
            this.pool = new InMemoryBufferPool();
        }
        this.engine = new Engine(catalog, pool);
        this.semanticAnalyzer = new SemanticAnalyzer(catalog);
        this.planner = new Planner(catalog);
        if (diskMode) {
            recoverFromDisk();
        }
    }

    /** 磁盘模式重启恢复：按文件页数重建表的页映射（页 id 0..n-1）。 */
    private void recoverFromDisk() {
        if (catalog instanceof PersistentCatalog pc && pool instanceof DiskBufferPool dp) {
            for (TableDef t : pc.getAllTables()) {
                engine.recoverTablePages(t.tableName(), dp.getTablePageCount(t.tableName().toLowerCase()));
            }
        }
    }

    public static void main(String[] args) {
        boolean diskMode = false;
        String scriptPath = null;
        for (String arg : args) {
            if ("--disk".equals(arg)) {
                diskMode = true;
            } else if (!arg.isEmpty()) {
                scriptPath = arg;
            }
        }

        MiniDB db = new MiniDB(diskMode);

        // --script 模式（--script demo.sql，可与 --disk 组合）
        if (scriptPath != null) {
            db.runScript(scriptPath);
            return;
        }

        // 交互模式
        System.out.println("Welcome to MiniDB!");
        System.out.println("Type 'exit' to quit.\n");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print(PROMPT);
                System.out.flush();

                String line = reader.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                    System.out.println("Bye!");
                    break;
                }

                // .trace 命令
                if (line.startsWith(".trace ")) {
                    String sql = line.substring(7).trim();
                    if (!sql.isEmpty()) {
                        db.traceSql(sql);
                    }
                    continue;
                }

                // 普通 SQL
                db.executeSql(line);
            }
        } catch (IOException e) {
            System.err.println("Error reading input: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 普通 SQL 执行
    // ------------------------------------------------------------------

    /** 执行单条 SQL，打印结果或错误。 */
    public void executeSql(String sql) {
        try {
            List<Token> tokens = lexer.tokenize(sql);
            List<Statement> stmts = parser.parseScript(tokens);
            for (Statement stmt : stmts) {
                executeStatement(stmt);
            }
        } catch (MiniDbException e) {
            printError(e);
        }
    }

    /** 执行单条语句（已 parse）。 */
    private void executeStatement(Statement stmt) throws MiniDbException {
        semanticAnalyzer.analyze(stmt);

        PlanNode plan = planner.plan(stmt);
        PlanNode optimized = optimizer.optimize(plan);

        if (stmt instanceof SelectStmt) {
            List<Object[]> results = engine.executeQuery(optimized);
            List<String> colNames = engine.getQueryColumnNames(optimized);
            printResultTable(colNames, results);
        } else {
            engine.execute(optimized);
            // DDL/DML 反馈
            if (stmt instanceof CreateTableStmt s) {
                System.out.println("表 " + s.tableName() + " 已创建");
            } else if (stmt instanceof InsertStmt s) {
                int rowCount = s.rows().size();
                System.out.println("插入 " + rowCount + " 行");
            } else if (stmt instanceof DeleteStmt) {
                System.out.println("删除完成");
            }
        }
    }

    // ------------------------------------------------------------------
    // .trace 五段输出
    // ------------------------------------------------------------------

    /** .trace 命令：Token 列表 → AST → 优化前 Plan → 优化后 Plan → 执行结果。 */
    public void traceSql(String sql) {
        try {
            // 1. Tokens
            List<Token> tokens = lexer.tokenize(sql);
            System.out.println("── Tokens ──");
            for (Token t : tokens) {
                if (t.type() == com.minidb.lexer.TokenType.EOF) break;
                System.out.println("  " + t.type() + "(" + t.text() + ") @" + t.pos());
            }

            // 2. AST
            List<Statement> stmts = parser.parseScript(tokens);
            System.out.println("── AST ──");
            for (Statement stmt : stmts) {
                System.out.println("  " + astToSExpr(stmt));
            }

            // 3 & 4 & 5: 对每条语句
            for (Statement stmt : stmts) {
                semanticAnalyzer.analyze(stmt);
                PlanNode plan = planner.plan(stmt);

                System.out.println("── Plan(优化前) ──");
                printPlanTree(plan, "  ");

                PlanNode optimized = optimizer.optimize(plan);
                System.out.println("── Plan(优化后) ──");
                printPlanTree(optimized, "  ");

                // 5. 执行结果
                System.out.println("── Result ──");
                if (stmt instanceof SelectStmt) {
                    List<Object[]> results = engine.executeQuery(optimized);
                    List<String> colNames = engine.getQueryColumnNames(optimized);
                    printResultTable(colNames, results);
                } else {
                    engine.execute(optimized);
                    if (stmt instanceof CreateTableStmt s) {
                        System.out.println("表 " + s.tableName() + " 已创建");
                    } else if (stmt instanceof InsertStmt s) {
                        System.out.println("插入 " + s.rows().size() + " 行");
                    } else if (stmt instanceof DeleteStmt) {
                        System.out.println("删除完成");
                    }
                }
            }
        } catch (MiniDbException e) {
            printError(e);
        }
    }

    // ------------------------------------------------------------------
    // --script 批量执行
    // ------------------------------------------------------------------

    /** 读取脚本文件并逐条执行。 */
    public void runScript(String filePath) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            String script = sb.toString();
            List<Token> tokens = lexer.tokenize(script);
            List<Statement> stmts = parser.parseScript(tokens);
            for (Statement stmt : stmts) {
                try {
                    executeStatement(stmt);
                } catch (MiniDbException e) {
                    printError(e);
                }
            }
        } catch (MiniDbException e) {
            printError(e);
        } catch (IOException e) {
            System.err.println("读取脚本文件失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 输出格式化
    // ------------------------------------------------------------------

    /** 按表打印查询结果（表头取自 Catalog）。 */
    private void printResultTable(List<String> colNames, List<Object[]> rows) {
        if (colNames.isEmpty() && rows.isEmpty()) {
            System.out.println("(空)");
            return;
        }

        // 计算每列宽度
        int[] widths = new int[colNames.size()];
        for (int i = 0; i < colNames.size(); i++) {
            widths[i] = colNames.get(i).length();
        }
        for (Object[] row : rows) {
            for (int i = 0; i < row.length && i < widths.length; i++) {
                String val = formatValue(row[i]);
                widths[i] = Math.max(widths[i], val.length());
            }
        }

        // 表头
        StringBuilder header = new StringBuilder();
        StringBuilder separator = new StringBuilder();
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) { header.append(" | "); separator.append("-+-"); }
            header.append(padRight(colNames.get(i), widths[i]));
            separator.append("-".repeat(widths[i]));
        }
        System.out.println(header);
        System.out.println(separator);

        // 数据行
        for (Object[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < colNames.size(); i++) {
                if (i > 0) line.append(" | ");
                String val = i < row.length ? formatValue(row[i]) : "NULL";
                line.append(padRight(val, widths[i]));
            }
            System.out.println(line);
        }
        System.out.println("(" + rows.size() + " 行)");
    }

    private String formatValue(Object val) {
        if (val == null) return "NULL";
        if (val instanceof Double d) {
            // 去掉不必要的小数位
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            return String.valueOf(d);
        }
        return val.toString();
    }

    private String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /** 打印计划树（缩进格式）。 */
    private void printPlanTree(PlanNode plan, String indent) {
        switch (plan) {
            case Project p -> {
                String cols = p.columns() != null ? String.join(",", p.columns()) : "*";
                System.out.println(indent + "Project[" + cols + "]");
                printPlanTree(p.child(), indent + "  ");
            }
            case Filter f -> {
                System.out.println(indent + "Filter[" + exprToString(f.condition()) + "]");
                printPlanTree(f.child(), indent + "  ");
            }
            case SeqScan s -> System.out.println(indent + "SeqScan(" + s.tableName() + ")");
            case CreateTablePlan p -> System.out.println(indent + "CreateTable(" + p.table().tableName() + ")");
            case InsertPlan p -> System.out.println(indent + "Insert(" + p.tableName() + ", " + p.rows().size() + " rows)");
            case DeletePlan p -> {
                String cond = p.condition() != null ? exprToString(p.condition()) : "ALL";
                System.out.println(indent + "Delete(" + p.tableName() + ", " + cond + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    // AST S-expression
    // ------------------------------------------------------------------

    private String astToSExpr(Statement stmt) {
        return switch (stmt) {
            case CreateTableStmt s -> "(create-table " + s.tableName() + " "
                    + s.columns().stream().map(c -> c.name() + ":" + c.type()).reduce("", (a, b) -> a + " " + b).trim()
                    + ")";
            case InsertStmt s -> "(insert " + s.tableName() + " " + s.rows().size() + " rows)";
            case SelectStmt s -> {
                StringBuilder sb = new StringBuilder("(select ");
                if (s.columns() == null) {
                    sb.append("*");
                } else {
                    sb.append(s.columns().stream().map(c -> "(col " + c.column() + ")").reduce("", (a, b) -> a + " " + b).trim());
                }
                sb.append(" (from ").append(s.tableName()).append(")");
                if (s.where() != null) {
                    sb.append(" (where ").append(exprToSExpr(s.where())).append(")");
                }
                sb.append(")");
                yield sb.toString();
            }
            case DeleteStmt s -> {
                StringBuilder sb = new StringBuilder("(delete " + s.tableName());
                if (s.where() != null) {
                    sb.append(" (where ").append(exprToSExpr(s.where())).append(")");
                }
                sb.append(")");
                yield sb.toString();
            }
        };
    }

    private String exprToSExpr(Expression expr) {
        return switch (expr) {
            case Literal lit -> String.valueOf(lit.value());
            case ColumnRef ref -> "(col " + ref.column() + ")";
            case BinaryExpr b -> "(" + b.op() + " " + exprToSExpr(b.left()) + " " + exprToSExpr(b.right()) + ")";
            case UnaryExpr u -> "(" + u.op() + " " + exprToSExpr(u.operand()) + ")";
        };
    }

    private String exprToString(Expression expr) {
        return switch (expr) {
            case Literal lit -> String.valueOf(lit.value());
            case ColumnRef ref -> ref.column();
            case BinaryExpr b -> exprToString(b.left()) + " " + b.op() + " " + exprToString(b.right());
            case UnaryExpr u -> u.op() + " " + exprToString(u.operand());
        };
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void printError(MiniDbException e) {
        String pos = e.pos() != null ? " @ " + e.pos() : "";
        System.out.println("[" + e.phase() + " 错误" + pos + "] " + e.getMessage());
    }
}
