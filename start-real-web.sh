#!/usr/bin/env bash
set -euo pipefail

# 一键启动（REAL 上链 + 前后端分离），后台运行不阻塞终端。
#
# 依赖：
# - Docker (MySQL + Fabric test-network 容器已创建)
# - Maven + Java 17
# - Node.js + npm（用于 frontend-node）
#
# 结果：
# - 后端: http://localhost:8082/api
# - 前端: http://localhost:5173

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FABRIC_DIR="${FABRIC_DIR:-/home/xfn/fabric-samples/test-network}"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-rpki-mysql}"
ORDERER_CONTAINER="${ORDERER_CONTAINER:-orderer.example.com}"
PEER1_CONTAINER="${PEER1_CONTAINER:-peer0.org1.example.com}"
PEER2_CONTAINER="${PEER2_CONTAINER:-peer0.org2.example.com}"

BACKEND_PORT="${BACKEND_PORT:-8082}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"

BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:${BACKEND_PORT}/api}"
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"

BACKEND_LOG="${BACKEND_LOG:-${ROOT_DIR}/logs/backend.out}"
FRONTEND_LOG="${FRONTEND_LOG:-${ROOT_DIR}/logs/frontend.out}"

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

wait_mysql_ready() {
  local max="${1:-90}"
  local i=0
  echo "等待 MySQL 就绪（最多 ${max}s）..."
  while ! docker exec "${MYSQL_CONTAINER}" sh -lc \
    'mysql -h 127.0.0.1 -uroot -proot -Nse "SELECT 1"' >/dev/null 2>&1; do
    i=$((i + 1))
    if (( i >= max )); then
      echo "❌ MySQL 在 ${max}s 内未就绪。请检查: docker logs ${MYSQL_CONTAINER}"
      exit 1
    fi
    sleep 1
  done
}

echo "============================================================"
echo "启动 REAL 上链 + 前后端分离（后台运行）"
echo "============================================================"
echo "项目目录: ${ROOT_DIR}"
echo "后端地址: ${BACKEND_BASE_URL}"
echo "前端地址: ${FRONTEND_URL}"
echo

require_cmd docker
require_cmd curl
require_cmd mvn
require_cmd java
require_cmd node
require_cmd npm

echo "[1/6] 启动 MySQL + Fabric 容器..."
if ! docker container inspect "${MYSQL_CONTAINER}" &>/dev/null; then
  echo "❌ 未找到 MySQL 容器: ${MYSQL_CONTAINER}"
  echo "   你可以先创建，例如:"
  echo "   docker run -d --name rpki-mysql -e MYSQL_ROOT_PASSWORD=root -p 3306:3306 mysql:8.0"
  exit 1
fi
docker start "${MYSQL_CONTAINER}" >/dev/null
for c in "${ORDERER_CONTAINER}" "${PEER1_CONTAINER}" "${PEER2_CONTAINER}"; do
  docker start "${c}" >/dev/null 2>&1 || true
done
wait_mysql_ready

echo "[2/6] 确保链码可用（必要时部署）..."
if ! docker ps --format '{{.Names}}' | grep -qE '^dev-peer0\.org1\.example\.com-rpkicc_'; then
  echo "未发现 rpkicc 链码容器，尝试部署链码..."
  ( cd "${FABRIC_DIR}" && ./network.sh deployCC -ccn rpkicc -ccp ./chaincode/rpkicc/go -ccl go )
else
  echo "✓ 已检测到 rpkicc 链码容器（跳过部署）"
fi

echo "[3/6] 初始化数据库（幂等）..."
docker exec "${MYSQL_CONTAINER}" sh -lc \
  "mysql -h 127.0.0.1 -uroot -proot -e 'CREATE DATABASE IF NOT EXISTS rpki_db;'"
docker exec -i "${MYSQL_CONTAINER}" sh -lc \
  "mysql -h 127.0.0.1 -uroot -proot rpki_db" < "${ROOT_DIR}/sql-init.sql"

echo "[4/6] 构建后端 JAR（加速后台启动）..."
( cd "${ROOT_DIR}" && mvn -q -Dmaven.repo.local=.m2 -DskipTests package )
JAR_PATH="${ROOT_DIR}/target/conflict-checker-backend-1.0.0.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "❌ 找不到 JAR: ${JAR_PATH}"
  exit 1
fi

echo "[5/6] 后台启动后端（REAL 上链）..."
if ss -ltnp 2>/dev/null | grep -q ":${BACKEND_PORT} "; then
  echo "✓ 检测到端口 ${BACKEND_PORT} 已在监听（跳过后端启动）"
else
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
  export FABRIC_MODE="REAL"
  nohup java -jar "${JAR_PATH}" > "${BACKEND_LOG}" 2>&1 &
  echo "后端日志: ${BACKEND_LOG}"
fi
wait_http "${BACKEND_BASE_URL}/" 180
echo "✓ 后端就绪"

echo "[6/6] 后台启动前端（Node.js UI）..."
if ss -ltnp 2>/dev/null | grep -q ":${FRONTEND_PORT} "; then
  echo "✓ 检测到端口 ${FRONTEND_PORT} 已在监听（跳过前端启动）"
else
  ( cd "${ROOT_DIR}/frontend-node" && npm install >/dev/null 2>&1 || true )
  export PORT="${FRONTEND_PORT}"
  export BACKEND_BASE="${BACKEND_BASE_URL}"
  export SWAGGER_URL="${BACKEND_BASE_URL}/swagger-ui.html"
  # 数据来源目录（可自行改）
  export DATA_SOURCE_URL="${DATA_SOURCE_URL:-https://ftp.ripe.net/rpki/apnic-afrinic.tal/2018/01/}"
  nohup node "${ROOT_DIR}/frontend-node/server.js" > "${FRONTEND_LOG}" 2>&1 &
  echo "前端日志: ${FRONTEND_LOG}"
fi
wait_http "${FRONTEND_URL}/" 60
echo "✓ 前端就绪"

echo
echo "============================================================"
echo "启动完成（后台运行，不阻塞终端）"
echo "- 前端: ${FRONTEND_URL}"
echo "- 后端: ${BACKEND_BASE_URL}"
echo "- Swagger: ${BACKEND_BASE_URL}/swagger-ui.html"
echo "============================================================"
