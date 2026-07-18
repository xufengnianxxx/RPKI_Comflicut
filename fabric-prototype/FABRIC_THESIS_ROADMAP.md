# 毕业设计：Fabric 层开发路线图与操作手册（RPKI 冲突校验原型）

本文档与仓库内代码对齐：

- **链码（Java Contract API）**：`fabric-prototype/chaincode-java/rpkicc-contract/`
- **Spring 集成门面**：`src/main/java/.../service/fabric/FabricBlockchainFacade.java`
- **REST 查询示例**：`src/main/java/.../controller/FabricChainController.java`（前缀 `/api/fabric/chain`，受 `server.servlet.context-path` 影响）

---

## 一、分步计划与检查点（4 周 × 5 天）

### 第 1 周：测试网络 + 最小链码

| 天 | 任务 | 完成标准（检查点） |
|----|------|-------------------|
| 1–2 | 安装 Docker、拉取 `fabric-samples`，检出与学校环境一致的 LTS（建议 **2.5.x**） | `docker ps` 无异常，`./network.sh` 可执行 |
| 3 | 启动 `test-network`、创建 channel、加入 Org1/Org2 | `peer channel list` 可见 `mychannel` |
| 4 | 部署 **Go 版** 既有链码或空链码验证通路 | `invoke/query` 成功 |
| 5 | 将本仓库 **Java 链码** 打包为 jar，按官方 Java 链码方式 `deployCC -ccl java`（或 Docker 外置链码） | `queryConflicts` 返回 `[]` |

### 第 2 周：链上逻辑 + Spring 集成

| 天 | 任务 | 检查点 |
|----|------|--------|
| 1–2 | 实现/联调 `storeCertificateBatch`、`detectConflictOnChain` | 世界状态出现 `CERT\|*`、`CONFLICT\|*` |
| 3 | 联调 `queryCertificate`、`getAuditHistory` | REST `/api/fabric/chain/...` 在 `FABRIC_MODE=REAL` 下可通 |
| 4 | 背书策略：默认 `AND('Org1MSP.member','Org2MSP.member')` 或论文要求的 2-of-2 | 单 Org 提交失败、双 Org 成功 |
| 5 | 将 `RpkiUnifiedConflictDetectionService` 结果经 `FabricService` / `FabricBlockchainFacade` 写入链与 `fabric_tx_record` | DB `is_sent_to_fabric`、`fabric_tx_id` 有值 |

### 第 3 周：端到端场景测试

- **无冲突**：两证前缀不重叠 → 链上 `NONE`，无 `CONFLICT|` 键。
- **前缀重叠/包含**：链下全量检测 + 链上 `detectConflictOnChain` 轻量校验一致（抽样比对）。
- **AS / 继承链**：链下权威判定，链上存 `ConflictRecordAsset`（摘要字段）。
- **撤销残留**：链下标记 + 链上审计历史可追溯。

### 第 4 周：性能与安全

- 压测：`storeCertificateBatch` 批量大小、TPS、peer CPU/内存；记录 **P50/P95 延迟**。
- 私钥：Wallet 目录权限、`connection-profile` 不入库；生产用 HSM/Vault 方案写入论文「展望」。
- 链码升级：`deployCC` 递增 sequence，双 Org approve + commit。

---

## 二、测试网络：可重复执行命令（fabric-samples/test-network）

以下路径以 `FABRIC_SAMPLES=/path/to/fabric-samples` 为例。

```bash
cd "$FABRIC_SAMPLES/test-network"
./network.sh down
./network.sh up createChannel -ca
# 默认：2 Org × 2 Peer + Orderer（Raft），通道名常为 mychannel
```

**环境变量（推荐写进 `~/.profile` 或论文附录）：**

```bash
export FABRIC_CFG_PATH="${FABRIC_SAMPLES}/config"
export PATH="${FABRIC_SAMPLES}/bin:$PATH"
```

**检查通道与节点：**

```bash
docker ps --format '{{.Names}}' | grep -E 'peer|orderer|ca'
```

**部署 Java 链码（名称需与 Spring `fabric.chaincode` 一致，例如 `rpkiccjava`）：**

具体 `-ccp` 目录结构须符合 `fabric-samples` 对 **Java chaincode** 的要求（通常内含 `build.gradle` 或可复制本仓库 `rpkicc-contract` 到模板项目）。示例命令形态：

```bash
./network.sh deployCC -ccn rpkiccjava -ccp ../path-to-chaincode-dir -ccl java -c mychannel
```

> 若学校脚本固定使用 Go 链码 `rpkicc`，可并行保留 **Go 存证 + Java 原型** 两条线，论文中说明「Java 链码为规则固化原型」。

