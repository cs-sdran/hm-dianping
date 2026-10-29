-- 在现有数据库中执行本增量脚本；不要重新导入包含 DROP TABLE 的 hmdp.sql。
-- 发现重复订单时停止，不自动删除或合并已有记录。重复执行不会重复添加索引。
DROP PROCEDURE IF EXISTS migrate_voucher_order_unique_20261016;
DELIMITER //
CREATE PROCEDURE migrate_voucher_order_unique_20261016()
BEGIN
    IF EXISTS (
        SELECT 1 FROM tb_voucher_order
        GROUP BY user_id, voucher_id HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Duplicate user/voucher orders exist; resolve them before adding the index';
    END IF;

    IF NOT EXISTS (
        SELECT index_name FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'tb_voucher_order'
        GROUP BY index_name
        HAVING MAX(non_unique) = 0
           AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'user_id,voucher_id'
    ) THEN
        ALTER TABLE tb_voucher_order
            ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
    END IF;
END//
CALL migrate_voucher_order_unique_20261016()//
DROP PROCEDURE migrate_voucher_order_unique_20261016//
DELIMITER ;
