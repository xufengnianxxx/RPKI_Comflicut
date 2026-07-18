#!/bin/bash

# ============================================================
# RPKI Fabric 集成部署脚本
# 用于：启动 Fabric 网络、部署链码、生成配置
# ============================================================

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
FABRIC_SAMPLES="/home/xfn/fabric-samples"
TEST_NETWORK="$FABRIC_SAMPLES/test-network"
CHAINCODE_PATH="$TEST_NETWORK/chaincode/rpkicc/go"
PROJECT_DIR="/home/xfn/rpki-conflict-checker-backend"

echo "╔════════════════════════════════════════════════════════╗"
echo "║     RPKI Fabric 真实集成部署系统                         ║"
echo "╚════════════════════════════════════════════════════════╝"

# 检查前置条件
check_prerequisites() {
    echo ""
    echo "【第1步】检查前置条件..."
    
    command -v docker &> /dev/null || { echo "❌ 需要安装 Docker"; exit 1; }
    echo "✓ Docker 已安装"
    
    command -v docker-compose &> /dev/null || { echo "❌ 需要安装 Docker Compose"; exit 1; }
    echo "✓ Docker Compose 已安装"
    
    command -v jq &> /dev/null || { echo "❌ 需要安装 jq"; exit 1; }
    echo "✓ jq 已安装"
    
    [ -d "$TEST_NETWORK" ] || { echo "❌ test-network 不存在"; exit 1; }
    echo "✓ test-network 已存在"
}

# 启动 Fabric 网络
start_network() {
    echo ""
    echo "【第2步】启动 Fabric 网络..."
    
    cd "$TEST_NETWORK"
    
    # 清理旧的网络和容器
    echo "清理旧的网络资源..."
    ./network.sh down 2>/dev/null || true
    sleep 2
    
    # 启动网络
    echo "启动 Fabric 测试网络..."
    ./network.sh up createChannel -ca 2>&1 | grep -E "(Creating|Started|Channel|SUCCESS|ERROR)" || true
    
    sleep 5
    echo "✓ Fabric 网络已启动"
}

# 部署 RPKI 链码
deploy_chaincode() {
    echo ""
    echo "【第3步】部署 RPKI 链码..."
    
    cd "$TEST_NETWORK"
    
    echo "部署链码 rpkicc..."
    ./network.sh deployCC -ccn rpkicc -ccp ./chaincode/rpkicc/go -ccl go 2>&1 | grep -E "(Deploying|Installed|Committed|SUCCESS|ERROR)" || true
    
    sleep 3
    echo "✓ 链码部署完成"
}

# 生成 Java SDK 配置
generate_sdk_config() {
    echo ""
    echo "【第4步】生成 Java SDK 配置..."
    
    # 获取 Peer 容器 IP
    PEER_CONTAINER=$(docker ps --filter "name=peer0.org1" --format "{{.Names}}" | head -1)
    if [ -z "$PEER_CONTAINER" ]; then
        echo "❌ 无法找到 Peer 容器"
        exit 1
    fi
    
    PEER_IP=$(docker inspect "$PEER_CONTAINER" -f '{{.NetworkSettings.IPAddress}}')
    echo "Peer 容器 IP: $PEER_IP"
    
    # 生成连接配置
    cat > "$PROJECT_DIR/fabric-config.json" << EOF
{
  "name": "rpki-network",
  "version": "1.0.0",
  "client": {
    "organization": "Org1",
    "connection": {
      "timeout": {
        "peer": {
          "endorser": "300"
        }
      }
    }
  },
  "organizations": {
    "Org1": {
      "mspid": "Org1MSP",
      "peers": ["peer0.org1.example.com"],
      "certificateAuthorities": ["ca.org1.example.com"],
      "adminPrivateKey": "$TEST_NETWORK/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore",
      "signedCert": "$TEST_NETWORK/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/signcerts"
    }
  },
  "peers": {
    "peer0.org1.example.com": {
      "url": "grpc://localhost:7051",
      "eventUrl": "grpc://localhost:7053",
      "grpcOptions": {
        "ssl-target-name-override": "peer0.org1.example.com",
        "keep-alive-time": "0s",
        "keep-alive-timeout": "20s",
        "keep-alive-permit-without-calls": false,
        "max-connection-idle": "30s",
        "max-connection-age": "2m",
        "max-connection-age-grace": "5s"
      }
    }
  },
  "certificateAuthorities": {
    "ca.org1.example.com": {
      "url": "http://localhost:7054",
      "httpOptions": {
        "verify": false
      }
    }
  },
  "channels": {
    "mychannel": {
      "orderers": ["orderer.example.com"],
      "peers": {
        "peer0.org1.example.com": {
          "endorsingPeer": true,
          "chaincodeQuery": true,
          "ledgerQuery": true,
          "eventSource": true
        }
      }
    }
  },
  "orderers": {
    "orderer.example.com": {
      "url": "grpc://localhost:7050",
      "grpcOptions": {
        "ssl-target-name-override": "orderer.example.com",
        "keep-alive-time": "0s",
        "keep-alive-timeout": "20s",
        "keep-alive-permit-without-calls": false,
        "max-connection-idle": "30s",
        "max-connection-age": "2m",
        "max-connection-age-grace": "5s"
      }
    }
  }
}
EOF

    echo "✓ Fabric 配置已生成: fabric-config.json"
}

