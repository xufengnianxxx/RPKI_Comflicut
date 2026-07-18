#!/usr/bin/env bash
set -euo pipefail

# 演示流程脚本（默认使用 conflictId=1）
# 用法：
#   ./demo-flow.sh
#   CONFLICT_ID=2 ./demo-flow.sh

BASE_URL="${BASE_URL:-http://localhost:8082/api}"
CONFLICT_ID="${CONFLICT_ID:-1}"

print_step() {
  echo
  echo "============================================================"
  echo "$1"
  echo "============================================================"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd python3

print_step "[1/6] 健康检查"
HOME_RESP="$(curl -s "${BASE_URL}/")"
echo "${HOME_RESP}"

print_step "[2/6] 下载并解析证书"
DOWNLOAD_RESP="$(curl -s -X POST "${BASE_URL}/cert/download-and-parse")"
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${DOWNLOAD_RESP}" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except json.JSONDecodeError:
    print(raw)
    sys.exit(0)
print("--- 接口 success / code ---")
print("success:", d.get("success"), " code:", d.get("code"))
print()
print("--- 建议先读：简明摘要（中文）---")
data = d.get("data") or {}
print(data.get("readableSummaryText") or "(无)")
print()
print("--- 常用数字 ---")
for k in ("runMode", "savedCount", "certCandidates", "parseFailed"):
    if k in data:
        print(" ", k + ":", data[k])
print()
print("--- 完整 JSON（排版）---")
print(json.dumps(d, ensure_ascii=False, indent=2))
'
else
  echo "${DOWNLOAD_RESP}"
fi

print_step "[3/6] 触发冲突检测"
DETECT_RESP="$(curl -s -X POST "${BASE_URL}/cert/detect-conflicts" -H "Content-Type: application/json" -d '{}')"
echo "${DETECT_RESP}"

print_step "[4/6] 查询冲突列表"
CONFLICT_LIST_RESP="$(curl -s "${BASE_URL}/cert/conflicts?page=1&size=10")"
echo "${CONFLICT_LIST_RESP}"

print_step "[5/6] 推送冲突上链 (conflictId=${CONFLICT_ID})"
PUSH_RESP="$(curl -s -X POST "${BASE_URL}/cert/push-to-fabric?conflictId=${CONFLICT_ID}")"
echo "${PUSH_RESP}"

print_step "[6/6] 查询链上冲突记录（仅显示有效记录）"
export PATH=/home/xfn/fabric-samples/bin:$PATH
export FABRIC_CFG_PATH=/home/xfn/fabric-samples/config
export CORE_PEER_TLS_ENABLED=true
export CORE_PEER_LOCALMSPID=Org1MSP
export CORE_PEER_TLS_ROOTCERT_FILE=/home/xfn/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt
export CORE_PEER_MSPCONFIGPATH=/home/xfn/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
export CORE_PEER_ADDRESS=localhost:7051

RAW_CHAIN_RESP="$(peer chaincode query -C mychannel -n rpkicc -c '{"Args":["GetAllConflicts"]}')"
echo "${RAW_CHAIN_RESP}" | python3 -c '
import json
import sys

raw = sys.stdin.read().strip()
if not raw:
    print("[]")
    sys.exit(0)

data = json.loads(raw)
filtered = [
    item for item in data
    if item.get("conflictType") or item.get("txId") or item.get("details")
]
print(json.dumps(filtered, ensure_ascii=False))
'

echo
echo "演示流程执行完成。"
