-- 双证检测流水：无论是否检出冲突均记一条，供前端「检测记录」展示
CREATE TABLE IF NOT EXISTS pair_detection_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cert_id_a BIGINT NOT NULL,
  cert_id_b BIGINT NOT NULL,
  offline_has_conflict BOOLEAN NOT NULL DEFAULT FALSE,
  offline_summary LONGTEXT,
  chain_verdict VARCHAR(64) NULL,
  chain_primary_type VARCHAR(64) NULL,
  chain_summary LONGTEXT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pair_detection_cert_a (cert_id_a),
  KEY idx_pair_detection_cert_b (cert_id_b),
  KEY idx_pair_detection_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
