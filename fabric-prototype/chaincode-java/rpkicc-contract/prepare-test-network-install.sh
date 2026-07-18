#!/usr/bin/env bash
# 用 Maven 生成与 packageCC.sh 中 Java 分支期望一致的目录：
#   build/install/<链码名>/{bin,lib}
# 当本机无法拉取 Gradle 发行包时，可用本脚本 + ../scripts/deploy-rpkicc-java-lifecycle.sh 完成部署。
set -euo pipefail
NAME="${1:-rpkiccjava}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"
mvn -q -DskipTests package
INSTALL="build/install/${NAME}"
rm -rf build/install
mkdir -p "${INSTALL}/lib"
JAR="$(ls target/rpkicc-contract-*.jar | grep -v original- | head -1)"
cp "${JAR}" "${INSTALL}/lib/chaincode.jar"
mkdir -p "${INSTALL}/bin"
cat >"${INSTALL}/bin/${NAME}" <<EOF
#!/bin/sh
APP_HOME="\$(cd "\$(dirname "\$0")/.." && pwd)"
exec java -jar "\$APP_HOME/lib/chaincode.jar" "\$@"
EOF
chmod +x "${INSTALL}/bin/${NAME}"
echo "Prepared ${INSTALL} (Maven fat jar as lib/chaincode.jar)"
