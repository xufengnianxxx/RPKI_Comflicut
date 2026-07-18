我来详细回答你这三个问题：

## 1️⃣ Fabric 相关文档和配置

### 📂 用于上链的目录和文件

```
/home/xfn/rpki-conflict-checker-backend/
├── src/main/java/com/rpki/conflictchecker/
│   ├── service/FabricService.java          ← Fabric 服务类
│   ├── controller/CertController.java      ← 包含 4 个 Fabric API 端点
│   └── config/FabricConfig.java            ← Fabric 配置类
│
├── fabric-config.json                      ← Fabric 网络配置
├── fabric-integration.yml                  ← 应用程序配置（fabric.enabled 开关）
├── deploy-fabric.sh                        ← 部署脚本
│
└── /home/xfn/fabric-samples/               ← Fabric 网络部署目录
    └── test-network/
        ├── chaincode/rpkicc/                ← RPKI 智能合约
        │   └── go/assetTransfer.go
        ├── organizations/                   ← 组织证书和密钥
        ├── channel-artifacts/               ← 通道配置
        └── network.sh                       ← 启动脚本
```

### 🏗️ Fabric 本地部署位置

**是的，Fabric 环境是在本地部署的，具体位置：**
```
/home/xfn/fabric-samples/test-network/
```

**部署方式：**
- 使用 **Docker** 容器运行 Fabric 网络
- 使用 **Docker Compose** 编排多个容器
- 高度集中的本地开发环境

**具体包含：**
- Orderer 节点（排序服务）
- Peer 节点（背书节点）
- CA（证书颁发机构）
- 测试通道（channel）

**启动命令（还未执行）：**
```bash
cd /home/xfn/fabric-samples/test-network
./network.sh up createChannel -ca
```

---

## 2️⃣ 现在下载的是哪些证书

### 📜 证书来源和类型

**来源网址：**
```
https://ftp.ripe.net/rpki/
```

**具体是什么证书：**
- **AFRINIC RPKI 证书**（非洲的）
- **格式**：X.509 证书
- **压缩格式**：TAR.XZ 格式
- **内容**：约几千到几万张证书

### 证书包含的信息

每张证书中含有：
```
✅ 证书名称（Common Name）
✅ 发行者（Issuer）
✅ 主体（Subject）
✅ 有效期（notBefore / notAfter）
✅ IPv4 地址段（CIDR 格式，比如 192.168.0.0/24）
✅ IPv6 地址段（CIDR 格式，比如 2001:db8::/32）
✅ ASN 号码（自治系统号，比如 AS64512）
```

### 下载代码位置

CertificateDownloadService.java

关键代码：
```java
String url = "https://ftp.ripe.net/rpki/afrinic/...";  // 下载URL
// 通过 HTTP 客户端下载
// 通过 TAR.XZ 解压器解压
// 提取所有 .cer 证书文件
```

---

## 3️⃣ 如何进入 MySQL 查看保存的数据

### 📊 进入数据库的方式

**方法 1：使用命令行（最简单）**

```bash
# 进入 MySQL 容器
docker exec -it rpki-mysql mysql -h localhost -uroot -prpki123

# 或者直接在本地连接（如果 MySQL 暴露了 3306 端口）
mysql -h 127.0.0.1 -u root -p
# 密码：rpki123
```

**方法 2：使用 GUI 工具**

推荐工具：
- **MySQL Workbench** - 官方工具
- **DBeaver** - 免费通用工具
- **Navicat** - 商业工具

连接信息：
```
主机：127.0.0.1 或 localhost
端口：3306
用户名：root
密码：rpki123
数据库：rpki_db
```

### 🗄️ 数据库结构

进入后执行这些命令：

```sql
-- 选择数据库
USE rpki_db;

-- 查看所有表
SHOW TABLES;

-- 应该能看到这个表
-- rpki_cert

-- 查看表结构
DESC rpki_cert;

-- 查看所有证书
SELECT * FROM rpki_cert;

-- 查看冲突证书数量
SELECT COUNT(*) FROM rpki_cert WHERE has_conflict = true;

-- 查看所有冲突证书
SELECT * FROM rpki_cert WHERE has_conflict = true;

-- 查看已上链的证书
SELECT * FROM rpki_cert WHERE is_sent_to_fabric = true;

-- 统计信息
SELECT 
    COUNT(*) as 总数,
    SUM(CASE WHEN has_conflict = true THEN 1 ELSE 0 END) as 冲突数,
    SUM(CASE WHEN is_sent_to_fabric = true THEN 1 ELSE 0 END) as 已上链数
FROM rpki_cert;
```

### 💾 表结构详解

```
rpki_cert 表包含以下字段：

id                      - 主键（自动递增）
cert_name              - 证书名称
issuer                 - 发行者
subject                - 主体
not_before             - 开始时间
not_after              - 过期时间
ipv4_prefixes          - IPv4 地址段（JSON 格式）
ipv6_prefixes          - IPv6 地址段（JSON 格式）
as_numbers             - ASN 号码（JSON 格式）
has_conflict           - 是否有冲突（true/false）
conflict_details       - 冲突详情（JSON 格式）
fabric_tx_id           - Fabric 交易 ID
fabric_block_num       - Fabric 区块号
is_sent_to_fabric      - 是否已上链（true/false）
fabric_send_time       - 上链时间
cert_hash              - 证书哈希值
raw_cert_data          - 原始证书数据（BLOB）
created_at             - 创建时间
updated_at             - 更新时间
```

### 🔍 实用查询示例

```sql
-- 1. 查看最新的 10 条记录
SELECT id, cert_name, has_conflict, is_sent_to_fabric, created_at 
FROM rpki_cert 
ORDER BY created_at DESC 
LIMIT 10;

-- 2. 查看包含特定 IPv4 的证书
SELECT cert_name, ipv4_prefixes, created_at 
FROM rpki_cert 
WHERE ipv4_prefixes LIKE '%192.168%';

-- 3. 统计冲突情况
SELECT 
    has_conflict,
    COUNT(*) as 数量
FROM rpki_cert 
GROUP BY has_conflict;

-- 4. 查看每个发行者的证书数
SELECT 
    issuer,
    COUNT(*) as 证书数
FROM rpki_cert 
GROUP BY issuer 
ORDER BY COUNT(*) DESC;

-- 5. 查看具体的冲突详情
SELECT 
    id, 
    cert_name, 
    conflict_details 
FROM rpki_cert 
WHERE has_conflict = true 
LIMIT 5;
```

### 📈 当前数据状态

根据日志记录，现在数据库中有：
```
✅ 已建立表结构
✅ MySQL 容器运行正常
⚠️ 还没有运行完整的处理流程，数据可能为空或很少

要填充数据，需要执行：
curl -X POST http://localhost:8081/api/cert/auto-process
```

---

## 🎯 完整操作流程

```bash
# 1. 启动 MySQL
docker start rpki-mysql

# 2. 启动应用
java -jar target/conflict-checker-backend-1.0.0.jar &

# 3. 触发数据处理（会下载证书并存入数据库）
curl -X POST http://localhost:8081/api/cert/auto-process

# 4. 等待处理完成（可能需要几分钟）...

# 5. 进入 MySQL 查看数据
mysql -h 127.0.0.1 -u root -prpki123
USE rpki_db;
SELECT COUNT(*) FROM rpki_cert;
```

有其他问题吗？