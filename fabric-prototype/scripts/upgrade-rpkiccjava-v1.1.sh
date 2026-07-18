#!/usr/bin/env bash
# 链码 3.2.2 确定性修复后升级 test-network 已部署的 rpkiccjava（新 package label + 提高 sequence）。
# 用法：
#   export FABRIC_SAMPLES=/path/to/fabric-samples
#   ./fabric-prototype/scripts/upgrade-rpkiccjava-v1.1.sh
set -euo pipefail
export CC_VERSION="${CC_VERSION:-1.1}"
export CC_SEQUENCE="${CC_SEQUENCE:-2}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/deploy-rpkicc-java-lifecycle.sh"
