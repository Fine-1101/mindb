CREATE TABLE student (
    id INT,
    name VARCHAR(50),
    score FLOAT
);

INSERT INTO student VALUES (1, 'Tom''s book', 90.5);

insert into student values (2, '你好世界', 85.0);

SeLeCt * FrOm student;  -- 关键字大小写混写

SELECT id, name
FROM student
WHERE score >= 90.0
  AND name != 'Tom''s book';   -- 双字符运算符与转义字符串

/* 多行注释：
   下面的语句在注释结束后才开始 */
SELECT * FROM student WHERE id == 1;

UPDATE student SET score = 95.5 WHERE id = 1;

DELETE FROM student WHERE name = 'Tom''s book' OR score <= 60.0;

SELECT name FROM student WHERE id != 3 AND NOT score < 60.0;

INSERT INTO course (cid, cname) VALUES (3, '');
