# RPKI 证书资源冲突检测后端（小白版完整说明）

这是一个用于毕业设计演示的后端系统，目标是把「RPKI 证书资源冲突检测」做成完整闭环：

1. 下载并解析 RPKI 证书数据  
2. 检测冲突（IP/AS/继承链）  
3. 保存到 MySQL  
4. 可选推送到 Hyperledger Fabric 上链存证  

技术栈：`Java 17` + `Spring Boot 3.3` + `MyBatis-Plus` + `MySQL` + `BouncyCastle` + `Fabric Gateway`

---

## 1. 启动前必须准备的服务

你至少要启动下面这些服务：

### 1.1 必需服务（不跑这些，项目无法完整使用）

- `MySQL`：保存证书、冲突记录、上链交易记录
- `后端服务（本项目）`：提供 API

### 1.2 上链演示时必需服务（REAL 模式）

- `Hyperledger Fabric test-network`
  - orderer
  - peer0.org1
  - peer0.org2
  - channel: `mychannel`
  - chaincode: `rpkicc`

### 1.3 本机运行工具

- `JDK 17`
- `Maven 3.8+`
- `Docker + docker compose`（Fabric 和 MySQL 通常用它启动）

---

## 2. 一次性初始化

### 2.1 初始化数据库表

在项目根目录执行：

```bash
mysql -uroot -p < sql-init.sql
```

### 2.2 配置数据库账号（推荐环境变量）

```bash
export MYSQL_USER=rpki
export MYSQL_PASSWORD=rpki123
```

如果你改了用户名密码，记得和 `src/main/resources/application-dev.yml` 保持一致。

### 2.3 （仅 REAL 上链）检查 Fabric 关键路径

`application-dev.yml` 里这些路径必须真实存在：

- `fabric.gateway.connection-profile`
- `fabric.wallet.path`
- `fabric.identity.cert-path`
- `fabric.identity.key-path`

---

## 3. 启动步骤（最稳妥）

### 一条命令启动（推荐答辩现场）

在项目根目录执行：

```bash
./start-all.sh
```

脚本会自动完成：
- 启动 MySQL + Fabric 容器
- 部署/升级 `rpkicc` 链码
- 初始化数据库表
- 编译并启动 Spring Boot 后端

启动后可在另一个终端执行接口调用进行演示。

## 3.1 启动 MySQL

确保 `127.0.0.1:3306` 可连接，且 `rpki_db` 已创建并导入表结构。

## 3.2 启动 Fabric（仅 REAL 模式）

在 `fabric-samples/test-network` 目录执行：

```bash
./network.sh up createChannel
./network.sh deployCC -ccn rpkicc -ccp ./chaincode/rpkicc/go -ccl go
```

## 3.3 启动本项目

在本项目根目录执行：

```bash
mvn spring-boot:run
```

启动成功后访问：

- 首页：`http://localhost:8082/api/`
- Swagger：`http://localhost:8082/api/swagger-ui.html`

首页返回：

`RPKI Conflict Detection System v1.0 - Ready`

---

## 4. 项目到底做了什么（业务视角）

你可以把它理解成一个四段流水线：

1. **下载和解析证书**：从 RIPE/APNIC/ARIN 拉取 RPKI 仓库，提取证书并解析关键字段  
2. **冲突检测**：根据规则判断证书间是否冲突  
3. **结果落库**：把证书、冲突、交易记录写入 MySQL  
4. **冲突上链**：把冲突记录提交到 Fabric 链码 `DetectAndRecord`

---

## 5. 核心 API（演示最常用）

- `POST /api/cert/download-and-parse`  
  下载并解析 RPKI 仓库，写入证书表

- `POST /api/cert/detect-conflicts`  
  触发冲突检测。可传 `{"rir":"APNIC"}`，不传则全量

- `GET /api/cert/conflicts?page=1&size=10`  
  分页查冲突记录

- `GET /api/cert/{id}/detail`  
  查证书详情和关联冲突（证书不存在也会返回空结果，不报错）

- `POST /api/cert/push-to-fabric?conflictId=1`  
  把指定冲突推送到 Fabric，返回链上记录 ID

---

## 6. 主要代码在哪（按模块）

### 6.1 Controller（接口层）

- `src/main/java/com/rpki/conflictchecker/controller/CertController.java`
  - `downloadAndParse()`：下载+解析+入库
  - `detectConflicts()`：触发冲突检测
  - `getConflictCerts()`：分页冲突查询
  - `detail()`：证书详情
  - `pushToFabric()`：冲突上链

