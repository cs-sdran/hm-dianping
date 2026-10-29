-- 增量执行：发现重复关系时停止，不删除数据；可重复执行。
-- 不要用包含 DROP TABLE 的 hmdp.sql 更新已有数据库。
DROP PROCEDURE IF EXISTS migrate_follow_unique_20261020;
DELIMITER //
CREATE PROCEDURE migrate_follow_unique_20261020()
BEGIN
    IF EXISTS (
        SELECT 1 FROM tb_follow
        GROUP BY user_id, follow_user_id HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Duplicate follow relations exist; resolve them before adding the index';
    END IF;

    IF NOT EXISTS (
        SELECT index_name FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'tb_follow'
        GROUP BY index_name
        HAVING MAX(non_unique) = 0 AND COUNT(*) = 2
           AND SUM(sub_part IS NOT NULL) = 0
           AND SUM(column_name = 'user_id') = 1
           AND SUM(column_name = 'follow_user_id') = 1
    ) THEN
        ALTER TABLE tb_follow
            ADD UNIQUE KEY uk_follow_user_target (user_id, follow_user_id);
    END IF;
END//
CALL migrate_follow_unique_20261020()//
DROP PROCEDURE migrate_follow_unique_20261020//
DELIMITER ;
