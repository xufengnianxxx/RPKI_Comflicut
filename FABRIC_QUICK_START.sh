#!/bin/bash

# ============================================================
# RPKI Fabric 真实集成 - 快速启动清单
# ============================================================

echo "╔════════════════════════════════════════════════════════╗"
echo "║        RPKI Fabric 真实集成快速启动指南                 ║"
echo "╚════════════════════════════════════════════════════════╝"

# 步骤 1: 启动 Fabric 网络
echo ""
echo "【第 1 步】启动 Fabric 网络"
echo "================================"
echo "命令:"
echo "  cd /home/xfn/fabric-samples/test-network"
echo "  ./network.sh up createChannel -ca"
echo "  ./network.sh deployCC -ccn rpkicc -ccp ./chaincode/rpkicc/go -ccl go"
echo ""
echo "✓ 应该看到的消息:"
echo "  - Creating network..."
echo "  - Creating peer0.org1..."
echo "  - Creating orderer..."
echo "  - Channel 'mychannel' created"
echo "  - Chaincode installed and committed"
echo ""

# 步骤 2: 生成配置
echo "【第 2 步】生成 Fabric 配置文件"
echo "================================"
echo "命令:"
echo "  bash /home/xfn/rpki-conflict-checker-backend/deploy-fabric.sh"
echo ""
echo "验证:"
echo "  ls -la /home/xfn/rpki-conflict-checker-backend/"
echo "  - 应该看到 fabric-config.json"
echo "  - 应该看到 FABRIC_INTEGRATION_GUIDE.md"
echo ""

# 步骤 3: 配置应用
echo "【第 3 步】修改应用配置"
echo "================================"
echo "编辑文件: src/main/resources/fabric-integration.yml"
echo ""
echo "修改如下:"
echo "  fabric:"
echo "    enabled: true        ← 改为 true"
echo "    gateway:"
echo "      host: localhost"
echo "      port: 7051"
echo "    user:"
echo "      name: admin"
echo "      msp-id: Org1MSP"
echo ""

# 步骤 4: 启动应用
echo "【第 4 步】启动 RPKI 应用"
echo "================================"
echo "命令:"
echo "  cd /home/xfn/rpki-conflict-checker-backend"
echo "  mvn clean package"
echo "  mvn spring-boot:run"
echo ""
echo "验证日志:"
echo "  - 应该看到 'Application started successfully'"
echo "  - 应该看到 'Fabric Gateway connected'"
echo ""

# 步骤 5: 测试 API
echo "【第 5 步】测试 Fabric 集成"
echo "================================"
echo ""

echo "5.1 检查 Fabric 健康状态"
echo "  curl http://localhost:8081/api/cert/fabric/health"
echo ""

echo "5.2 执行完整工作流 (会向 Fabric 上链)"
echo "  curl -X POST http://localhost:8081/api/cert/auto-process"
echo ""

echo "5.3 查询链上数据"
echo "  curl http://localhost:8081/api/cert/fabric/all"
echo ""

echo "5.4 查询证书历史"
echo "  curl http://localhost:8081/api/cert/fabric/history/cert1"
echo ""

# 核心概念
echo "【核心概念】"
echo "================================"
echo ""
echo "1. 真实集成 vs 模拟模式"
echo "   - fabric.enabled=true  : 连接真实 Fabric 网络"
echo "   - fabric.enabled=false : 使用本地模拟"
echo ""

echo "2. 关键组件"
echo "   - FabricService_Real.java : 真实 Fabric SDK 集成"
echo "   - rpkicc 链码 : Go 语言智能合约"
echo "   - fabric-config.json : 网络配置"
echo ""

echo "3. 工作流程"
echo "   下载证书 → 解析 → 冲突检测 → DB 存储 → Fabric 上链"
echo ""

# 故障排除
echo "【快速故障排除】"
echo "================================"
echo ""

echo "问题 1: Gateway 连接失败"
echo "  原因: Fabric 网络未启动"
echo "  检查: docker ps | grep peer"
echo "  解决: 运行 ./network.sh up createChannel -ca"
echo ""

echo "问题 2: 找不到证书文件"
echo "  原因: 证书路径配置错误"
echo "  检查: ls src/main/resources/fabric-certs/"
echo "  解决: 运行 deploy-fabric.sh 重新生成"
echo ""

echo "问题 3: 链码调用失败"
echo "  原因: 链码未部署或名称错误"
echo "  检查: peer lifecycle chaincode queryinstalled"
echo "  解决: 重新部署 deployCC 命令"
echo ""

# 文档
echo "【详细文档】"
echo "================================"
echo ""
echo "完整指南: FABRIC_REAL_INTEGRATION_GUIDE.md"
echo "Fabric 配置: fabric-integration.yml"
echo "部署脚本: deploy-fabric.sh"
echo ""

# 最后的提示
echo "╔════════════════════════════════════════════════════════╗"
echo "║                    开始部署!                             ║"
echo "║                                                          ║"
echo "║  下一步: ./network.sh up createChannel -ca             ║"
echo "║                                                          ║"
echo "║  祝你集成顺利! 🚀                                        ║"
echo "╚════════════════════════════════════════════════════════╝"
