-- ============================================
-- MiniDB 演示脚本 demo.sql
-- 覆盖：建表 / 多行插入 / 带 WHERE 查询 / 条件删除 / 再查询
--       + 聚合函数（COUNT/SUM/AVG/MIN/MAX）
--       + 聚合 + WHERE 组合
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

-- ============================================
-- 聚合函数演示
-- ============================================

-- 8. COUNT(*)：统计行数
SELECT COUNT(*) FROM student;

-- 9. SUM/AVG/MIN/MAX：聚合 score
SELECT SUM(score) FROM student;
SELECT AVG(score) FROM student;
SELECT MIN(score) FROM student;
SELECT MAX(score) FROM student;

-- 10. 多聚合一次输出
SELECT COUNT(*), SUM(score), AVG(score), MIN(score), MAX(score) FROM student;

-- 11. 聚合 + WHERE 组合：只统计 score >= 85 的行
SELECT COUNT(*), AVG(score) FROM student WHERE score >= 85.0;

-- 12. 故意语义错误：查询不存在的表
SELECT * FROM nonexistent_table;
