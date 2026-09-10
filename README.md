# MiniDB

一个教学用迷你关系数据库，从零实现 SQL 词法/语法/语义分析、火山模型执行器、聚合函数、缓冲区管理等核心模块。

## 技术栈

- **Java 21**（sealed interface / record / pattern matching）
- **Maven 3.8+**
- **JUnit 5.10.2**（测试框架）

## 环境配置（三个坑必读）

### 1. JAVA_HOME 必须指向 JDK 21

项目使用 Java 21 特性（sealed interface、record pattern matching），JDK 8/17 无法编译。

```cmd
:: Windows：确认 JAVA_HOME 指向 JDK 21
set JAVA_HOME=D:\你的JDK21路径
java -version
:: 应显示 openjdk version "21.x.x"
```

IntelliJ IDEA 中：`File → Project Structure → SDKs` 添加 JDK 21 路径，`Project` 选 JDK 21。

### 2. Maven 本地仓库路径（-Dmaven.repo.local）

如果系统 Maven 仓库路径有问题（权限/中文路径），需指定本地仓库：

```cmd
mvn test -Dmaven.repo.local=D:\mymavenrepo
```

IntelliJ IDEA 中：`Settings → Build → Maven` 设置 `Maven home path` 和 `Local repository`。

### 3. Windows 控制台编码（chcp 65001）

CLI 交互模式输出含中文和 Unicode 字符（如 ── 分隔线），Windows cmd 默认 GBK 编码会乱码：

```cmd
chcp 65001
mvn exec:java -Dexec.mainClass="com.minidb.MiniDB"
```

或在 IntelliJ IDEA 的 Run Configuration 中设置 VM options：`-Dfile.encoding=UTF-8`。

## 构建与运行

```cmd
:: 编译
mvn compile

:: 运行全部测试
mvn test

:: 交互模式 CLI
mvn exec:java -Dexec.mainClass="com.minidb.MiniDB"

:: 脚本模式
mvn exec:java -Dexec.mainClass="com.minidb.MiniDB" -Dexec.args="--script src/test/resources/demo.sql"
```

## 项目结构

```
src/main/java/com/minidb/
├── ast/          AST 节点（sealed interface Expression permits Literal/ColumnRef/BinaryExpr/UnaryExpr/FuncCall）
├── buffer/       缓冲区管理（BufferPool / InMemoryBufferPool）
├── catalog/      目录管理（Catalog / MemoryCatalog / TableDef / ColumnDef）
├── common/       公共类（DataType / Position / MiniDbException）
├── engine/       执行引擎（火山模型：SeqScan/Filter/Project/Aggregate Executor + ExpressionEvaluator + RowEncoder）
├── lexer/        词法分析（Lexer / Token / TokenType）
├── parser/       语法分析（递归下降 Parser）
├── plan/         逻辑计划（PlanNode sealed interface permits SeqScan/Filter/Project/CreateTablePlan/InsertPlan/DeletePlan/AggregatePlan）
├── semantic/     语义分析（SemanticAnalyzer / TypeRules）
├── storage/      存储层（Page / MemoryPage）
└── MiniDB.java   CLI 入口
```

## 支持的 SQL 语法

| 语句 | 示例 |
|------|------|
| CREATE TABLE | `CREATE TABLE t (id INT, name VARCHAR(50), score FLOAT)` |
| INSERT INTO | `INSERT INTO t VALUES (1, 'Tom', 90.5)` |
| SELECT | `SELECT * FROM t WHERE score >= 90` |
| DELETE | `DELETE FROM t WHERE id = 1` |
| 聚合查询 | `SELECT COUNT(*), SUM(score), AVG(score), MIN(score), MAX(score) FROM t` |
| 聚合+WHERE | `SELECT COUNT(*), AVG(score) FROM t WHERE score >= 85` |
| DISTINCT | `SELECT DISTINCT name FROM t` |

## 聚合函数说明

| 函数 | 说明 | 空表语义 | 与标准 SQL 差异 |
|------|------|----------|----------------|
| COUNT(*) | 统计行数 | 0 | 一致 |
| COUNT(col) | 统计非空行数 | 0 | 一致（系统无 NULL） |
| SUM(col) | 求和 | 0 | 标准 SQL 返回 NULL |
| AVG(col) | 平均值 | 0 | 标准 SQL 返回 NULL |
| MIN(col) | 最小值 | 0 | 标准 SQL 返回 NULL |
| MAX(col) | 最大值 | 0 | 标准 SQL 返回 NULL |

**限制**：不支持 GROUP BY；聚合与普通列混写报 SEMANTIC 错误。

## 测试体系

测试覆盖全部模块，按层次组织：

| 层次 | 测试类 | 覆盖内容 |
|------|--------|----------|
| 词法 | LexerTest | 关键字/标识符/数字/字符串/注释/错误定位 |
| 语法 | ParserTest | 四类语句/表达式优先级/错误恢复 |
| 语义 | SemanticAnalyzerTest + TypeRulesTest | 存在性/类型匹配/聚合函数检查 |
| 存储 | MemoryPageTest | 页内槽分配/删除/空间计算 |
| 缓冲 | InMemoryBufferPoolTest | 页分配/脏页标记/刷新 |
| 编解码 | RowEncoderTest | 全类型往返/中文/空串/超 8 列位图 |
| 引擎 | EngineTest | CREATE/INSERT/DELETE 端到端/跨页 |
| 表达式 | ExpressionEvaluatorTest | 比较/算术/AND/OR 短路/类型错误 |
| 集成 | AggregateFunctionTest | 聚合五函数/空表语义/WHERE 组合/DISTINCT |
| 集成 | EndToEndTest / MilestoneScriptTest / SeqScanTest | 全链/里程碑脚本/扫描 |
| 分类 | PhaseClassificationTest | 各阶段错误分类 |
| 等价 | OptimizationEquivalenceTest | 优化前后等价性 |