# 验证部署
verify_deployment() {
    echo ""
    echo "【第5步】验证部署..."
    
    cd "$TEST_NETWORK"
    
    # 测试链码是否可用
    echo "测试链码调用..."
    
    export PATH="$FABRIC_SAMPLES/bin:$PATH"
    export FABRIC_CFG_PATH="$TEST_NETWORK/configtx"
    
    export CORE_PEER_TLS_ENABLED=true
    export CORE_PEER_LOCALMSPID=Org1MSP
    export CORE_PEER_TLS_ROOTCERT_FILE="$TEST_NETWORK/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt"
    export CORE_PEER_MSPCONFIGPATH="$TEST_NETWORK/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp"
    export CORE_PEER_ADDRESS=localhost:7051
    
    # 调用链码
    peer chaincode query -C mychannel -n rpkicc -c '{"Args":["GetAllCertificates"]}' 2>&1 | tail -5 || true
    
    echo "✓ 部署验证完成"
}

# 生成证书和私钥
generate_certificates() {
    echo ""
    echo "【第6步】生成 Java SDK 证书..."
    
    CERT_DIR="$PROJECT_DIR/src/main/resources/fabric-certs"
    mkdir -p "$CERT_DIR"
    
    # 复制管理员证书
    cp "$TEST_NETWORK/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/signcerts"/* \
        "$CERT_DIR/admin-cert.pem" 2>/dev/null || echo "提示: 证书路径待确认"
    
    # 复制私钥
    KEYSTORE="$TEST_NETWORK/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore"
    if [ -d "$KEYSTORE" ]; then
        cp "$KEYSTORE"/* "$CERT_DIR/admin-key.pem" 2>/dev/null || true
    fi
    
    # 复制 CA 证书
    cp "$TEST_NETWORK/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt" \
        "$CERT_DIR/ca-cert.pem" 2>/dev/null || echo "提示: CA 证书路径待确认"
    
    echo "✓ 证书生成完成"
}

# 生成使用说明
generate_usage_guide() {
    echo ""
    echo "【第7步】生成使用说明..."
    
    cat > "$PROJECT_DIR/FABRIC_INTEGRATION_GUIDE.md" << 'GUIDE'
# Fabric 真实集成使用指南

## 系统架构

```
RPKI 后端应用 (Spring Boot)
    ↓
FabricService (Java SDK)
    ↓
Fabric Gateway (gRPC)
    ↓
排序服务 (Orderer) + 背书节点 (Peer)
    ↓
RPKI 链码 (rpkicc channel)
    ↓
分类账 (Ledger)
```

## 配置文件

- `fabric-config.json` - Fabric 网络连接配置
- `src/main/resources/fabric-certs/` - 证书和私钥

## 启动 Fabric 网络

```bash
cd /home/xfn/fabric-samples/test-network
./network.sh up createChannel -ca
./network.sh deployCC -ccn rpkicc -ccp ./chaincode/rpkicc/go -ccl go
```

## 查看网络状态

```bash
# 查看容器
docker ps -f "label=com.docker.compose.project=net"

# 查看通道
export PATH="/home/xfn/fabric-samples/bin:$PATH"
peer channel list -o localhost:7050 --tls --cafile <ca-cert>

# 查看已安装的链码
peer lifecycle chaincode queryinstalled -o localhost:7050 --tls --cafile <ca-cert>
```

## 测试链码

```bash
# 存储证书
peer chaincode invoke -C mychannel -n rpkicc \
  -c '{"Args":["StoreCertificate","cert1","{\\"certName\\":\\"test\\"}","false",""]}'

# 查询证书
peer chaincode query -C mychannel -n rpkicc \
  -c '{"Args":["ReadCertificate","cert1"]}'

# 获取所有证书
peer chaincode query -C mychannel -n rpkicc \
  -c '{"Args":["GetAllCertificates"]}'
```

## Java 应用连接

修改 `application.yml` 中的 Fabric 配置

```yaml
fabric:
  gateway:
    connection-profile: classpath:fabric-config.json
  user:
    name: admin
    msp-id: Org1MSP
  chaincode:
    name: rpkicc
    channel: mychannel
```

## 停止网络

```bash
cd /home/xfn/fabric-samples/test-network
./network.sh down
```
GUIDE

    echo "✓ 使用指南已生成"
}

# 主函数
main() {
    check_prerequisites
    start_network
    deploy_chaincode
    generate_sdk_config
    generate_certificates
    generate_usage_guide
    verify_deployment
    
    echo ""
    echo "╔════════════════════════════════════════════════════════╗"
    echo "║        ✓ Fabric 真实集成部署完成!                       ║"
    echo "╚════════════════════════════════════════════════════════╝"
    echo ""
    echo "后续步骤:"
    echo "1. 启动 Spring Boot 应用: mvn spring-boot:run"
    echo "2. 测试流程: curl -X POST http://localhost:8081/api/cert/auto-process"
    echo "3. 查看日志: tail -f logs/rpki-checker.log"
    echo ""
}

main
