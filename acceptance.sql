-- expect: OK
CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT);
-- expect: OK
INSERT INTO student VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0), (3, 'Bob', 60.0);
-- expect: OK
CREATE TABLE course (cid INT, cname VARCHAR(30));
-- expect: OK
INSERT INTO course VALUES (1, 'Math'), (2, 'Physics');
-- expect: OK
CREATE TABLE flag_t (id INT, active BOOLEAN);
-- expect: OK
INSERT INTO flag_t VALUES (1, TRUE), (2, FALSE), (3, NULL);

-- ===== 1.1 词法分析 L1-L8 =====
-- L1 基本Token+位置（交互模式 .trace 复核 Tokens 段）
-- expect: OK
SELECT name FROM student;
-- L2 关键字大小写不敏感（text 保留原文）
-- expect: OK
SeLeCt * FrOm student;
-- L3 字符串转义：'' 还原为一个 '（.trace 复核 Tokens 段；SELECT 列表只接受列名，转用 WHERE 比较）
-- expect: OK
SELECT * FROM student WHERE name = 'Tom''s book';
-- L4 多字符运算符 >= <= != 不拆分（.trace 复核 Tokens 段）
-- expect: OK
SELECT * FROM student WHERE id >= 1 AND id <= 2 AND id != 1;
-- L5 行注释与块注释不产生 Token
-- 行注释：本行不产生任何 Token
-- expect: OK
SELECT /* 块注释 */ name FROM student;
-- L6 非法字符
-- expect: LEXER
SELECT @ FROM student;
-- L8 非法数字
-- expect: LEXER
INSERT INTO student VALUES (1.2.3);

-- ===== 1.2 语法分析 P1-P9 =====
-- P1 建表语句 AST
-- expect: OK
CREATE TABLE t (id INT, name VARCHAR(50));
-- P2 插入语句 AST
-- expect: OK
INSERT INTO student VALUES (4, 'Carol', 77.7);
-- P3 SELECT + WHERE AST
-- expect: OK
SELECT name FROM student WHERE score >= 90.0;
-- P4 DELETE AST
-- expect: OK
DELETE FROM student WHERE id = 4;
-- P5 AND 结合性强于 OR（.trace 复核 AST 段：(OR (EQ id 1) (AND (EQ id 2) (GT score 60.0)))）
-- expect: OK
SELECT * FROM student WHERE id = 1 OR id = 2 AND score > 60.0;
-- P6 括号改变结合（.trace 复核 AST 段：(AND (OR ...) (GT ...))）
-- expect: OK
SELECT * FROM student WHERE (id = 1 OR id = 2) AND score > 60.0;
-- P7 SELECT 后缺列：unexpected token + 期望集合
-- expect: PARSER
SELECT FROM student;
-- P8 VALUES 后缺值：期望 [integer literal / float literal / string literal / NULL / TRUE / FALSE]
-- expect: PARSER
INSERT INTO student VALUES;
-- P9 多语句：一条输入两条语句依次生成、依次执行
--   （脚本模式按 ';' 切分后独立执行，语义等价；交互模式 .trace 可见两条 AST）
-- expect: OK
CREATE TABLE p9t (x INT);
-- expect: OK
INSERT INTO p9t VALUES (1);
-- expect: OK
SELECT * FROM p9t;

-- ===== 1.3 语义分析 S1-S11 =====
-- S1 表不存在，pos 报到表名 token
-- expect: SEMANTIC
SELECT * FROM nosuch;
-- S2 列不存在，pos 报到该列 token
-- expect: SEMANTIC
SELECT nosuch FROM student;
-- S3 表已存在（系统目录管理：重名表报错）
-- expect: SEMANTIC
CREATE TABLE student (id INT);
-- S4 重复列名（用未存在的表名以触发重复列检查）
-- expect: SEMANTIC
CREATE TABLE dup_col_t (id INT, id INT);
-- S5 列数与值数不匹配
-- expect: SEMANTIC
INSERT INTO student VALUES (1);
-- S6 类型不匹配，pos 报到出错值
-- expect: SEMANTIC
INSERT INTO student VALUES ('x', 'y', 1.0);
-- S7 VARCHAR 值超长（51 字符 > name(50)）
-- expect: SEMANTIC
INSERT INTO student VALUES (1, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1.0);
-- S8 WHERE 条件必须是 BOOLEAN
-- expect: SEMANTIC
SELECT * FROM student WHERE name;
-- S9 聚合函数不允许出现在 WHERE 中
-- expect: SEMANTIC
SELECT * FROM student WHERE COUNT(*) > 1;
-- S10 JOIN 右表同样查 Catalog
-- expect: SEMANTIC
SELECT * FROM student JOIN nosuch ON student.id = nosuch.x;
-- S11 正例：部分列插入，缺列补 NULL
-- expect: OK
INSERT INTO student (id, name) VALUES (9, 'P');
-- expect: OK
SELECT * FROM student WHERE id = 9;

