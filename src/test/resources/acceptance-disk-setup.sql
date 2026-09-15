-- ============================================================
-- MiniDB 验收：磁盘持久化 - 会话1（建表 + 插数据，exit 自动落盘）
--
-- 运行（--disk 模式）：
--   mvn compile exec:java -Dexec.mainClass=com.minidb.MiniDB ^
--     -Dexec.args="--disk src/test/resources/acceptance-disk-setup.sql"
--
-- 脚本结束自动 close()：flushAll 刷全部脏页 + catalog.dat 持久化。
-- 若 data/ 下已有 disk_t 表（重复运行），首条建表报"表已存在"属预期，
-- 后续插数据仍执行（幂等演示可自行清理 data/ 后重跑）。
-- ============================================================

-- expect: OK
CREATE TABLE disk_t (id INT, name VARCHAR(50), score FLOAT, active BOOLEAN);
-- expect: OK
INSERT INTO disk_t VALUES
    (1, 'Tom', 90.5, TRUE),
    (2, 'Alice', NULL, FALSE),
    (3, NULL, 60.0, NULL);
-- 会话内先验证一次
-- expect: OK
SELECT * FROM disk_t;
-- 多行写入（覆盖第二页）
-- expect: OK
INSERT INTO disk_t VALUES
    (4, 'Carol', 77.7, TRUE),
    (5, 'Dave', 95.0, FALSE),
    (6, 'Eve', 88.0, TRUE);
-- expect: OK
SELECT COUNT(*), SUM(id) FROM disk_t;
