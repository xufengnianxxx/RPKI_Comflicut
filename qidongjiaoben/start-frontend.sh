#!/usr/bin/env bash
# 启动 Vue3 + Vite 前端（frontend-vue），开发模式，带 /api 代理到 Spring Boot
#
# 环境变量（可选）：
#   FRONTEND_PORT    默认 5173（通过 npm run dev -- --port 传递）
#   HOST             默认 0.0.0.0，便于局域网访问
#   START_FRONTEND_BG 设为 1 时后台运行（日志 frontend.out，供 start-stack.sh / 开机自启）
#
# 依赖：Node.js 18+、npm；后端需已启动（默认代理到 http://127.0.0.1:8082）
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
FRONTEND_DIR="${ROOT_DIR}/frontend-vue"

FRONTEND_PORT="${FRONTEND_PORT:-5173}"
HOST="${HOST:-0.0.0.0}"
START_FRONTEND_BG="${START_FRONTEND_BG:-0}"
FRONTEND_LOG="${FRONTEND_LOG:-${ROOT_DIR}/logs/frontend.out}"

if [[ ! -d "${FRONTEND_DIR}" ]]; then
  echo "❌ 未找到前端目录: ${FRONTEND_DIR}"
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "❌ 未找到 npm，请先安装 Node.js"
  exit 1
fi

echo "============================================================"
echo "启动前端（Vite + Vue3 + Element Plus）"
echo "目录: ${FRONTEND_DIR}"
echo "访问: http://127.0.0.1:${FRONTEND_PORT}/"
echo "代理: /api → http://127.0.0.1:8082（见 frontend-vue/vite.config.ts）"
echo "============================================================"
echo "提示：须先启动后端（同目录 ./start-backend-all.sh），终端出现「✓ Spring Boot 就绪」后再开本站。"
echo "      若报 ECONNREFUSED 8082，说明后端未起来或刚被中断，请先修好后端再刷新页面。"
echo "============================================================"

cd "${FRONTEND_DIR}"
if [[ ! -d node_modules ]]; then
  echo "首次运行，执行 npm install..."
  npm install
fi

mkdir -p "${ROOT_DIR}/logs"

if [[ "${START_FRONTEND_BG}" == "1" ]]; then
  echo "后台启动 Vite，日志: ${FRONTEND_LOG}"
  nohup npm run dev -- --host "${HOST}" --port "${FRONTEND_PORT}" >> "${FRONTEND_LOG}" 2>&1 &
  echo $! > "${ROOT_DIR}/logs/frontend.pid"
  echo "PID $(cat "${ROOT_DIR}/logs/frontend.pid") （结束: kill \$(cat ${ROOT_DIR}/logs/frontend.pid)）"
  exit 0
fi

# 前台运行，便于 Ctrl+C 结束
exec npm run dev -- --host "${HOST}" --port "${FRONTEND_PORT}"
