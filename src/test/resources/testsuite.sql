-- ============================================================
-- MiniDB 批量测试 SQL 集（隐藏测试集模拟）
-- 标注格式：-- expect: OK | LEXER | PARSER | SEMANTIC
-- 语句按顺序在同一实例上执行，后面的语句依赖前面的建表/插入
-- ============================================================

-- ===== 第 1 节：正常 - 建表与插入 =====
-- expect: OK
CREATE TABLE student (id INT, name VARCHAR(50), score FLOAT);
-- expect: OK
CREATE TABLE course (cid INT, cname VARCHAR(30));
-- expect: OK
CREATE TABLE flag_t (id INT, active BOOLEAN);
-- expect: OK
INSERT INTO student VALUES (1, 'Tom', 90.5);
-- expect: OK
INSERT INTO student VALUES (2, 'Alice', 85.0), (3, 'Bob', 60.0);
-- expect: OK
insert into student values (4, '你好世界', 77.7);
-- expect: OK
INSERT INTO student VALUES (5, 'Tom''s book', 59.9);
-- expect: OK
INSERT INTO student (id, name) VALUES (6, 'Partial');
-- expect: OK
INSERT INTO student VALUES (7, NULL, 70.0);
-- expect: OK
INSERT INTO student VALUES (8, 'Tom', 88.0);
-- expect: OK
INSERT INTO course VALUES (1, 'Math'), (2, 'Physics');
-- expect: OK
INSERT INTO flag_t VALUES (1, TRUE), (2, FALSE), (3, NULL);

-- ===== 第 2 节：正常 - 查询 =====
-- expect: OK
SELECT * FROM student;
-- expect: OK
SELECT name, score FROM student;
-- expect: OK
SELECT * FROM student WHERE score >= 85.0;
-- expect: OK
SELECT * FROM student WHERE score < 60.0 OR score > 90.0;
-- expect: OK
SELECT * FROM student WHERE NOT (score < 60.0) AND id != 2;
-- expect: OK
SELECT * FROM student WHERE name IS NULL;
-- expect: OK
SELECT * FROM student WHERE name IS NOT NULL AND score <= 77.7;
-- expect: OK
SELECT name FROM student WHERE score + 5 > 90.0;
-- expect: OK
SELECT DISTINCT name FROM student;
-- expect: OK
SELECT COUNT(*), SUM(score), AVG(score), MIN(name), MAX(name) FROM student;
-- expect: OK
SELECT COUNT(*), SUM(score) FROM student WHERE score >= 60.0;
-- expect: OK
SELECT name, COUNT(*) FROM student WHERE score IS NOT NULL GROUP BY name;
-- expect: OK
SELECT name, COUNT(*) FROM student GROUP BY name ORDER BY name ASC;
-- expect: OK
SELECT * FROM student ORDER BY score DESC, id ASC;
-- expect: OK
SELECT student.name, course.cname FROM student JOIN course ON student.id = course.cid;
-- expect: OK
SELECT * FROM flag_t WHERE active;

-- ===== 第 3 节：正常 - 更新与删除 =====
-- expect: OK
UPDATE student SET score = score + 5 WHERE id = 3;
-- expect: OK
UPDATE student SET name = NULL WHERE id = 8;
-- expect: OK
UPDATE flag_t SET active = FALSE WHERE id = 1;
-- expect: OK
DELETE FROM student WHERE id = 7;
-- expect: OK
DELETE FROM course WHERE cname = 'Physics';

-- ===== 第 4 节：边界 =====
-- expect: OK
INSERT INTO student VALUES (9, 'One', 60.0); INSERT INTO student VALUES (10, 'Two', 61.0);
-- expect: OK
CREATE TABLE long_t (col_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa INT);
-- expect: OK
INSERT INTO long_t VALUES (1);
-- expect: OK
SELECT col_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa FROM long_t;
-- expect: OK
SeLeCt NaMe FrOm StUdEnT WhErE Id = 1;
-- expect: OK
SELECT NAME FROM STUDENT WHERE ID = 2;
-- expect: OK
SELECT id,
       name
FROM student
WHERE score >= 60.0;
-- expect: OK
INSERT INTO student VALUES (2147483647, 'MaxInt', 1.0);
-- expect: OK
INSERT INTO student VALUES (-2147483647, 'MinInt', 2.0);
-- expect: OK
CREATE TABLE empty_t (x INT);
-- expect: OK
SELECT COUNT(*), SUM(x) FROM empty_t;
-- expect: OK
SELECT * FROM student WHERE id = 999;
-- expect: OK
/* 块注释 */ SELECT * FROM student WHERE id = 1;

-- ===== 第 5 节：词法错误 =====
-- expect: LEXER
SELECT @ FROM student;
-- expect: LEXER
SELECT 'unclosed FROM student;
-- expect: LEXER
INSERT INTO student VALUES (1.2.3);

-- ===== 第 6 节：语法错误 =====
-- expect: PARSER
SELECT FROM student;
-- expect: PARSER
INSERT INTO student VALUES;
-- expect: PARSER
CREATE TABLE (id INT);
-- expect: PARSER
DELETE student WHERE id = 1;
-- expect: PARSER
SELECT * FROM student WHERE;
-- expect: PARSER
UPDATE student SET = 5 WHERE id = 1;

-- ===== 第 7 节：语义错误 =====
-- expect: SEMANTIC
SELECT * FROM nonexistent;
-- expect: SEMANTIC
SELECT nosuch FROM student;
-- expect: SEMANTIC
INSERT INTO student VALUES (1);
-- expect: SEMANTIC
INSERT INTO student VALUES ('x', 'y', 1.0);
-- expect: SEMANTIC
CREATE TABLE student (id INT);
-- expect: SEMANTIC
CREATE TABLE dup_t (id INT, id VARCHAR(10));
-- expect: SEMANTIC
INSERT INTO student (nosuch) VALUES (1);
-- expect: SEMANTIC
UPDATE student SET nosuch = 1;
-- expect: SEMANTIC
SELECT * FROM student ORDER BY nosuch;
-- expect: SEMANTIC
SELECT name FROM student GROUP BY score;
-- expect: SEMANTIC
SELECT * FROM student JOIN nosuch ON student.id = nosuch.x;
-- expect: SEMANTIC
SELECT * FROM student WHERE name;
-- expect: SEMANTIC
SELECT * FROM student WHERE COUNT(*) > 1;
-- expect: SEMANTIC
INSERT INTO flag_t VALUES (9, 5);
-- expect: SEMANTIC
INSERT INTO student VALUES (11, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1.0);