- `src/main/java/com/rpki/conflictchecker/controller/GlobalExceptionHandler.java`
  - 全局异常拦截，统一返回 `Result<T>`

### 6.2 Service（核心业务层）

- `CertificateDownloadService`
  - 下载 RIR 数据源
  - 自动解压和文件遍历
  - 定时同步（`@Scheduled`）

- `CertificateParseService`
  - 解析 X.509 证书
  - 提取基础字段（序列号、颁发者、有效期等）
  - 提取 RFC3779 相关扩展信息并转 DTO/Entity

- `ConflictDetectionService`
  - 核心冲突规则计算（IP/AS/继承链）
  - 输出 `ConflictResult`
  - 保存 `ConflictRecord`

- `StorageService`
  - MyBatis-Plus 数据访问封装
  - 证书去重写入（按 `certHash`）
  - 冲突分页查询、详情查询、交易记录存储

- `FabricService`
  - `MOCK/REAL` 双模式
  - REAL 模式通过 Fabric Gateway 调用链码 `DetectAndRecord`
  - 记录交易结果到 `fabric_tx_record`

### 6.3 链码（Fabric）

- `fabric-samples/test-network/chaincode/rpkicc/go/chaincode/smartcontract.go`
  - `DetectAndRecord(payload)`：冲突上链入口
  - `ReadConflict(id)`：查询单条冲突
  - `GetAllConflicts()`：查询全部冲突

---

## 7. 主要数据表

- `rpki_cert`：证书数据
- `conflict_record`：冲突检测结果
- `fabric_tx_record`：上链交易结果

表结构见：`sql-init.sql`

---

## 8. 代码运行流程（一步一步）

下面是最典型的一次完整执行链路：

1. 调用 `POST /api/cert/download-and-parse`
   - `CertController.downloadAndParse()`
   - `CertificateDownloadService.downloadAndExtractAllRirs()`
   - `CertificateParseService.parseCertificateToDto()`
   - `StorageService.saveOrUpdateCertificatesBatch()`

2. 调用 `POST /api/cert/detect-conflicts`
   - `CertController.detectConflicts()`
   - `ConflictDetectionService.detectAndPersistConflicts()`
   - 生成冲突后写入 `conflict_record`

3. 调用 `GET /api/cert/conflicts`
   - `StorageService.pageConflictRecords()` 返回分页结果

4. 调用 `POST /api/cert/push-to-fabric?conflictId=xxx`
   - `CertController.pushToFabric()`
   - `FabricService.submitConflictToChain()`
   - REAL 模式调用链码 `DetectAndRecord`
   - 成功后写入 `fabric_tx_record`

5. （可选）在 Fabric 侧查询
   - 调用链码 `GetAllConflicts` 验证链上数据

---

## 9. 演示用最小命令清单

```bash
# 1) 启动后端
mvn spring-boot:run

# 2) 健康检查
curl http://localhost:8082/api/

# 3) 下载并解析
curl -X POST http://localhost:8082/api/cert/download-and-parse

# 4) 冲突检测
curl -X POST http://localhost:8082/api/cert/detect-conflicts -H "Content-Type: application/json" -d "{}"

# 5) 查询冲突
curl "http://localhost:8082/api/cert/conflicts?page=1&size=10"

# 6) 上链（示例 conflictId=1）
curl -X POST "http://localhost:8082/api/cert/push-to-fabric?conflictId=1"
```

---

## 10. 常见问题（小白最常踩坑）

- **启动报数据库连接失败**  
  先确认 MySQL 在跑、账号密码正确、`rpki_db` 已建、`sql-init.sql` 已执行。

- **上链失败（REAL 模式）**  
  先确认 Fabric 网络、channel、链码都已启动；再确认 `application-dev.yml` 的 Fabric 路径配置正确。

- **看不到证书详情**  
  如果当前没有该 `id` 的证书，接口会返回空结果（这是正常保护逻辑）。

---

## 11. 配置文件说明

- `src/main/resources/application.yml`：通用配置（端口、context-path、profile）
- `src/main/resources/application-dev.yml`：开发配置（MySQL、RPKI 源、Fabric REAL 配置）
- `src/main/resources/application-test.yml`：测试配置（常用 MOCK）
- `src/main/resources/application-prod.yml`：生产配置（建议全走环境变量）

Fabric 模式切换：

- `fabric.mode: MOCK` -> 不连链，只做本地模拟
- `fabric.mode: REAL` -> 真正调用 Fabric 链码

---

## 12. 一句话总结

这个项目已经具备「下载解析 -> 冲突检测 -> 数据库存证 -> 区块链存证」完整闭环，适合毕业答辩现场做端到端演示。
