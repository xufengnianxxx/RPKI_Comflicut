-- =============================================================================
-- 步骤 1：从 rpki_cert 优选「同一主导 RIR」随机抽取 50 证，写入 rpki_cert_conflict_pick，
--         并全量备份到 rpki_cert_test_backup（供 revert 使用）。
-- 前置：USE rpki_db; 或连接串指定库
-- =============================================================================

USE rpki_db;

SET @dominant_rir = (
  SELECT rir FROM rpki_cert
  WHERE rir IS NOT NULL AND TRIM(rir) <> ''
  GROUP BY rir ORDER BY COUNT(*) DESC LIMIT 1
);

DROP TABLE IF EXISTS rpki_cert_conflict_pick;
CREATE TABLE rpki_cert_conflict_pick (
  slot INT NOT NULL PRIMARY KEY,
  id BIGINT NOT NULL,
  cert_hash VARCHAR(255),
  rir VARCHAR(32),
  orig_parent_cert_id BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO rpki_cert_conflict_pick (slot, id, cert_hash, rir, orig_parent_cert_id)
SELECT rn, id, cert_hash, rir, parent_cert_id
FROM (
  SELECT
    ROW_NUMBER() OVER (
      ORDER BY
        CASE WHEN @dominant_rir IS NOT NULL AND rir = @dominant_rir THEN 0 ELSE 1 END,
        (parent_cert_id IS NULL),
        RAND()
    ) AS rn,
    id,
    cert_hash,
    rir,
    parent_cert_id
  FROM rpki_cert
  WHERE cert_hash IS NOT NULL AND TRIM(cert_hash) <> ''
) t
WHERE rn <= 50;

SELECT COUNT(*) INTO @n FROM rpki_cert_conflict_pick;
SELECT IF(@n = 50, 'OK: 50 rows picked', CONCAT('ERROR: expected 50, got ', @n)) AS pick_status;

DROP TABLE IF EXISTS rpki_cert_test_backup;
CREATE TABLE rpki_cert_test_backup LIKE rpki_cert;

INSERT INTO rpki_cert_test_backup
SELECT c.* FROM rpki_cert c
INNER JOIN rpki_cert_conflict_pick p ON c.id = p.id;

SELECT COUNT(*) AS backup_rows FROM rpki_cert_test_backup;
