#!/usr/bin/env bash
# 一键拉起：后端全栈 + 可选后台前端（适合「一条命令」；分两个终端则用 start-backend-all + start-frontend）
#
#   ./start-stack.sh                    # 后端 + 前端后台（日志 logs/frontend.out）
#   START_FRONTEND=0 ./start-stack.sh   # 仅后端
#   DEPLOY_JAVA_CC=1 ./start-stack.sh   # 同时部署/升级链码（首次或改合约）
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

export RESTART_BACKEND="${RESTART_BACKEND:-1}"
export DEPLOY_JAVA_CC="${DEPLOY_JAVA_CC:-0}"

echo "============================================================"
echo "一键启动栈：start-backend-all.sh → 可选 Vite 后台"
echo "ROOT_DIR=${ROOT_DIR}"
echo "============================================================"

bash "${SCRIPT_DIR}/start-backend-all.sh"

START_FRONTEND="${START_FRONTEND:-1}"
if [[ "${START_FRONTEND}" == "1" ]]; then
  export START_FRONTEND_BG=1
  bash "${SCRIPT_DIR}/start-frontend.sh"
  echo "前端: http://127.0.0.1:${FRONTEND_PORT:-5173}/  日志: ${ROOT_DIR}/logs/frontend.out"
else
  echo "已跳过前端（START_FRONTEND=0）；手动前台: ${SCRIPT_DIR}/start-frontend.sh"
fi

echo "============================================================"
echo "完成。后端 API: http://localhost:8082/api/"
echo "============================================================"
