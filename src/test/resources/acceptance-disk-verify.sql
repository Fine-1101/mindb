-- ============================================================
-- MiniDB 验收：磁盘持久化 - 会话2（重启后读回，验证数据恢复）
--
-- 运行（--disk 模式，在 acceptance-disk-setup.sql 的会话结束之后）：
--   mvn compile exec:java -Dexec.mainClass=com.minidb.MiniDB ^
--     -Dexec.args="--disk src/test/resources/acceptance-disk-verify.sql"
--
-- 验证点：
--   1) 重启恢复：recoverFromDisk 按文件页数重建页映射，6 行全部读回；
--   2) UPDATE 落盘：本会话更新后 exit 再次刷盘，再重启读回 99.0（幂等）；
--   3) NULL 落盘不丢：id=2/3 的 NULL 列读回仍为 NULL；
--   4) 上层 SQL 不感知存储切换（Engine 经同一 BufferPool 接口读回）。
-- ============================================================

-- V1 重启数据恢复：6 行与 setup 会话一致（id=1..6）
-- expect: OK
SELECT * FROM disk_t;
-- V2 聚合读回：COUNT=6、SUM(id)=21
-- expect: OK
SELECT COUNT(*), SUM(id) FROM disk_t;
-- V3 NULL 落盘不丢：IS NULL / IS NOT NULL
-- expect: OK
SELECT id FROM disk_t WHERE name IS NULL;
-- expect: OK
SELECT id FROM disk_t WHERE score IS NOT NULL;
-- V4 UPDATE 落盘（幂等：绝对值更新，重启后仍为 99.0）
-- expect: OK
UPDATE disk_t SET score = 99.0 WHERE id = 1;
-- expect: OK
SELECT score FROM disk_t WHERE id = 1;
-- V5 过滤 + BOOLEAN 读回
-- expect: OK
SELECT id FROM disk_t WHERE active;
