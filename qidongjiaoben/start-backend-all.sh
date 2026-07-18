#!/usr/bin/env bash
# 后端一键启动：Docker MySQL → Fabric test-network → 数据库初始化与补丁 →（可选）Java 链码 rpkiccjava → Spring Boot
# 不包含前端；前端请使用同目录下 start-frontend.sh
#
# 环境变量（可选）：
#   FABRIC_DIR           fabric-samples/test-network 路径（默认先试 $HOME/fabric-samples/test-network）
#   FABRIC_SAMPLES       fabric-samples 根目录；未设置时若 FABRIC_DIR 名为 test-network 会自动设为上一级目录
#   RECREATE_FABRIC_NETWORK  设为 1 时先执行 network.sh down 再 up createChannel（清空链上状态，慎用）
#   MYSQL_CONTAINER      默认 rpki-mysql
#   MYSQL_ROOT_PASSWORD  默认 root
#   AUTO_CREATE_MYSQL    无容器时是否 docker run（默认 1）
#   DEPLOY_JAVA_CC       是否部署/升级 rpkiccjava（默认 0，日常开机快且避免 sequence 狂涨）；首次或改链码后请设 1
#   BACKEND_PORT         默认 8082
#   RESTART_BACKEND      默认 1：先释放 BACKEND_PORT 再启动新 JAR（避免「端口占用跳过」→ 旧进程 → 前端 proxy socket hang up）
#                        设为 0 则若端口已占用则跳过启动后端（仅特殊调试）
#   SPRING_PROFILES_ACTIVE  默认 dev
#   FABRIC_MODE          默认 REAL；可设为 MOCK 做无链调试
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

FABRIC_DIR="${FABRIC_DIR:-}"
if [[ -z "${FABRIC_DIR}" ]]; then
  if [[ -d "${HOME}/fabric-samples/test-network" ]]; then
    FABRIC_DIR="${HOME}/fabric-samples/test-network"
  else
    FABRIC_DIR="/home/xfn/fabric-samples/test-network"
  fi
fi

# FABRIC_DIR 为 .../test-network 时自动导出 FABRIC_SAMPLES，便于 peer 与 Java 链码部署脚本
if [[ -z "${FABRIC_SAMPLES:-}" && "$(basename "${FABRIC_DIR}")" == "test-network" ]]; then
  export FABRIC_SAMPLES="$(cd "${FABRIC_DIR}/.." && pwd)"
fi

MYSQL_CONTAINER="${MYSQL_CONTAINER:-rpki-mysql}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
MYSQL_APP_USER="${MYSQL_APP_USER:-rpki}"
MYSQL_APP_PASSWORD="${MYSQL_APP_PASSWORD:-rpki123}"
AUTO_CREATE_MYSQL="${AUTO_CREATE_MYSQL:-1}"
DEPLOY_JAVA_CC="${DEPLOY_JAVA_CC:-0}"
ORDERER_CONTAINER="${ORDERER_CONTAINER:-orderer.example.com}"
PEER1_CONTAINER="${PEER1_CONTAINER:-peer0.org1.example.com}"
PEER2_CONTAINER="${PEER2_CONTAINER:-peer0.org2.example.com}"

BACKEND_PORT="${BACKEND_PORT:-8082}"
BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://localhost:${BACKEND_PORT}/api}"
BACKEND_LOG="${BACKEND_LOG:-${ROOT_DIR}/logs/backend.out}"
RESTART_BACKEND="${RESTART_BACKEND:-1}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
FABRIC_MODE="${FABRIC_MODE:-REAL}"

mkdir -p "${ROOT_DIR}/logs"

