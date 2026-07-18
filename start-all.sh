#!/usr/bin/env bash
set -euo pipefail

# 一键启动脚本：
# 1) 启动 MySQL + Fabric 容器
# 2) (可选) 部署/升级链码 rpkicc
# 3) 初始化数据库（幂等）
# 4) 启动 Spring Boot 后端
#
# 默认不重复部署链码，避免 sequence 冲突。
# 如需部署链码请显式开启：
#   DEPLOY_CC=1 ./start-all.sh

ROOT_DIR="/home/xfn/rpki-conflict-checker-backend"
FABRIC_DIR="/home/xfn/fabric-samples/test-network"

MYSQL_CONTAINER="rpki-mysql"
ORDERER_CONTAINER="orderer.example.com"
PEER1_CONTAINER="peer0.org1.example.com"
PEER2_CONTAINER="peer0.org2.example.com"

wait_mysql_ready() {
  local max="${1:-90}"
  local i=0
  echo "等待 MySQL 就绪（最多 ${max}s）..."
  while ! docker exec "${MYSQL_CONTAINER}" sh -lc \
    'mysql -h 127.0.0.1 -uroot -proot -Nse "SELECT 1"' >/dev/null 2>&1; do
    i=$((i + 1))
    if (( i >= max )); then
      echo "错误: MySQL 在 ${max}s 内未就绪。请检查: docker logs ${MYSQL_CONTAINER}"
      exit 1
    fi
    sleep 1
  done
}

echo "[1/5] 启动 MySQL + Fabric 核心容器..."
if ! docker container inspect "${MYSQL_CONTAINER}" &>/dev/null; then
  echo "错误: 未找到 Docker 容器 ${MYSQL_CONTAINER}。"
  echo "请先创建，例如:"
  echo "  docker run -d --name rpki-mysql -e MYSQL_ROOT_PASSWORD=root -p 3306:3306 mysql:8.0"
  exit 1
fi
docker start "${MYSQL_CONTAINER}" >/dev/null
for c in "${ORDERER_CONTAINER}" "${PEER1_CONTAINER}" "${PEER2_CONTAINER}"; do
  docker start "${c}" >/dev/null 2>&1 || true
done
wait_mysql_ready

echo "[2/5] 链码步骤..."
if [[ "${DEPLOY_CC:-0}" == "1" ]]; then
  echo "检测到 DEPLOY_CC=1，开始部署/升级链码 rpkicc..."
  cd "${FABRIC_DIR}"
  ./network.sh deployCC -ccn rpkicc -ccp ./chaincode/rpkicc/go -ccl go
else
  echo "跳过链码部署（默认行为）。如需部署请执行: DEPLOY_CC=1 ./start-all.sh"
fi

echo "[3/5] 初始化数据库（幂等）..."
cd "${ROOT_DIR}"
docker exec "${MYSQL_CONTAINER}" sh -lc \
  "mysql -h 127.0.0.1 -uroot -proot -e 'CREATE DATABASE IF NOT EXISTS rpki_db;'"
docker exec -i "${MYSQL_CONTAINER}" sh -lc \
  "mysql -h 127.0.0.1 -uroot -proot rpki_db" < "${ROOT_DIR}/sql-init.sql"

echo "[4/5] 编译项目..."
mvn -q -Dmaven.repo.local=.m2 clean compile

echo "[5/5] 启动后端（前台运行，Ctrl+C 停止）..."
exec mvn -Dmaven.repo.local=.m2 spring-boot:run
