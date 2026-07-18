#!/usr/bin/env bash
set -euo pipefail

# 演示用：只下载“某一天”的 RPKI 归档快照（repo.tar.xz），并启动前后端（后台不阻塞）。
#
# 默认使用 2026-01-01 单日快照：
# - apnic-afrinic.tal 该日在镜像上无 repo.tar.xz（404）
# - apnic.tal 单日包约 136MB，解压后需大量磁盘；根分区紧张时请改用更小源，例如 afrinic.tal（约 25MB）
#   https://ftp.ripe.net/rpki/afrinic.tal/2026/01/01/repo.tar.xz
#
# 可通过环境变量覆盖：
#   DEMO_REPO_URL=.../YYYY/MM/DD/repo.tar.xz ./start-demo-day.sh
#   FABRIC_MODE=REAL|MOCK (默认 REAL)
#
# 输出：
# - 后端: http://localhost:8082/api
# - 前端: http://localhost:5173
#
# 本地证书目录（与 application-dev 一致）：${ROOT_DIR}/rpki-data/{downloads,extracted}
# 首次会下载并解压；之后同机再次调用 download-and-parse 将默认走本地缓存（不重复下载）。
# 强制重新下载：curl -X POST ".../cert/download-and-parse?forceNetwork=true"
# 仅冲突检测：curl -X POST ".../cert/detect-conflicts" （启动时若 auto-detect-conflicts=true 也会自动跑）

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BACKEND_PORT="${BACKEND_PORT:-8082}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:${BACKEND_PORT}/api}"
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"

DEMO_REPO_URL="${DEMO_REPO_URL:-https://ftp.ripe.net/rpki/afrinic.tal/2026/01/01/repo.tar.xz}"
FABRIC_MODE="${FABRIC_MODE:-REAL}"

BACKEND_LOG="${BACKEND_LOG:-${ROOT_DIR}/logs/backend-demo-day.out}"
FRONTEND_LOG="${FRONTEND_LOG:-${ROOT_DIR}/logs/frontend-demo-day.out}"

mkdir -p "${ROOT_DIR}/logs"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "❌ 缺少命令: $1"
    exit 1
  fi
}

wait_http() {
  local url="$1"
  local max="${2:-120}"
  local i=0
  while ! curl -fsS --max-time 2 "$url" >/dev/null 2>&1; do
    i=$((i + 1))
    if (( i >= max )); then
      echo "❌ 超时未就绪: $url (等待 ${max}s)"
      return 1
    fi
    sleep 1
  done
}

echo "============================================================"
echo "演示启动（单日证书包）+ 前后端分离（后台运行）"
echo "============================================================"
echo "DEMO_REPO_URL: ${DEMO_REPO_URL}"
echo "Fabric 模式: ${FABRIC_MODE}"
echo "后端地址: ${BACKEND_BASE_URL}"
echo "前端地址: ${FRONTEND_URL}"
echo "本地 RPKI 目录: ${ROOT_DIR}/rpki-data （downloads=压缩包，extracted=解压后的 .cer）"
echo

require_cmd curl
require_cmd mvn
require_cmd java
require_cmd node
require_cmd npm

echo "[1/5] 构建后端 JAR..."
# 若库 rpki_db 存在但未建表，会导致 rpki_cert doesn't exist；有 mysql 客户端时自动执行 sql-init.sql
if [[ "${SKIP_SQL_INIT:-0}" != "1" ]] && command -v mysql >/dev/null 2>&1; then
  _MYSQL_USER="${MYSQL_USER:-rpki}"
  _MYSQL_PASS="${MYSQL_PASSWORD:-rpki123}"
  if MYSQL_PWD="$_MYSQL_PASS" mysql -h127.0.0.1 -P3306 -u"$_MYSQL_USER" -e "SELECT 1" >/dev/null 2>&1; then
    if MYSQL_PWD="$_MYSQL_PASS" mysql -h127.0.0.1 -P3306 -u"$_MYSQL_USER" < "${ROOT_DIR}/sql-init.sql" >/dev/null 2>&1; then
      echo "  ✓ 已执行 sql-init.sql（确保 rpki_cert 等表存在）"
      # 旧库缺列时补全（列已存在则 ALTER 报错，忽略即可）
      MYSQL_PWD="$_MYSQL_PASS" mysql -h127.0.0.1 -P3306 -u"$_MYSQL_USER" < "${ROOT_DIR}/sql-add-cer-file-name.sql" >/dev/null 2>&1 || true
      MYSQL_PWD="$_MYSQL_PASS" mysql -h127.0.0.1 -P3306 -u"$_MYSQL_USER" < "${ROOT_DIR}/sql-add-subject-authority-key.sql" >/dev/null 2>&1 || true
    else
      echo "  ⚠ sql-init.sql 执行失败（表可能已存在或权限不足），若仍报缺表请手动导入"
    fi
  else
    echo "  ⚠ 无法连接 MySQL 127.0.0.1（用户 ${_MYSQL_USER}），跳过建表。请先启动 MySQL 后执行:"
    echo "    mysql -h127.0.0.1 -u${_MYSQL_USER} -p < ${ROOT_DIR}/sql-init.sql"
  fi
