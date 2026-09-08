-- ============================================
-- MiniDB 黄金样例 SQL（10条）
-- 覆盖：CREATE / INSERT / SELECT / DELETE
-- 包含：注释、转义字符串、大小写混写
-- ============================================

-- 1. CREATE TABLE（标准格式）
CREATE TABLE student (
    id INT,
    name VARCHAR(50),
    score FLOAT
);

-- 2. INSERT（含转义单引号）
INSERT INTO student VALUES (1, 'Tom''s book', 90.5);

-- 3. INSERT（中文字符 + 小写关键字）
insert into student values (2, '你好世界', 85.0);

-- 4. SELECT（大小写混写关键字）
SeLeCt * FrOm student;

-- 5. SELECT（多行 + WHERE + 双字符运算符 + 转义字符串）
SELECT id, name
FROM student
WHERE score >= 90.0
  AND name != 'Tom''s book';   -- 双字符运算符与转义字符串

-- 6. SELECT（多行注释后跟语句）
/* 多行注释：
   下面的语句在注释结束后才开始 */
SELECT * FROM student WHERE id == 1;

-- 7. DELETE（OR 条件 + 转义字符串）
DELETE FROM student WHERE name = 'Tom''s book' OR score <= 60.0;

-- 8. SELECT（NOT 运算符）
SELECT name FROM student WHERE id != 3 AND NOT score < 60.0;

-- 9. INSERT（空字符串）
INSERT INTO student VALUES (3, '', 77.7);

-- 10. DELETE（简单条件）
DELETE FROM student WHERE id = 2;
