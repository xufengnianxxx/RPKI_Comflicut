-- 写路径闭环：上链失败可观测性 + 审计关联（已有库升级；列已存在则跳过对应语句）
USE rpki_db;

ALTER TABLE rpki_cert
  ADD COLUMN fabric_send_failed BOOLEAN DEFAULT FALSE,
  ADD COLUMN fabric_last_error VARCHAR(2048) NULL;

ALTER TABLE conflict_record
  ADD COLUMN fabric_send_failed BOOLEAN DEFAULT FALSE,
  ADD COLUMN fabric_last_error VARCHAR(2048) NULL;

ALTER TABLE fabric_tx_record
  ADD COLUMN correlation_id VARCHAR(64) NULL,
  ADD COLUMN pipeline_step VARCHAR(64) NULL,
  ADD KEY idx_fabric_correlation (correlation_id);
