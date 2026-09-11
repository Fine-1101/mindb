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

-- ============================================
-- D5 新特性演示（依赖 A/B 的 UPDATE/ORDER BY/NULL/GROUP BY/JOIN）
-- 注：以下语句需 Parser/Planner 支持对应特性后才能跑通
-- ============================================

-- -- 12. UPDATE：SET score = score + 5 WHERE id = 1（拍板3：允许引用本行列）
-- UPDATE student SET score = score + 5.0 WHERE id = 1;
--
-- -- 13. UPDATE 后再查询
-- SELECT id, name, score FROM student WHERE id = 1;
--
-- -- 14. ORDER BY 单列 ASC
-- SELECT name, score FROM student ORDER BY score;
--
-- -- 15. ORDER BY 多列 + DESC
-- SELECT name, score FROM student ORDER BY score DESC, name ASC;
--
-- -- 16. ORDER BY + NULL 排序（拍板7：NULL 视为最小值）
-- INSERT INTO student VALUES (6, NULL, NULL);
-- SELECT id, name FROM student ORDER BY name ASC;
--
-- -- 17. IS NULL / IS NOT NULL（拍板8）
-- SELECT id, name FROM student WHERE name IS NULL;
-- SELECT id, name FROM student WHERE name IS NOT NULL;
--
-- -- 18. GROUP BY + 聚合（拍板12）
-- -- 注：需先有重复的 score 值
-- SELECT score, COUNT(*) FROM student GROUP BY score;
--
-- -- 19. JOIN 双表（拍板9）
-- -- 注：需先建 course 表并插入数据
-- CREATE TABLE enrollment (
--                             sid INT,
--                             cid INT
-- );
-- INSERT INTO enrollment VALUES (1, 101), (2, 101), (1, 102);
-- SELECT student.name, enrollment.cid
-- FROM student JOIN enrollment ON student.id = enrollment.sid;
--
-- -- 20. JOIN + WHERE + ORDER BY 组合
-- SELECT student.name, enrollment.cid
-- FROM student JOIN enrollment ON student.id = enrollment.sid
-- WHERE enrollment.cid = 101
-- ORDER BY student.name;