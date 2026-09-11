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
-- D5 五大特性演示：UPDATE / ORDER BY / NULL / GROUP BY / JOIN
-- ============================================

-- 12. UPDATE：将 Tom 的分数更新为 92.0
UPDATE student SET score = 92.0 WHERE name = 'Tom';
SELECT name, score FROM student WHERE name = 'Tom';

-- 13. ORDER BY：按分数降序排列
SELECT name, score FROM student ORDER BY score DESC;

-- 14. NULL 支持：插入含 NULL 的行 + IS [NOT] NULL 查询
INSERT INTO student VALUES (6, 'Eve', NULL);
SELECT name, score FROM student WHERE score IS NULL;
SELECT name, score FROM student WHERE score IS NOT NULL;

-- 15. GROUP BY：建第二张表并按部门分组聚合
CREATE TABLE emp (id INT, name VARCHAR(20), dept VARCHAR(10), salary INT);
INSERT INTO emp VALUES
    (1, 'Alice', 'Eng', 100),
    (2, 'Bob', 'Eng', 120),
    (3, 'Charlie', 'Sales', 80),
    (4, 'Dave', 'Sales', 90),
    (5, 'Eve', 'Eng', 110);
SELECT dept, COUNT(*), SUM(salary), AVG(salary) FROM emp GROUP BY dept;

-- 16. JOIN：员工与部门表连接
CREATE TABLE dept (id INT, dept_name VARCHAR(20));
INSERT INTO dept VALUES (1, 'Engineering'), (2, 'Sales');
SELECT emp.name, dept.dept_name FROM emp JOIN dept ON emp.dept = dept.dept_name;

-- 17. 组合：JOIN + ORDER BY
SELECT emp.name, dept.dept_name FROM emp JOIN dept ON emp.dept = dept.dept_name ORDER BY emp.name ASC;

-- 18. 组合：UPDATE 后聚合
UPDATE emp SET salary = 130 WHERE name = 'Alice';
SELECT dept, SUM(salary) FROM emp GROUP BY dept;
