CREATE DATABASE IF NOT EXISTS rpki_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rpki_db;

CREATE TABLE IF NOT EXISTS rpki_cert (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cert_name VARCHAR(255) NOT NULL,
  cer_file_name VARCHAR(512),
  serial_number VARCHAR(255),
  issuer VARCHAR(512),
  subject VARCHAR(512),
  rir VARCHAR(32),
  not_before DATETIME,
  not_after DATETIME,
  ipv4_prefixes LONGTEXT,
  ipv6_prefixes LONGTEXT,
  as_numbers LONGTEXT,
  parent_cert_id BIGINT,
  subject_key_id VARCHAR(128),
  authority_key_id VARCHAR(128),
  revoked BOOLEAN DEFAULT FALSE,
  has_conflict BOOLEAN DEFAULT FALSE,
  conflict_details LONGTEXT,
  fabric_tx_id VARCHAR(255),
  fabric_block_num VARCHAR(255),
  is_sent_to_fabric BOOLEAN DEFAULT FALSE,
  fabric_send_time DATETIME,
  fabric_send_failed BOOLEAN DEFAULT FALSE,
  fabric_last_error VARCHAR(2048) NULL,
  cert_hash VARCHAR(255),
  raw_cert_data LONGTEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cert_hash (cert_hash),
  KEY idx_rir (rir),
  KEY idx_has_conflict (has_conflict),
  KEY idx_parent_cert (parent_cert_id),
  KEY idx_subject_key (subject_key_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conflict_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cert_id_a BIGINT NOT NULL,
  cert_id_b BIGINT NOT NULL,
  conflict_type VARCHAR(64) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  rule_version VARCHAR(32),
  rule_reference VARCHAR(64),
  conflict_key VARCHAR(1024) NOT NULL,
  details LONGTEXT,
  detected_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  fabric_tx_id VARCHAR(255),
  fabric_block_num VARCHAR(255),
  is_sent_to_fabric BOOLEAN DEFAULT FALSE,
  fabric_send_time DATETIME,
  fabric_send_failed BOOLEAN DEFAULT FALSE,
  fabric_last_error VARCHAR(2048) NULL,
  UNIQUE KEY uk_conflict_key (conflict_key(255)),
  KEY idx_conflict_cert_a (cert_id_a),
  KEY idx_conflict_cert_b (cert_id_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fabric_tx_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conflict_record_id BIGINT,
  mode VARCHAR(16) NOT NULL,
  tx_id VARCHAR(255),
  block_num VARCHAR(255),
  payload LONGTEXT,
  status VARCHAR(32) NOT NULL,
  error_message LONGTEXT,
  correlation_id VARCHAR(64) NULL,
  pipeline_step VARCHAR(64) NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_fabric_tx_id (tx_id),
  KEY idx_fabric_correlation (correlation_id),
  KEY idx_fabric_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
