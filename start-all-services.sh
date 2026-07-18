#!/usr/bin/env bash
set -euo pipefail

# 一键启动：Docker MySQL → Hyperledger Fabric test-network → 数据库初始化 → 后端 + 前端（后台）
#
# 环境变量（可选）：
#   FABRIC_DIR          Fabric test-network 目录（默认先试 $HOME/fabric-samples/test-network，不存在则用 /home/xfn/fabric-samples/test-network）
#   MYSQL_CONTAINER     MySQL 容器名（默认 rpki-mysql）
#   MYSQL_ROOT_PASSWORD 容器内 root 密码（默认 root，与下方 docker exec 一致）
#   AUTO_CREATE_MYSQL   未找到 MySQL 容器时是否自动 docker run（默认 1）
#   START_WEB           是否构建并后台启动后端+前端（默认 1）；设为 0 则只做基础设施
#   BACKEND_PORT / FRONTEND_PORT / BACKEND_LOG / FRONTEND_LOG 同 start-real-web.sh
#
# 示例：
#   ./start-all-services.sh
#   START_WEB=0 ./start-all-services.sh    # 只起 MySQL + Fabric + 建库

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

FABRIC_DIR="${FABRIC_DIR:-}"
if [[ -z "${FABRIC_DIR}" ]]; then
  if [[ -d "${HOME}/fabric-samples/test-network" ]]; then
    FABRIC_DIR="${HOME}/fabric-samples/test-network"
  else
    FABRIC_DIR="/home/xfn/fabric-samples/test-network"
  fi
fi

MYSQL_CONTAINER="${MYSQL_CONTAINER:-rpki-mysql}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
MYSQL_APP_USER="${MYSQL_APP_USER:-rpki}"
MYSQL_APP_PASSWORD="${MYSQL_APP_PASSWORD:-rpki123}"
AUTO_CREATE_MYSQL="${AUTO_CREATE_MYSQL:-1}"
START_WEB="${START_WEB:-1}"

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
  local max="${1:-120}"
  local i=0
  echo "等待 MySQL 就绪（最多 ${max}s）..."
  while ! docker exec "${MYSQL_CONTAINER}" sh -lc \
    "mysql -h 127.0.0.1 -uroot -p${MYSQL_ROOT_PASSWORD} -Nse 'SELECT 1'" >/dev/null 2>&1; do
    i=$((i + 1))
    if (( i >= max )); then
      echo "❌ MySQL 在 ${max}s 内未就绪。请检查: docker logs ${MYSQL_CONTAINER}"
      exit 1
    fi
    sleep 1
  done
}

ensure_mysql_container() {
  if docker container inspect "${MYSQL_CONTAINER}" &>/dev/null; then
    echo "✓ 已存在容器: ${MYSQL_CONTAINER}"
    docker start "${MYSQL_CONTAINER}" >/dev/null
    return 0
  fi
  if [[ "${AUTO_CREATE_MYSQL}" != "1" ]]; then
    echo "❌ 未找到 MySQL 容器: ${MYSQL_CONTAINER}"
    echo "   可执行: AUTO_CREATE_MYSQL=1 $0"
    echo "   或手动: docker run -d --name ${MYSQL_CONTAINER} -e MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD} -p 3306:3306 mysql:8.0"
    exit 1
  fi
  echo "创建 MySQL 容器 ${MYSQL_CONTAINER}（root 密码: ${MYSQL_ROOT_PASSWORD}，映射 3306）..."
  docker run -d --name "${MYSQL_CONTAINER}" \
    -e "MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}" \
    -p 3306:3306 \
    mysql:8.0 >/dev/null
}

bring_up_fabric() {
  if [[ ! -f "${FABRIC_DIR}/network.sh" ]]; then
    echo "❌ 未找到 Fabric network.sh: ${FABRIC_DIR}/network.sh"
    echo "   请安装 fabric-samples 并设置 FABRIC_DIR，或克隆: https://github.com/hyperledger/fabric-samples"
    exit 1
  fi

  if docker ps --format '{{.Names}}' | grep -q "^${ORDERER_CONTAINER}\$"; then
    echo "✓ Fabric orderer 已在运行"
    return 0
  fi

  if docker container inspect "${ORDERER_CONTAINER}" &>/dev/null; then
    echo "启动已停止的 Fabric 容器..."
    docker start "${ORDERER_CONTAINER}" >/dev/null 2>&1 || true
    docker start "${PEER1_CONTAINER}" >/dev/null 2>&1 || true
    docker start "${PEER2_CONTAINER}" >/dev/null 2>&1 || true
    for c in ca_org1 ca_org2; do
      docker container inspect "${c}" &>/dev/null && docker start "${c}" >/dev/null 2>&1 || true
    done
    sleep 4
  fi

  if docker ps --format '{{.Names}}' | grep -q "^${ORDERER_CONTAINER}\$"; then
    echo "✓ Fabric 核心容器已启动"
    return 0
  fi

  echo "拉起 Fabric test-network（./network.sh up createChannel -ca）..."
  ( cd "${FABRIC_DIR}" && ./network.sh up createChannel -ca )
}