-- ===== 1.4 执行计划生成 T1-T6（语句可执行性验证；Plan 段用 .trace 复核） =====
-- 重置演示数据（S11 插入了 id=9）
-- expect: OK
DELETE FROM student WHERE id > 0;
-- expect: OK
INSERT INTO student VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0), (3, 'Bob', 60.0);
-- T1 投影裁剪：优化后 SeqScan 标注引用列 {name, score}
-- expect: OK
SELECT name FROM student WHERE score >= 90.0;
-- T2 常量折叠 + 布尔化简：1 = 1 消失
-- expect: OK
SELECT name FROM student WHERE 1 = 1 AND score > 60.0;
-- T3 双表数据源 + ON 条件
-- expect: OK
SELECT student.name, course.cname FROM student JOIN course ON student.id = course.cid;
-- T4 聚合节点 + 分组键 + 顶层 Project 重排输出序
-- expect: OK
SELECT name, COUNT(*) FROM student GROUP BY name;
-- T5 Sort 最外层 + 冗余 Project * 消除
-- expect: OK
SELECT * FROM student ORDER BY score DESC;
-- T6 Update 计划
-- expect: OK
UPDATE student SET score = score + 5 WHERE id = 3;
-- T7 等价性：优化只改树不改结果（另有 OptimizationEquivalenceTest 23 组结果级对拍）

