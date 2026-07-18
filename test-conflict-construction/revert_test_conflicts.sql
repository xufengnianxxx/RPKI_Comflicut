-- =============================================================================
-- 一键复原：将 rpki_cert 中被测试脚本改动的行恢复为 01_pick_50_and_backup.sql
-- 执行备份时的快照（rpki_cert_test_backup）。
-- 执行前请确认备份表存在且未被清空。
-- =============================================================================

USE rpki_db;

UPDATE rpki_cert c
INNER JOIN rpki_cert_test_backup b ON c.id = b.id
SET
  c.cert_name = b.cert_name,
  c.cer_file_name = b.cer_file_name,
  c.serial_number = b.serial_number,
  c.issuer = b.issuer,
  c.subject = b.subject,
  c.rir = b.rir,
  c.not_before = b.not_before,
  c.not_after = b.not_after,
  c.ipv4_prefixes = b.ipv4_prefixes,
  c.ipv6_prefixes = b.ipv6_prefixes,
  c.as_numbers = b.as_numbers,
  c.parent_cert_id = b.parent_cert_id,
  c.subject_key_id = b.subject_key_id,
  c.authority_key_id = b.authority_key_id,
  c.revoked = b.revoked,
  c.has_conflict = b.has_conflict,
  c.conflict_details = b.conflict_details,
  c.fabric_tx_id = b.fabric_tx_id,
  c.fabric_block_num = b.fabric_block_num,
  c.is_sent_to_fabric = b.is_sent_to_fabric,
  c.fabric_send_time = b.fabric_send_time,
  c.fabric_send_failed = b.fabric_send_failed,
  c.fabric_last_error = b.fabric_last_error,
  c.cert_hash = b.cert_hash,
  c.raw_cert_data = b.raw_cert_data,
  c.created_at = b.created_at,
  c.updated_at = b.updated_at;

SELECT ROW_COUNT() AS restored_rows;

-- 可选：测试结束后删除辅助表（取消注释即可）
-- DROP TABLE IF EXISTS rpki_cert_test_backup;
-- DROP TABLE IF EXISTS rpki_cert_conflict_pick;
