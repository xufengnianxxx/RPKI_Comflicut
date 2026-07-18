#!/usr/bin/env bash
# 在 fabric-samples test-network 上部署本仓库 Java 链码（Maven prepare + lifecycle）。
# 前置：peer/orderer 已运行，通道 mychannel 已创建。
#
#   export FABRIC_SAMPLES=/path/to/fabric-samples
#   ./fabric-prototype/scripts/deploy-rpkicc-java-lifecycle.sh
#
# 可选：CHANNEL_NAME、CC_NAME（默认 rpkiccjava）、CC_VERSION、CC_SEQUENCE
# CC_SEQUENCE：默认 auto（由 test-network scripts/ccutils.sh resolveSequence 根据已提交定义自动取下一序号）。
# 通道上已是 sequence 2 时若仍用 1 会导致 ApproveChaincodeDefinitionForMyOrg 失败。
# 仅全新通道首次部署可显式设 CC_SEQUENCE=1。
set -uo pipefail

: "${FABRIC_SAMPLES:?Set FABRIC_SAMPLES to your fabric-samples clone root}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CC_HOME="${REPO_ROOT}/fabric-prototype/chaincode-java/rpkicc-contract"
TEST_NETWORK_HOME="${FABRIC_SAMPLES}/test-network"

CHANNEL_NAME="${CHANNEL_NAME:-mychannel}"
CC_NAME="${CC_NAME:-rpkiccjava}"
CC_VERSION="${CC_VERSION:-1.0}"
CC_SEQUENCE="${CC_SEQUENCE:-auto}"
CC_INIT_FCN="${CC_INIT_FCN:-NA}"
DELAY="${DELAY:-3}"
MAX_RETRY="${MAX_RETRY:-5}"
VERBOSE="${VERBOSE:-false}"

INIT_REQUIRED=""
if [ "${CC_INIT_FCN}" != "NA" ]; then
  INIT_REQUIRED="--init-required"
fi

CC_END_POLICY="${CC_END_POLICY:-}"
if [ -n "${CC_END_POLICY}" ]; then
  CC_END_POLICY="--signature-policy ${CC_END_POLICY}"
fi

CC_COLL_CONFIG="${CC_COLL_CONFIG:-}"
if [ -n "${CC_COLL_CONFIG}" ]; then
  CC_COLL_CONFIG="--collections-config ${CC_COLL_CONFIG}"
fi

if [ ! -d "${TEST_NETWORK_HOME}" ]; then
  echo "test-network not found: ${TEST_NETWORK_HOME}" >&2
  exit 1
fi

command -v peer >/dev/null 2>&1 || export PATH="${FABRIC_SAMPLES}/bin:${PATH}"
command -v jq >/dev/null 2>&1 || {
  echo "jq is required (same as fabric-samples test-network)" >&2
  exit 1
}

"${CC_HOME}/prepare-test-network-install.sh" "${CC_NAME}"
CC_STAGE="${CC_HOME}/build/install/${CC_NAME}"

export FABRIC_CFG_PATH="${FABRIC_SAMPLES}/config"
export TEST_NETWORK_HOME
export OVERRIDE_ORG="${OVERRIDE_ORG:-}"
cd "${TEST_NETWORK_HOME}"

# shellcheck source=/dev/null
. scripts/utils.sh
# shellcheck source=/dev/null
. scripts/envVar.sh
# shellcheck source=/dev/null
. scripts/ccutils.sh

println "Packaging Java chaincode from ${CC_STAGE}"
set -x
peer lifecycle chaincode package "${CC_NAME}.tar.gz" --path "${CC_STAGE}" --lang java --label "${CC_NAME}_${CC_VERSION}" >&log.txt
res=$?
{ set +x; } 2>/dev/null
cat log.txt
verifyResult "${res}" "Chaincode packaging failed"

PACKAGE_ID=$(peer lifecycle chaincode calculatepackageid "${CC_NAME}.tar.gz")
successln "PACKAGE_ID=${PACKAGE_ID}"

infoln "Installing chaincode on peer0.org1..."
installChaincode 1
infoln "Installing chaincode on peer0.org2..."
installChaincode 2

resolveSequence

queryInstalled 1

approveForMyOrg 1
checkCommitReadiness 1 "\"Org1MSP\": true" "\"Org2MSP\": false"
checkCommitReadiness 2 "\"Org1MSP\": true" "\"Org2MSP\": false"

approveForMyOrg 2
checkCommitReadiness 1 "\"Org1MSP\": true" "\"Org2MSP\": true"
checkCommitReadiness 2 "\"Org1MSP\": true" "\"Org2MSP\": true"

commitChaincodeDefinition 1 2

queryCommitted 1
queryCommitted 2

if [ "${CC_INIT_FCN}" = "NA" ]; then
  infoln "Chaincode initialization is not required"
else
  chaincodeInvokeInit 1 2
fi

successln "Java chaincode ${CC_NAME} is committed on ${CHANNEL_NAME}. Set Spring fabric.chaincode=${CC_NAME}"