**关闭网络：**

```bash
./network.sh down
```

**Docker Compose**：`test-network` 内部已编排 `compose`；一般无需手写，除非自定义端口/卷，再参考 `compose/` 下 YAML 扩展。

---

## 三、链码设计摘要（已实现骨架）

| 交易 | 类型 | 说明 |
|------|------|------|
| `storeCertificateBatch` | Submit | 批量写入脱敏 `CertificateAsset`（键 `CERT\|<hash>`） |
| `detectConflictOnChain` | Submit | 读两证 IPv4，做确定性包含/重叠，命中则写 `CONFLICT\|*` |
| `queryCertificate` | Evaluate | 按 `cert_hash` 读状态 |
| `queryConflicts` | Evaluate | 范围扫 `CONFLICT\|` 前缀（演示用，生产需分页） |
| `getAuditHistory` | Evaluate | `getHistoryForKey` 审计 |

**隐私**：不上链 `raw_cert_data`、完整 DN；仅 `cert_hash`、前缀列表、AS 列表、`parentRef`、`revoked` 等。

**与链下分工**：复杂前缀树、RIR 策略、继承链推理在 **Spring**；链上 **轻量判定 + 不可篡改存证**。

---

## 四、Spring Boot 与现有服务对接

1. **配置**（`application-dev.yml` 已有 Fabric 段）：  
   - `fabric.mode=REAL`  
   - `fabric.gateway.connection-profile` → `connection-org1.json`  
   - `fabric.chaincode` → 与部署名一致（部署 Java 链码后改为 `rpkiccjava` 等）  
   - `fabric.wallet.path`、`fabric.identity.*`

2. **门面调用**：注入 `FabricBlockchainFacade`  
   - 批量上链：`storeCertificateBatch(redactedList)`  
   - 轻量检测：`detectConflictOnChain(ha, hb)`  
   - 查询：`queryCertificate` / `queryConflicts` / `getAuditHistory`

3. **与冲突检测衔接（建议在 `ConflictDetectionService` 或独立 Listener 中）**：  
   - 持久化 `conflict_record` 后，构造 `CertificateRedactedPayload` 列表（或仅冲突对）调用 `storeCertificateBatch`；  
   - 再 `detectConflictOnChain` 或对每条冲突写自定义存证交易；  
   - 成功则更新 `RpkiCert.fabric_tx_id` / `is_sent_to_fabric`（需扩展 Mapper 按 id 更新）。

4. **保留的 `FabricService.submitConflictToChain`**：仍调用 Go 链码 `DetectAndRecord`；**迁移期**可同时存在，论文中写「双链码演进」或统一为 Java。

---

## 五、REST（Vue 前端可对接）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/fabric/chain/certificate/{certHash}` | 链上脱敏证书记录 |
| GET | `/api/fabric/chain/conflicts` | 链上冲突列表 JSON |
| GET | `/api/fabric/chain/audit/{certHash}` | 变更历史 |

POST「提交校验任务」建议在通过认证后调用现有 `POST /api/cert/detect-conflicts`，再在服务端异步上链（避免浏览器直连 Fabric）。

---

## 六、最佳实践与注意事项

1. **链上只做轻量运算**：避免在链码中做 O(n²) 全库扫描；本骨架的 `queryConflicts` 全表扫仅用于演示。  
2. **背书策略**：多组织背书实现「多方见证」；与论文「去中心化」叙述一致。  
3. **确定性**：链码禁用随机数、系统时间仅用于非共识字段；本骨架在存证里使用 `System.currentTimeMillis()`，正式论文可改为链下传入统一时间戳。  
4. **错误处理**：Gateway 捕获 `ContractException`，映射为 HTTP 502/400 + 可读信息。  
5. **版本升级**：`peer lifecycle chaincode approveformyorg` / `commit` 递增 **sequence**。  
6. **Gateway API 演进**：当前工程为 **fabric-gateway-java 1.4.1**（与旧 gRPC/Netty 锁定）；论文可单列一节说明迁移至 **Fabric Gateway v2** 客户端的路径。

---

## 七、论文可引用的三层架构表述（摘要）

- **应用交互层**：Spring Boot + MySQL + REST +（可选）Vue。  
- **链下计算层**：BouncyCastle 解析、RFC 3779、`RpkiUnifiedConflictDetectionService`。  
- **区块链层**：Hyperledger Fabric Orderer + Multi-Org Peer；**智能合约层** Java Contract API 固化存证与轻量规则，实现可审计、不可篡改与隐私脱敏上链。

---

*文档版本与仓库链码 `RULE_VERSION` / `pom` 中 `fabric-chaincode-shim` 版本请保持同步记录于论文「实验环境」章节。*
