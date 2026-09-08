-- ============================================
-- MiniDB 黄金样例 SQL（10条）
-- 覆盖：CREATE / INSERT / SELECT / DELETE 各2条
-- 包含：注释、转义字符串、大小写混写、运算符WHERE
-- ============================================

-- 1. CREATE TABLE（标准格式）
CREATE TABLE student (
    id INT,
    name VARCHAR(50),
    score FLOAT
);

-- 2. CREATE TABLE（第二张表）
CREATE TABLE course (
    cid INT,
    cname VARCHAR(30)
);

-- 3. INSERT（含转义单引号）
INSERT INTO student VALUES (1, 'Tom''s book', 90.5);

-- 4. INSERT（中文字符 + 小写关键字）
insert into student values (2, '你好世界', 85.0);

-- 5. SELECT（大小写混写关键字 + 带运算符的WHERE）
SeLeCt * FrOm student WHERE score >= 90.0;

-- 6. SELECT（多行注释后跟语句 + 转义字符串 + NOT 运算符）
/* 多行注释：
   下面的语句在注释结束后才开始 */
SELECT name FROM student WHERE id != 3 AND NOT score < 60.0;

-- 7. DELETE（OR 条件 + 转义字符串）
DELETE FROM student WHERE name = 'Tom''s book' OR score <= 60.0;

-- 8. DELETE（简单条件）
DELETE FROM student WHERE id = 2;

-- 9. SELECT（补充：多行 WHERE + 双字符运算符）
SELECT id, name
FROM student
WHERE score >= 90.0
  AND name != 'Tom''s book';   -- 双字符运算符与转义字符串

-- 10. INSERT（空字符串，补充INSERT覆盖）
INSERT INTO student VALUES (3, '', 77.7);