# 释放本机 TCP 端口上的监听进程（避免脚本「跳过启动」后仍使用陈旧/损坏的 java 进程）
free_backend_port() {
  local port="$1"
  if command -v fuser >/dev/null 2>&1; then
    if fuser "${port}/tcp" >/dev/null 2>&1; then
      echo "释放端口 ${port}（fuser -k）..."
      fuser -k "${port}/tcp" >/dev/null 2>&1 || true
      sleep 2
    fi
  fi
  local pids=""
  if command -v ss >/dev/null 2>&1; then
    # pipefail 下 grep 无匹配会返回 1，勿让 $(...) 触发 set -e 直接退出整个脚本
    pids=$(ss -ltnp 2>/dev/null | grep ":${port} " | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sort -u) || true
  fi
  for p in ${pids}; do
    [[ -z "${p}" ]] && continue
    echo "停止占用端口 ${port} 的进程 pid=${p}..."
    kill "${p}" 2>/dev/null || true
  done
  sleep 2
  for p in ${pids}; do
    [[ -z "${p}" ]] && continue
    kill -9 "${p}" 2>/dev/null || true
  done
  sleep 1
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "❌ 缺少命令: $1"
    exit 1
  fi
}

wait_http() {
  local url="$1"
  local max="${2:-180}"
  local i=0
  while ! curl -fsS --max-time 2 "$url" >/dev/null 2>&1; do
    i=$((i + 1))
    if (( i >= max )); then
      echo "❌ 超时未就绪: $url (${max}s)"
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
      echo "❌ MySQL 未就绪: docker logs ${MYSQL_CONTAINER}"
      exit 1
    fi
    sleep 1
  done
}

ensure_mysql_container() {
  if docker container inspect "${MYSQL_CONTAINER}" &>/dev/null; then
    echo "✓ MySQL 容器已存在: ${MYSQL_CONTAINER}"
    docker start "${MYSQL_CONTAINER}" >/dev/null
    return 0
  fi
  if [[ "${AUTO_CREATE_MYSQL}" != "1" ]]; then
    echo "❌ 未找到 MySQL 容器。可: AUTO_CREATE_MYSQL=1 $0"
    exit 1
  fi
  echo "创建 MySQL 容器（端口 3306，root 密码 ${MYSQL_ROOT_PASSWORD}）..."
  docker run -d --name "${MYSQL_CONTAINER}" \
    -e "MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD}" \
    -p 3306:3306 \
    mysql:8.0 >/dev/null
}

bring_up_fabric() {
  if [[ ! -f "${FABRIC_DIR}/network.sh" ]]; then
    echo "❌ 未找到 Fabric: ${FABRIC_DIR}/network.sh"
    echo "   请安装 fabric-samples 并设置 FABRIC_DIR"
    exit 1
  fi

  orderer_running() {
    docker ps --format '{{.Names}}' | grep -q "^${ORDERER_CONTAINER}\$"
  }

  # 强制完整重建网络（会 down 掉旧容器与卷，链上数据清空）
  if [[ "${RECREATE_FABRIC_NETWORK:-0}" == "1" ]]; then
    echo "RECREATE_FABRIC_NETWORK=1：network.sh down → up createChannel -ca …"
    ( cd "${FABRIC_DIR}" && ./network.sh down ) || true
    ( cd "${FABRIC_DIR}" && ./network.sh up createChannel -ca )
    sleep 3
    if ! orderer_running; then
      echo "❌ network.sh up 后仍未检测到 orderer，请查看 ${FABRIC_DIR} 下脚本输出"
      exit 1
    fi
    echo "✓ Fabric 网络已重新创建（通道 mychannel）"
    return 0
  fi

  if orderer_running; then
    echo "✓ Fabric orderer 已在运行（test-network 已就绪）"
    return 0
  fi

  echo "正在启动 Fabric test-network（先尝试启动已有容器，必要时执行 network.sh up）…"

  local c
  for c in \
    orderer.example.com \
    peer0.org1.example.com peer1.org1.example.com \
    peer0.org2.example.com peer1.org2.example.com \
    ca_org1 ca_org2 \
    couchdb0 couchdb1 couchdb2 couchdb3; do
    if docker container inspect "${c}" &>/dev/null; then
      docker start "${c}" >/dev/null 2>&1 || true
    fi
  done

  local waited=0
  while (( waited < 45 )); do
    if orderer_running; then
      echo "✓ Fabric 核心已运行（orderer/peers 等，约 ${waited}s 内就绪）"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  echo "仅靠恢复旧容器未成功，执行: cd ${FABRIC_DIR} && ./network.sh up createChannel -ca"
  ( cd "${FABRIC_DIR}" && ./network.sh up createChannel -ca )
  sleep 5
  if ! orderer_running; then
    echo "❌ Fabric 仍未就绪。可手动排查:"
    echo "   cd ${FABRIC_DIR} && ./network.sh down && ./network.sh up createChannel -ca"
    echo "   或本脚本: RECREATE_FABRIC_NETWORK=1 $0"
    exit 1
  fi
  echo "✓ Fabric test-network 已通过 network.sh 拉起"
}

