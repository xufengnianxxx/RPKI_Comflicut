#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-rpki_db}"
DB_USER="${MYSQL_USER:-rpki}"
DB_PASS="${MYSQL_PASSWORD:-rpki123}"
BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:8082/api}"
DEMO_RIR="${DEMO_RIR:-DEMO_CASE}"

echo "=== 准备演示冲突数据 ==="
echo "DB: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "API: ${BACKEND_BASE_URL}"
echo "RIR: ${DEMO_RIR}"

MYSQL_PWD="${DB_PASS}" mysql -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USER}" "${DB_NAME}" <<'SQL'
SET @DEMO_RIR := 'DEMO_CASE';

DELETE FROM conflict_record
WHERE cert_id_a IN (SELECT id FROM rpki_cert WHERE rir = @DEMO_RIR)
   OR cert_id_b IN (SELECT id FROM rpki_cert WHERE rir = @DEMO_RIR);

DELETE FROM rpki_cert WHERE rir = @DEMO_RIR;

-- 案例1：同级IP包含（IP_CONTAINMENT_SIBLING）
INSERT INTO rpki_cert
(cert_name, cer_file_name, serial_number, issuer, subject, rir, not_before, not_after,
 ipv4_prefixes, ipv6_prefixes, as_numbers, parent_cert_id, subject_key_id, authority_key_id, revoked, has_conflict, conflict_details,
 cert_hash, raw_cert_data, created_at, updated_at)
VALUES
('demo-ip-parent', 'demo-ip-parent.cer', '1001', 'CN=DemoIssuerA', 'CN=DemoSiblingA', @DEMO_RIR, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
 '["10.10.0.0/16"]', '[]', '["65010"]', NULL, NULL, NULL, 0, 0, NULL,
 CONCAT('demo-hash-', UUID()), 'demo', NOW(), NOW());

INSERT INTO rpki_cert
(cert_name, cer_file_name, serial_number, issuer, subject, rir, not_before, not_after,
 ipv4_prefixes, ipv6_prefixes, as_numbers, parent_cert_id, subject_key_id, authority_key_id, revoked, has_conflict, conflict_details,
 cert_hash, raw_cert_data, created_at, updated_at)
VALUES
('demo-ip-child', 'demo-ip-child.cer', '1002', 'CN=DemoIssuerA', 'CN=DemoSiblingB', @DEMO_RIR, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
 '["10.10.1.0/24"]', '[]', '["65011"]', NULL, NULL, NULL, 0, 0, NULL,
 CONCAT('demo-hash-', UUID()), 'demo', NOW(), NOW());

-- 案例2：同级AS重复（AS_OVERLAP_SIBLING）
INSERT INTO rpki_cert
(cert_name, cer_file_name, serial_number, issuer, subject, rir, not_before, not_after,
 ipv4_prefixes, ipv6_prefixes, as_numbers, parent_cert_id, subject_key_id, authority_key_id, revoked, has_conflict, conflict_details,
 cert_hash, raw_cert_data, created_at, updated_at)
VALUES
('demo-as-a', 'demo-as-a.cer', '1003', 'CN=DemoIssuerB', 'CN=DemoAsA', @DEMO_RIR, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
 '["192.0.2.0/24"]', '[]', '["65020","65021"]', NULL, NULL, NULL, 0, 0, NULL,
 CONCAT('demo-hash-', UUID()), 'demo', NOW(), NOW());

INSERT INTO rpki_cert
(cert_name, cer_file_name, serial_number, issuer, subject, rir, not_before, not_after,
 ipv4_prefixes, ipv6_prefixes, as_numbers, parent_cert_id, subject_key_id, authority_key_id, revoked, has_conflict, conflict_details,
 cert_hash, raw_cert_data, created_at, updated_at)
VALUES
('demo-as-b', 'demo-as-b.cer', '1004', 'CN=DemoIssuerB', 'CN=DemoAsB', @DEMO_RIR, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
 '["198.51.100.0/24"]', '[]', '["65020","65022"]', NULL, NULL, NULL, 0, 0, NULL,
 CONCAT('demo-hash-', UUID()), 'demo', NOW(), NOW());

-- 案例3：父子越权 + 父撤销后子仍可用（IP_OUT_OF_SCOPE / AS_OUT_OF_SCOPE / CHAIN_RESIDUAL_AFTER_REVOKE）
INSERT INTO rpki_cert
(cert_name, cer_file_name, serial_number, issuer, subject, rir, not_before, not_after,
 ipv4_prefixes, ipv6_prefixes, as_numbers, parent_cert_id, subject_key_id, authority_key_id, revoked, has_conflict, conflict_details,
 cert_hash, raw_cert_data, created_at, updated_at)
VALUES
('demo-parent', 'demo-parent.cer', '1005', 'CN=DemoRoot', 'CN=DemoParentCA', @DEMO_RIR, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
 '["172.16.0.0/16"]', '[]', '["65100"]', NULL, NULL, NULL, 1, 0, NULL,
 CONCAT('demo-hash-', UUID()), 'demo', NOW(), NOW());

SET @PARENT_ID := LAST_INSERT_ID();

INSERT INTO rpki_cert
(cert_name, cer_file_name, serial_number, issuer, subject, rir, not_before, not_after,
 ipv4_prefixes, ipv6_prefixes, as_numbers, parent_cert_id, subject_key_id, authority_key_id, revoked, has_conflict, conflict_details,
 cert_hash, raw_cert_data, created_at, updated_at)
VALUES
('demo-child', 'demo-child.cer', '1006', 'CN=DemoParentCA', 'CN=DemoChildCA', @DEMO_RIR, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,
 '["172.17.0.0/16"]', '[]', '["65101"]', @PARENT_ID, NULL, NULL, 0, 0, NULL,
 CONCAT('demo-hash-', UUID()), 'demo', NOW(), NOW());
SQL

echo "=== 已写入 DEMO_CASE 样例证书，触发冲突检测 ==="
DETECT_RESP="$(curl -s -X POST "${BACKEND_BASE_URL}/cert/detect-conflicts" \
  -H 'Content-Type: application/json' \
  -d "{\"rir\":\"${DEMO_RIR}\"}")"

if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${DETECT_RESP}" | python3 -c '
import json,sys
raw=sys.stdin.read()
try:
  d=json.loads(raw)
  data=d.get("data")
  if isinstance(data,list):
    print("冲突条数:",len(data))
    # 展示前几条类型，演示更直观
    kinds=[]
    for x in data:
      t=x.get("conflictType")
      if t and t not in kinds:
        kinds.append(t)
    print("冲突类型:", ", ".join(kinds[:10]) if kinds else "(无)")
  else:
    print(raw[:800])
except Exception:
  print(raw[:800])
'
else
  echo "${DETECT_RESP}" | cut -c1-800
fi

echo "完成。你可以在前端按 rir=${DEMO_RIR} 查看演示冲突。"