-- ===== 2.1 页式存储管理（页分配 / 释放 / 读取 / 写入 / 跨页） =====
-- 大表 big：每行约 250B（240B VARCHAR），一页 4096B 放 16 行，60 行跨 4 页
-- （页级逐字节对拍 / 重启数据恢复由 ParameterizedPageTest/DiskPageTest 单测验证）
-- expect: OK
CREATE TABLE big (id INT, pad VARCHAR(240));
-- expect: OK
INSERT INTO big VALUES
    (1, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (2, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (3, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (4, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (5, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (6, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (7, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (8, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (9, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
    (10, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
-- expect: OK
INSERT INTO big VALUES
    (11, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (12, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (13, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (14, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (15, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (16, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (17, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (18, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (19, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'),
    (20, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb');
-- expect: OK
INSERT INTO big VALUES
    (21, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (22, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (23, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (24, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (25, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (26, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (27, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (28, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (29, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'),
    (30, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc');
-- expect: OK
INSERT INTO big VALUES
    (31, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (32, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (33, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (34, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (35, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (36, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (37, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (38, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (39, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'),
    (40, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd');
-- expect: OK
INSERT INTO big VALUES
    (41, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (42, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (43, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (44, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (45, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (46, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (47, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (48, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (49, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'),
    (50, 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee');
-- expect: OK
INSERT INTO big VALUES
    (51, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (52, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (53, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (54, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (55, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (56, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (57, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (58, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (59, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'),
    (60, 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff');
-- 跨页读取验证：COUNT=60、SUM(id)=1830（扫描全部 4 页）
-- expect: OK
SELECT COUNT(*), SUM(id) FROM big;
-- 跨页定位读取：id=57 在第 4 页
-- expect: OK
SELECT id FROM big WHERE id = 57;
-- 跨页写入：UPDATE 第 4 页行
-- expect: OK
UPDATE big SET pad = 'updated!' WHERE id = 57;
-- expect: OK
SELECT pad FROM big WHERE id = 57;
-- 页释放（标删）：删除跨第 2-4 页的 30 行
-- expect: OK
DELETE FROM big WHERE id > 30;
-- expect: OK
SELECT COUNT(*), SUM(id) FROM big;

-- ===== 2.2 缓存机制（固定交替访问序列，触发命中 / 淘汰 / 回写路径） =====
-- 建同构第二张表：big2 两页，id 201-220
-- expect: OK
CREATE TABLE big2 (id INT, pad VARCHAR(240));
-- expect: OK
INSERT INTO big2 VALUES
    (201, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (202, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (203, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (204, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (205, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (206, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (207, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (208, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (209, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg'),
    (210, 'gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg');
-- expect: OK
INSERT INTO big2 VALUES
    (211, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (212, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (213, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (214, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (215, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (216, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (217, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (218, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (219, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh'),
    (220, 'hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh');
-- 固定交替访问序列：big → big2 → big → big2 → student → big2
-- （重复访问命中缓存，交替访问触发不同表的页换入换出；磁盘模式下脏页触发回写。
--   LRU 命中/淘汰/回写事件流与命中率统计由 InMemoryBufferPoolTest/BufferLogTest 验证）
-- expect: OK
SELECT COUNT(*), SUM(id) FROM big;
-- expect: OK
SELECT COUNT(*), SUM(id) FROM big2;
-- expect: OK
SELECT COUNT(*) FROM big;
-- expect: OK
SELECT COUNT(*) FROM big2;
-- expect: OK
SELECT COUNT(*) FROM student;
-- expect: OK
SELECT SUM(id) FROM big2;

-- ===== 2.3 接口与集成（统一接口 getPage/newPage/flushAll 下双表互不干扰） =====
-- 缓存键为"表名:页号"，big(4页)/big2(2页) 交替读写后数据不串表
-- expect: OK
UPDATE big2 SET pad = 'cross-check' WHERE id = 220;
-- expect: OK
SELECT pad FROM big2 WHERE id = 220;
-- expect: OK
SELECT COUNT(*), SUM(id) FROM big;
-- expect: OK
SELECT COUNT(*) FROM student;
-- expect: OK
SELECT COUNT(*), SUM(id) FROM big2;
-- 双表 JOIN 走同一统一接口
-- expect: OK
SELECT student.name, course.cname FROM student JOIN course ON student.id = course.cid;

-- ===== 3.1 执行引擎 E1-E13 =====
-- 重置演示数据（T6 更新了 id=3 的 score）
-- expect: OK
DELETE FROM student WHERE id > 0;
-- expect: OK
INSERT INTO student VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0), (3, 'Bob', 60.0);
-- E1 SeqScan：3 行 × 3 列
-- expect: OK
SELECT * FROM student;
-- E2 Filter：Tom、Alice，2 行
-- expect: OK
SELECT name FROM student WHERE score >= 85.0;
-- E3 Project：列序与书写一致
-- expect: OK
SELECT name, score FROM student;
-- E4 Insert：插入 1 行；COUNT(*)=4
-- expect: OK
INSERT INTO student VALUES (4, 'Carol', 77.7);
-- expect: OK
SELECT COUNT(*) FROM student;
-- E5 Delete：删除完成；COUNT(*)=3
-- expect: OK
DELETE FROM student WHERE id = 4;
-- expect: OK
SELECT COUNT(*) FROM student;
-- E6 Update：更新 1 行；60.0 → 65.0（SET 引用本行列）
-- expect: OK
UPDATE student SET score = score + 5 WHERE id = 3;
-- expect: OK
SELECT score FROM student WHERE id = 3;
-- 重置演示数据（E6 更新了 id=3 的 score，E7 起恢复干净数据）
-- expect: OK
DELETE FROM student WHERE id > 0;
-- expect: OK
INSERT INTO student VALUES (1, 'Tom', 90.5), (2, 'Alice', 85.0), (3, 'Bob', 60.0);
-- E7 Sort 多键：Tom 90.5 → Alice 85.0 → Bob 60.0
-- expect: OK
SELECT * FROM student ORDER BY score DESC, id ASC;
-- E8 NestedLoopJoin：(Tom, Math)、(Alice, Physics) 2 行
-- expect: OK
SELECT student.name, course.cname FROM student JOIN course ON student.id = course.cid;
-- E9 标量聚合：3、235.5、78.5、Alice、Tom
-- expect: OK
SELECT COUNT(*), SUM(score), AVG(score), MIN(name), MAX(name) FROM student;
-- E10 聚合+分组+排序叠加：Alice/Bob/Tom 各 1 行
-- expect: OK
SELECT name, COUNT(*) FROM student GROUP BY name ORDER BY name;
-- E11 DISTINCT：3 行（无重名）
-- expect: OK
SELECT DISTINCT name FROM student;
-- E12 BOOLEAN 列作条件：仅 id=1 一行
-- expect: OK
SELECT * FROM flag_t WHERE active;
-- E13 NULL 语义：IS NULL 空集 / IS NOT NULL 3 行
-- expect: OK
SELECT * FROM student WHERE name IS NULL;
-- expect: OK
SELECT * FROM student WHERE name IS NOT NULL;

-- ===== 3.2 存储引擎与目录 =====
-- 序列化-反序列化往返：极值 INT / NULL / 中文 / '' 转义 / 空串 / BOOLEAN 三值
-- expect: OK
CREATE TABLE ser_t (id INT, name VARCHAR(50), score FLOAT, active BOOLEAN);
-- expect: OK
INSERT INTO ser_t VALUES
    (2147483647, 'Tom''s book', -3.14, NULL),
    (-2147483647, '中文测试', 1.0, TRUE),
    (0, '', 0.5, FALSE);
-- expect: OK
SELECT * FROM ser_t;
-- expect: OK
SELECT id FROM ser_t WHERE name IS NULL;
-- 系统目录：表名大小写不敏感（规范化收敛在 Catalog）
-- expect: OK
SELECT COUNT(*) FROM STUDENT;
-- expect: OK
SELECT name FROM Student WHERE ID = 1;
-- L7 未闭合字符串（脚本切分按行尾最后一个 ';' 截断隔离本语句）
-- expect: LEXER
SELECT 'abc;
