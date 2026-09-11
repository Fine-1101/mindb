-- ============================================
-- MiniDB 演示脚本 demo.sql
-- 覆盖：建表 / 多行插入 / 带 WHERE 查询 / 条件删除 / 再查询
--       + 一条故意语义错误语句演示错误定位
-- ============================================

-- 1. 建表
CREATE TABLE student (
    id INT,
    name VARCHAR(50),
    score FLOAT
);

-- 2. 多行插入
INSERT INTO student VALUES
    (1, 'Tom', 90.5),
    (2, 'Alice', 85.0),
    (3, 'Bob', 60.0),
    (4, 'Charlie', 77.7),
    (5, 'Dave', 95.0);

-- 3. 带 WHERE 查询
SELECT name, score FROM student WHERE score >= 90.0;

-- 4. 全表查询
SELECT * FROM student;

-- 5. 条件删除（删除 score < 70 的行）
DELETE FROM student WHERE score < 70.0;

-- 6. 删除后再查询
SELECT * FROM student;

-- 7. 带 AND 条件的查询
SELECT name FROM student WHERE score >= 80.0 AND id <= 4;

-- 8. 故意语义错误：查询不存在的表
SELECT * FROM nonexistent_table;

-- 9. 标量聚合（D4）：五个聚合函数一行输出
SELECT COUNT(*), SUM(score), AVG(score), MIN(name), MAX(name) FROM student;

-- 10. 聚合 + WHERE 过滤（只统计 score >= 80 的行）
SELECT COUNT(*), AVG(score) FROM student WHERE score >= 80.0;

-- 11. DISTINCT 去重（保留首次出现顺序）
SELECT DISTINCT name FROM student;
