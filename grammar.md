# MiniDB 文法（grammar.md）

> 本文档由 D4-A 根据 `src/main/java/com/minidb/parser/Parser.java` 的**实际递归下降代码**整理，
> 每条产生式都标注对应实现函数，review 时可直接对照代码。
> Lexer 层（`com.minidb.lexer.Lexer`）只负责把 SQL 切成 Token；`COUNT/SUM/AVG/MIN/MAX`
> **不是关键字**，Lexer 仍按 `IDENT` 处理。

记号：`{ X }` 表示 0 次或多次，`[ X ]` 表示可选，`|` 表示选择。

---

## 1. 脚本与语句

```ebnf
script      → { ';' } { statement { ';' } } [ ';' ]
// implementation: parseScript()   （跳过空语句；单条出错时记录并同步到下一个 ';' 继续解析）

statement   → createTable
            | insert
            | select
            | update
            | delete
// implementation: parseStatement()  （按首 Token 分派；其它 Token 报 expected [CREATE / INSERT / SELECT / UPDATE / DELETE]）

single      → script 中仅一条语句的形态；允许结尾分号
// implementation: parse()  （多余输入报 expected [;]）
```

约定：`Statement.pos` 取**表名 Token 的位置**（CREATE/INSERT/SELECT/UPDATE/DELETE 均如此），
便于语义错误（表已存在/表不存在）定位到表名而非语句首。

---

## 1b. UPDATE（D5）

```ebnf
update      → 'UPDATE' identifier 'SET' assignment { ',' assignment } [ 'WHERE' expression ]
// implementation: parseUpdate()

assignment  → identifier '=' expression
// implementation: parseAssignment()
```

* SET 右侧是完整 `expression`，因此允许引用本行已有列（`score = score + 5`）；
  求值顺序与“先收集命中行再逐行重编码”属于执行层（D/C）职责。
* 左侧只允许普通列名（不支持 `t.col = ...`）。
* `where` 为 null 表示全表更新。


---

## 2. CREATE TABLE

```ebnf
createTable → 'CREATE' 'TABLE' identifier '(' columnDef { ',' columnDef } ')'
// implementation: parseCreateTable()

columnDef   → identifier type
// implementation: parseColumnDef()

type        → 'INT'
            | 'FLOAT'
            | 'VARCHAR' '(' INT_LIT ')'
// implementation: parseColumnDef()（VARCHAR 缺括号/长度非 INT_LIT 均报 PARSER 错误）
```

关键字大小写不敏感；`identifier` 的原文拼写（含大小写）保留到 `ColumnDef.name`。

---

## 3. INSERT

```ebnf
insert      → 'INSERT' 'INTO' identifier [ '(' identifier { ',' identifier } ')' ]
              'VALUES' row { ',' row }
// implementation: parseInsert()   （未指定列 => InsertStmt.columns == null；指定列按源码顺序）

row         → '(' value { ',' value } ')'
// implementation: parseRow()

value       → INT_LIT | FLOAT_LIT | STRING | 'NULL'
// implementation: parseValueLiteral()
//   （值只允许字面量，含 NULL => Literal(null, DataType.NULL, pos)；不做类型检查，交 Semantic）
```

---

## 4. SELECT

```ebnf
select      → 'SELECT' [ 'DISTINCT' ] selectList 'FROM' identifier
              [ joinClause ]
              [ 'WHERE' expression ]
              [ 'GROUP' 'BY' columnRef { ',' columnRef } ]
              [ 'ORDER' 'BY' orderKey { ',' orderKey } ]
// implementation: parseSelect()   （子句顺序固定：JOIN → WHERE → GROUP BY → ORDER BY）

joinClause  → 'JOIN' identifier 'ON' expression
// implementation: parseSelect()（内联在 parseSelect 中）
//   仅单层 INNER JOIN；不解析 INNER 关键字、不支持链式/多表/逗号连接、不支持表别名；ON 后必须有表达式

selectList  → '*'
            | selectItem { ',' selectItem }
// implementation: parseSelect()（'*' 时 SelectStmt.columns == null）

selectItem  → functionCall
            | columnRef
// implementation: parseSelectItem()
//   当前 IDENT 且下一个 Token 是 '(' => 聚合 FuncCall（放入 aggregates）；含 '.' => 限定列；否则普通列
//   （M0 契约：columns 与 aggregates 分列存放；纯聚合时 columns == null）

groupKey    → columnRef
// implementation: parseGroupBy() / parseColumnRef()
//   分组键只允许列引用：不支持表达式、不支持位置序号；本轮无 HAVING

orderKey    → columnRef [ 'ASC' | 'DESC' ]
// implementation: parseOrderKey()（缺省 ASC；排序键只能是列引用：不支持表达式与位置序号）

columnRef   → identifier [ '.' identifier ]
// implementation: parseColumnRef()
//   无限定名 => ColumnRef(table=null, col)；限定名 t.col => ColumnRef(table="t", col)；pos 取引用起始 token

delete      → 'DELETE' 'FROM' identifier [ 'WHERE' expression ]
// implementation: parseDelete()
```