init_database() {
  docker exec "${MYSQL_CONTAINER}" sh -lc \
    "mysql -h 127.0.0.1 -uroot -p${MYSQL_ROOT_PASSWORD} -e 'CREATE DATABASE IF NOT EXISTS rpki_db;'"
  docker exec -i "${MYSQL_CONTAINER}" sh -lc \
    "mysql -h 127.0.0.1 -uroot -p${MYSQL_ROOT_PASSWORD} rpki_db" < "${ROOT_DIR}/sql-init.sql"
  for patch in sql-add-cer-file-name.sql sql-add-subject-authority-key.sql; do
    if [[ -f "${ROOT_DIR}/${patch}" ]]; then
      docker exec -i "${MYSQL_CONTAINER}" sh -lc \
        "mysql -h 127.0.0.1 -uroot -p${MYSQL_ROOT_PASSWORD} rpki_db" < "${ROOT_DIR}/${patch}" 2>/dev/null || true
    fi
  done
  docker exec "${MYSQL_CONTAINER}" mysql -h127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD}" -e \
    "CREATE USER IF NOT EXISTS '${MYSQL_APP_USER}'@'%' IDENTIFIED BY '${MYSQL_APP_PASSWORD}'; \
     GRANT ALL PRIVILEGES ON rpki_db.* TO '${MYSQL_APP_USER}'@'%'; FLUSH PRIVILEGES;" >/dev/null
  echo "✓ 数据库 rpki_db 已初始化，应用账号 ${MYSQL_APP_USER}@%"
}

maybe_deploy_chaincode() {
  if docker ps --format '{{.Names}}' | grep -qE '^dev-peer0\.org1\.example\.com-rpkicc_'; then
    echo "✓ 已检测到 rpkicc 链码容器（跳过部署）"
    return 0
  fi
  echo "部署链码 rpkicc..."
  ( cd "${FABRIC_DIR}" && ./network.sh deployCC -ccn rpkicc -ccp ./chaincode/rpkicc/go -ccl go )
}

start_backend_frontend() {
  require_cmd mvn
  require_cmd java
  require_cmd node
  require_cmd npm

  echo "构建后端 JAR..."
  ( cd "${ROOT_DIR}" && mvn -q -Dmaven.repo.local=.m2 -DskipTests package )
  local jar="${ROOT_DIR}/target/conflict-checker-backend-1.0.0.jar"
  if [[ ! -f "${jar}" ]]; then
    echo "❌ 找不到 JAR: ${jar}"
    exit 1
  fi

  echo "后台启动后端（REAL 上链）..."
  if ss -ltnp 2>/dev/null | grep -q ":${BACKEND_PORT} "; then
    echo "✓ 端口 ${BACKEND_PORT} 已在监听（跳过后端启动）"
  else
    export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
    export FABRIC_MODE="REAL"
    export MYSQL_USER="${MYSQL_APP_USER}"
    export MYSQL_PASSWORD="${MYSQL_APP_PASSWORD}"
    nohup java -jar "${jar}" > "${BACKEND_LOG}" 2>&1 &
    echo "后端日志: ${BACKEND_LOG}"
  fi
  wait_http "${BACKEND_BASE_URL}/" 180
  echo "✓ 后端就绪"

  echo "后台启动前端..."
  if ss -ltnp 2>/dev/null | grep -q ":${FRONTEND_PORT} "; then
    echo "✓ 端口 ${FRONTEND_PORT} 已在监听（跳过前端启动）"
  else
    ( cd "${ROOT_DIR}/frontend-node" && npm install >/dev/null 2>&1 || true )
    export PORT="${FRONTEND_PORT}"
    export BACKEND_BASE="${BACKEND_BASE_URL}"
    export SWAGGER_URL="${BACKEND_BASE_URL}/swagger-ui.html"
    export DATA_SOURCE_URL="${DATA_SOURCE_URL:-https://ftp.ripe.net/rpki/apnic-afrinic.tal/2018/01/}"
    nohup node "${ROOT_DIR}/frontend-node/server.js" > "${FRONTEND_LOG}" 2>&1 &
    echo "前端日志: ${FRONTEND_LOG}"
  fi
  wait_http "${FRONTEND_URL}/" 60
  echo "✓ 前端就绪"
}

# --- main ---
echo "============================================================"
echo "一键启动：MySQL (Docker) + Fabric + 数据库 + 可选 Web"
echo "============================================================"
echo "项目: ${ROOT_DIR}"
echo "Fabric: ${FABRIC_DIR}"
echo "MySQL 容器: ${MYSQL_CONTAINER}"
echo

require_cmd docker
require_cmd curl

echo "[1/5] MySQL（Docker）..."
ensure_mysql_container
wait_mysql_ready

echo "[2/5] Fabric test-network..."
bring_up_fabric

echo "[3/5] 链码 rpkicc..."
maybe_deploy_chaincode

echo "[4/5] 数据库初始化..."
init_database

if [[ "${START_WEB}" == "1" ]]; then
  echo "[5/5] 后端 + 前端..."
  start_backend_frontend
  echo
  echo "============================================================"
  echo "全部就绪（后台进程见 logs/）"
  echo "- 前端: ${FRONTEND_URL}"
  echo "- 后端: ${BACKEND_BASE_URL}"
  echo "- Swagger: ${BACKEND_BASE_URL}/swagger-ui.html"
  echo "============================================================"
else
  echo "[5/5] 已跳过 Web（START_WEB=0）"
  echo "随后可执行: ./start-real-web.sh"
  echo "============================================================"
fi
