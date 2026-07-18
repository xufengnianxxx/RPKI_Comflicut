-- 已有库一次性升级（若列已存在会报错，可忽略或逐条执行）
USE rpki_db;
ALTER TABLE conflict_record
  ADD COLUMN fabric_tx_id VARCHAR(255) NULL,
  ADD COLUMN fabric_block_num VARCHAR(255) NULL,
  ADD COLUMN is_sent_to_fabric BOOLEAN DEFAULT FALSE,
  ADD COLUMN fabric_send_time DATETIME NULL;