`SelectStmt` 字段：`columns(List<ColumnRef>)`、`aggregates(List<FuncCall>)`、
`tableName`、`where`、`distinct`、`groupBy(List<ColumnRef>)`、`orderBy(List<OrderKey>)`、
`joinTable`、`joinOn`（后两者为 null 表示无 JOIN）。
「聚合与普通列混写是否合法」「GROUP BY 键覆盖非聚合列」等属于 Semantic 判断，Parser 不做限制。

---

## 5. 表达式（优先级从低到高）

```ebnf
expression     → or
// implementation: parseExpression()

or             → and { 'OR' and }
// implementation: parseOr()            左结合：Or(Or(a,b),c)

and            → not { 'AND' not }
// implementation: parseAnd()           左结合

not            → 'NOT' not
               | isPredicate
// implementation: parseNot()           NOT 右递归（NOT NOT x），只吃下一层 IS 谓词/比较

isPredicate    → comparison [ 'IS' [ 'NOT' ] 'NULL' ]
// implementation: parseIsPredicate()
//   层级：NOT < IS NULL < comparison；实现为 UnaryExpr(IS_NULL / IS_NOT_NULL)，不是 BinaryExpr

comparison     → additive [ comparisonOp additive ]
// implementation: parseComparison()   （非左递归链；不允许多重比较）

comparisonOp   → '=' | '==' | '!=' | '<' | '<=' | '>' | '>='
// implementation: comparisonOp()  映射 BinaryOp：'=' 与 '==' 都 => EQ，
//                                    '!=' => NE，'<' => LT，'<=' => LE，'>' => GT，'>=' => GE

additive       → multiplicative { ( '+' | '-' ) multiplicative }
// implementation: parseAdditive()      左结合

multiplicative → unary { ( '*' | '/' ) unary }
// implementation: parseMultiplicative() 左结合；'*' 在表达式中为乘法
//                                       （SELECT 列表里的 '*' 由 parseSelect 处理，二者由上下文区分）

unary          → '-' unary
               | primary
// implementation: parseUnary()         一元负号 => UnaryExpr(NEG, ...)（右递归）

primary        → literal
               | columnRef
               | '(' expression ')'
               | functionCall
// implementation: parsePrimary()
```

字面量映射（`parsePrimary` / `parseValueLiteral`）：
`INT_LIT → Literal(Integer, DataType.INT)`、`FLOAT_LIT → Literal(Double, DataType.FLOAT)`、
`STRING → Literal(String, DataType.VARCHAR)`、`NULL → Literal(null, DataType.NULL)`；
列引用走 `columnRef`（支持 `t.col`）；括号不产生节点，只有其中的 `expression`。

> **契约限制（D5-A 记录）**：M0 冻结的 `SelectStmt` 只有 `columns(List<ColumnRef>)` 与
> `aggregates(List<FuncCall>)` 两个投影字段，没有承载字面量投影的位置，因此
> `SELECT NULL FROM t;` 在 Parser 阶段报 `expected [* / identifier]`。
> NULL 在 WHERE / JOIN ON / SET / VALUES / 函数参数等表达式位置均已完整支持；
> 如需支持 `SELECT NULL`，属于 AST 契约扩展，需与 B/D 一起评审（A 不擅自改冻结契约）。

---

## 6. 函数调用（D4 聚合）

```ebnf
functionCall → identifier '(' functionArg ')'
// implementation: parseFunctionCall()

functionArg  → '*'
             | expression
// implementation: parseFunctionCall()
//   '*' => FuncCall.arg == null（COUNT(*) 特殊语法，不会构造成 ColumnRef("*")）
//   其余 => 参数为完整表达式（SUM(a + 1)、SUM((a + 1) * 2)）
```

约定：

