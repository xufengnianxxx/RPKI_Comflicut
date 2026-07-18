#!/usr/bin/env bash
# 清空证书与冲突相关表后，触发一次「下载并解析」（本地已有 .cer 时不走网络）。
# 用于补全 cer_file_name 等字段，或从零重建 DEMO 库数据。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-rpki_db}"
DB_USER="${MYSQL_USER:-rpki}"
DB_PASS="${MYSQL_PASSWORD:-rpki123}"
BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:8082/api}"

echo "=== 清库（${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}）==="
MYSQL_PWD="${DB_PASS}" mysql -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USER}" "${DB_NAME}" <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE fabric_tx_record;
TRUNCATE TABLE conflict_record;
TRUNCATE TABLE rpki_cert;
SET FOREIGN_KEY_CHECKS = 1;
SQL
echo "✓ 已清空 rpki_cert / conflict_record / fabric_tx_record"

echo "=== 触发解析（POST ${BACKEND_BASE_URL}/cert/download-and-parse）==="
echo "    库为空时会从磁盘重新解析 .cer 并入库（含 cer_file_name）"
RESP="$(curl -s -S -X POST "${BACKEND_BASE_URL}/cert/download-and-parse")"
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${RESP}" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
  d = json.loads(raw)
except Exception:
  print(raw); sys.exit(1)
print("success:", d.get("success"), "message:", (d.get("message") or "")[:200])
data = d.get("data") or {}
for k in ("skippedParse", "savedCount", "certCandidates", "parseFailed", "dbCertRows", "runMode"):
  if k in data:
    print(" ", k + ":", data[k])
if d.get("success") is False:
  sys.exit(1)
'
else
  echo "${RESP}" | head -c 1200
fi

echo "完成。如需再跑冲突检测: curl -s -X POST \"${BACKEND_BASE_URL}/cert/detect-conflicts\" -H 'Content-Type: application/json' -d '{}'"