init_database() {
  docker exec "${MYSQL_CONTAINER}" sh -lc \
    "mysql -h 127.0.0.1 -uroot -p${MYSQL_ROOT_PASSWORD} -e 'CREATE DATABASE IF NOT EXISTS rpki_db;'"
  echo "执行 sql-init.sql..."
  docker exec -i "${MYSQL_CONTAINER}" sh -lc \
    "mysql -h 127.0.0.1 -uroot -p${MYSQL_ROOT_PASSWORD} rpki_db" < "${ROOT_DIR}/sql-init.sql"

  # 增量补丁（存在则执行，失败不阻断）
  local patches=(
    "sql-add-cer-file-name.sql"
    "sql-add-subject-authority-key.sql"
    "sql-patch-conflict-fabric-columns.sql"
    "sql-patch-fabric-anchor-resilience.sql"
    "sql-patch-pair-detection-record.sql"
  )
  for patch in "${patches[@]}"; do
    if [[ -f "${ROOT_DIR}/${patch}" ]]; then
      echo "执行 ${patch}..."
      docker exec -i "${MYSQL_CONTAINER}" sh -lc \
        "mysql -h 127.0.0.1 -uroot -p${MYSQL_ROOT_PASSWORD} rpki_db" < "${ROOT_DIR}/${patch}" 2>/dev/null || \
        echo "⚠️ ${patch} 执行有告警（可能已应用过），可忽略"
    fi
  done

  docker exec "${MYSQL_CONTAINER}" mysql -h127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD}" -e \
    "CREATE USER IF NOT EXISTS '${MYSQL_APP_USER}'@'%' IDENTIFIED BY '${MYSQL_APP_PASSWORD}'; \
     GRANT ALL PRIVILEGES ON rpki_db.* TO '${MYSQL_APP_USER}'@'%'; FLUSH PRIVILEGES;" >/dev/null 2>&1 || true
  echo "✓ 数据库 rpki_db 就绪（应用用户 ${MYSQL_APP_USER}）"
}

maybe_deploy_java_chaincode() {
  if [[ "${DEPLOY_JAVA_CC}" != "1" ]]; then
    echo "跳过 Java 链码部署（默认不跑；首次安装或升级合约请: DEPLOY_JAVA_CC=1 ${SCRIPT_DIR}/start-backend-all.sh）"
    return 0
  fi
  if [[ -z "${FABRIC_SAMPLES:-}" ]]; then
    echo "⚠️ 未设置 FABRIC_SAMPLES（且未能从 FABRIC_DIR 自动推导），跳过 rpkiccjava 自动部署。"
    echo "   请 export FABRIC_SAMPLES=/path/to/fabric-samples 或保证 FABRIC_DIR 为 .../test-network"
    return 0
  fi
  local deploy_script="${ROOT_DIR}/fabric-prototype/scripts/deploy-rpkicc-java-lifecycle.sh"
  if [[ ! -f "${deploy_script}" ]]; then
    echo "⚠️ 未找到 ${deploy_script}"
    return 0
  fi
  echo "部署 Java 链码 rpkiccjava（lifecycle）..."
  if FABRIC_SAMPLES="${FABRIC_SAMPLES}" bash "${deploy_script}"; then
    echo "✓ Java 链码部署流程已执行"
  else
    echo "⚠️ Java 链码部署失败（可能已部署或 sequence 需递增），请查看上方日志后手动执行:"
    echo "   FABRIC_SAMPLES=... ${deploy_script}"
  fi
}