* `FuncCall.func` 统一保存**大写**函数名：`count(*)`、`CoUnT(*)`、`COUNT(*)` 都得到 `"COUNT"`。
* 函数名不受限：`FOO(x)` 语法合法即构造 `FuncCall("FOO", ...)`，是否支持由 Semantic 判断。
* `SELECT count FROM t;` 中 `count` 无括号，仍是普通列 `ColumnRef`，不是函数。
* 聚合可与 `WHERE` / `GROUP BY` / `ORDER BY` 组合（如
  `SELECT name, COUNT(*) FROM t WHERE a > 1 GROUP BY name ORDER BY name DESC;`），Parser 只负责语法。

示例与 AST（M0 契约：聚合放在 `aggregates`，普通列放在 `columns`）：

| SQL | AST 片段 |
|---|---|
| `SELECT COUNT(*) FROM t;` | `SelectStmt(columns=null, aggregates=[FuncCall("COUNT", null, pos)], ...)` |
| `SELECT COUNT(a) FROM t;` | `FuncCall("COUNT", ColumnRef(null, "a", pos), pos)` |
| `SELECT SUM(a + 1) FROM t;` | `FuncCall("SUM", BinaryExpr(ColumnRef a, ADD, Literal 1), pos)` |
| `SELECT * FROM t;` | `SelectStmt(columns=null, aggregates=null, ...)`（`*` 仍为通配符） |

---

## 6b. D5 五特性示例（Parser 产出）

| SQL | 关键 AST |
|---|---|
| `UPDATE t SET score = score + 5 WHERE id = 1;` | `UpdateStmt(tableName="t", sets=[SetClause(ColumnRef(score), ADD(score,5))], where=EQ(id,1), pos=表名)` |
| `SELECT * FROM t ORDER BY score DESC, id ASC;` | `SelectStmt(orderBy=[OrderKey(score,false), OrderKey(id,true)])` |
| `SELECT name, COUNT(*) FROM t GROUP BY name;` | `SelectStmt(columns=[name], aggregates=[COUNT(*)→null], groupBy=[name])` |
| `SELECT * FROM a JOIN b ON a.id = b.id;` | `SelectStmt(joinTable="b", joinOn=EQ(ColumnRef("a",id), ColumnRef("b",id)))` |
| `SELECT * FROM t WHERE a = NULL;` | `BinaryExpr(ColumnRef(a), EQ, Literal(null, DataType.NULL, pos))` |
| `SELECT * FROM t WHERE a IS NULL;` | `UnaryExpr(UnaryOp.IS_NULL, ColumnRef(a), pos=IS)` |
| `SELECT * FROM t WHERE a IS NOT NULL;` | `UnaryExpr(UnaryOp.IS_NOT_NULL, ColumnRef(a), pos=IS)` |
| `SELECT a.id FROM a;` | `SelectStmt(columns=[ColumnRef("a","id",pos)])` |

---

## 7. Token 层规则（Lexer，未修改）

```ebnf
identifier → [A-Za-z_] [A-Za-z0-9_]*
// implementation: Lexer.scanIdentifierOrKeyword()（27 个 KW_* 关键字整词、大小写不敏感：
//                 16 个基础关键字 + DISTINCT + D5 的 UPDATE/SET/ORDER/BY/GROUP/JOIN/ON/NULL/IS/ASC/DESC；
//                 COUNT/SUM/AVG/MIN/MAX 仍不在关键字表内，按 IDENT 处理）

literal    → INT_LIT | FLOAT_LIT | STRING | 'NULL'
// implementation: Lexer.scanNumber() / Lexer.scanString()；NULL 由 Parser 的 parsePrimary() 处理
//   STRING 使用单引号，'' 表示一个单引号：'Tom''s book' → value "Tom's book"

comment    → '--' 行注释 | '/* ... */' 块注释（可跨行，行列号正确推进）
// implementation: Lexer.skipLineComment() / Lexer.skipBlockComment()
```

---

## 8. 错误报告与恢复

* 所有语法错误：`MiniDbException(Phase.PARSER, 出错Token.pos, message)`；
  message 形如
  `unexpected token FROM (KW_FROM) at line 1, column 8, expected [* / identifier]`
  （unexpected 含 Token 原文与 TokenType，expected 为可读名称集合）。
* `parseScript`：单条语句出错 → 记录错误 → 同步到下一个 `;` → 继续解析后续语句；
  最终若存在错误，抛出**第一条错误**，message 汇总全部错误位置与内容。
  已成功解析的语句保留在 Parser 内部（`recoveredStatements()`，包内可见，仅供测试取证）。
* EOF、未闭合字符串（由 Lexer 报 LEXER）、`;;`、纯注释脚本均有对应测试覆盖。