elif [[ "${SKIP_SQL_INIT:-0}" != "1" ]]; then
  echo "  ⚠ 未找到 mysql 客户端；若报 rpki_cert 不存在，请手动: mysql ... < ${ROOT_DIR}/sql-init.sql"
fi
( cd "${ROOT_DIR}" && mvn -q -Dmaven.repo.local=.m2 -DskipTests package )
JAR_PATH="${ROOT_DIR}/target/conflict-checker-backend-1.0.0.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "❌ 找不到 JAR: ${JAR_PATH}"
  exit 1
fi

echo "[2/5] 后台启动后端（DEMO 单日：命令行清空 demo-month-base，避免被 application-dev.yml 整月配置覆盖）..."

# 必须重启后端，否则端口已被占用时会沿用旧进程配置，与本次 DEMO_REPO_URL 不一致
if ss -ltnp 2>/dev/null | grep -q ":${BACKEND_PORT} "; then
  echo "⚠ 释放端口 ${BACKEND_PORT} 以应用本次 DEMO 单日配置..."
  if command -v fuser >/dev/null 2>&1; then
    fuser -k "${BACKEND_PORT}/tcp" >/dev/null 2>&1 || true
  fi
  sleep 2
fi
nohup java -jar "${JAR_PATH}" \
  --fabric.mode="${FABRIC_MODE}" \
  --rpki.sync.rirs=DEMO \
  --rpki.sources.demo-month-base= \
  --rpki.sources.demo="${DEMO_REPO_URL}" \
  > "${BACKEND_LOG}" 2>&1 &
echo "后端日志: ${BACKEND_LOG}"
wait_http "${BACKEND_BASE_URL}/" 180
echo "✓ 后端就绪"

echo "[3/5] 后台启动前端 UI..."
if ss -ltnp 2>/dev/null | grep -q ":${FRONTEND_PORT} "; then
  echo "✓ 检测到端口 ${FRONTEND_PORT} 已在监听（跳过前端启动）"
else
  ( cd "${ROOT_DIR}/frontend-node" && npm install >/dev/null 2>&1 || true )
  export PORT="${FRONTEND_PORT}"
  export BACKEND_BASE="${BACKEND_BASE_URL}"
  export SWAGGER_URL="${BACKEND_BASE_URL}/swagger-ui.html"
  export DATA_SOURCE_URL="$(dirname "${DEMO_REPO_URL}")/"
  nohup node "${ROOT_DIR}/frontend-node/server.js" > "${FRONTEND_LOG}" 2>&1 &
  echo "前端日志: ${FRONTEND_LOG}"
fi
wait_http "${FRONTEND_URL}/" 60
echo "✓ 前端就绪"

echo "[4/5] 同步：下载并解析（单日包；若 rpki-data 已有 .cer 则跳过网络，仅必要时入库）..."
echo "    提示：下载完成后还会解压、解析、写库；证书多时代理无输出属正常，请看 logs/backend-demo-day.out"
RESP="$(curl -s -X POST "${BACKEND_BASE_URL}/cert/download-and-parse")"
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${RESP}" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
  d = json.loads(raw)
except Exception:
  print(raw); sys.exit(0)
print("--- 下载并解析：简明摘要 ---")
data = d.get("data") or {}
print(data.get("readableSummaryText") or "(无)")
print("--- 常用数字 ---")
for k in ("runMode","savedCount","certCandidates","parseFailed","skippedParse","dbCertRows"):
  if k in data:
    print(" ", k + ":", data[k])
'
else
  echo "${RESP}"
fi

echo "[5/5] 冲突检测（基于库中证书；两两比对，证书很多时可能很慢）..."
DETECT_RESP="$(curl -s -X POST "${BACKEND_BASE_URL}/cert/detect-conflicts" -H 'Content-Type: application/json' -d '{}')"
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${DETECT_RESP}" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
  d = json.loads(raw)
  data = d.get("data")
  print("--- 冲突检测 ---")
  if isinstance(data, list):
    print(" 冲突条数:", len(data))
  else:
    print(raw[:500])
except Exception:
  print(raw[:800])
'
else
  echo "${DETECT_RESP}" | head -c 800
fi

echo
echo "============================================================"
echo "启动完成（后台运行，不阻塞终端）"
echo "- 前端: ${FRONTEND_URL}"
echo "- 后端: ${BACKEND_BASE_URL}"
echo "- Swagger: ${BACKEND_BASE_URL}/swagger-ui.html"
echo "============================================================"