start_spring_boot() {
  require_cmd mvn
  require_cmd java
  require_cmd curl

  echo "构建后端 JAR..."
  ( cd "${ROOT_DIR}" && mvn -q -Dmaven.repo.local="${ROOT_DIR}/.m2" -DskipTests package )
  local jar="${ROOT_DIR}/target/conflict-checker-backend-1.0.0.jar"
  if [[ ! -f "${jar}" ]]; then
    echo "❌ 找不到 JAR: ${jar}"
    exit 1
  fi

  if [[ "${RESTART_BACKEND}" != "0" ]]; then
    echo "释放端口 ${BACKEND_PORT} 并用新构建的 JAR 启动后端（默认开启；设 RESTART_BACKEND=0 可跳过释放）..."
    free_backend_port "${BACKEND_PORT}"
  fi

  local port_busy=0
  if command -v ss >/dev/null 2>&1 && ss -ltnp 2>/dev/null | grep -q ":${BACKEND_PORT} "; then
    port_busy=1
  fi

  if [[ "${port_busy}" -eq 1 && "${RESTART_BACKEND}" == "0" ]]; then
    echo "✓ 端口 ${BACKEND_PORT} 已在监听（RESTART_BACKEND=0，跳过后端启动）"
    echo "  若前端 socket hang up / 接口异常，请去掉 RESTART_BACKEND=0 或执行: fuser -k ${BACKEND_PORT}/tcp"
  else
    if [[ "${port_busy}" -eq 1 ]]; then
      echo "⚠️ 端口仍被占用，再次尝试释放..."
      free_backend_port "${BACKEND_PORT}"
    fi
    export SPRING_PROFILES_ACTIVE
    export FABRIC_MODE
    export MYSQL_USER="${MYSQL_APP_USER}"
    export MYSQL_PASSWORD="${MYSQL_APP_PASSWORD}"
    nohup java -jar "${jar}" >> "${BACKEND_LOG}" 2>&1 &
    echo "后端进程已后台启动，日志: ${BACKEND_LOG}"
  fi

  wait_http "${BACKEND_BASE_URL}/" 180
  echo "✓ Spring Boot 就绪: ${BACKEND_BASE_URL}"
}

# --- main ---
echo "============================================================"
echo "后端全栈启动（MySQL + Fabric + DB + 可选 Java CC + Spring Boot）"
echo "项目: ${ROOT_DIR}"
echo "Fabric test-network: ${FABRIC_DIR}"
echo "FABRIC_SAMPLES: ${FABRIC_SAMPLES:-（未设置）}"
echo "RESTART_BACKEND=${RESTART_BACKEND}（默认 1=每次释放 8082 并启动新 JAR） DEPLOY_JAVA_CC=${DEPLOY_JAVA_CC}（默认 0=跳过链码部署；首次请设 1）"
echo "============================================================"

require_cmd docker
require_cmd curl

echo "[1/5] MySQL（Docker）..."
ensure_mysql_container
wait_mysql_ready

echo "[2/5] Hyperledger Fabric..."
bring_up_fabric

echo "[3/5] MySQL 库表与补丁..."
init_database

echo "[4/5] Java 链码（rpkiccjava，与 application-dev.yml 一致）..."
maybe_deploy_java_chaincode

echo "[5/5] Spring Boot..."
start_spring_boot

echo
echo "============================================================"
echo "后端已就绪"
echo "- API:  ${BACKEND_BASE_URL}"
echo "- 文档: ${BACKEND_BASE_URL}/swagger-ui.html"
echo "- 前端: 请另开终端执行 qidongjiaoben/start-frontend.sh"
echo "============================================================"
