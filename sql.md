## RPKI 冲突检测系统数据库结构报告

这份报告基于当前项目的 `sql-init.sql` 与实体类，说明每张表用途、字段含义、表间关系及数据流。

---

## 1) 数据库概览

- 数据库名：`rpki_db`
- 核心业务表共 3 张：
  - `rpki_cert`：证书主数据表（证书事实 + 检测状态 + 上链状态）
  - `conflict_record`：冲突结果表（证书对之间的冲突事件）
  - `fabric_tx_record`：上链交易日志表（提交 Fabric 的审计记录）

---

## 2) 表：`rpki_cert`（证书主表）

### 表用途
存放解析后的 RPKI X.509 证书信息，是冲突检测的输入数据源，也是前端证书列表/详情的基础。

### 字段说明

- `id`：主键，自增。
- `cert_name`：证书显示名（系统内部命名，通常含来源与序列号特征）。
- `serial_number`：证书序列号。
- `issuer`：签发者 DN。
- `subject`：主体 DN。
- `rir`：来源标签（如 `DEMO`、`RIPE`、`APNIC` 等，当前演示也可能用 `DEMO_CASE`）。
- `not_before`：证书生效时间。
- `not_after`：证书失效时间。
- `ipv4_prefixes`：IPv4 资源前缀列表（JSON 字符串）。
- `ipv6_prefixes`：IPv6 资源前缀列表（JSON 字符串）。
- `as_numbers`：AS 资源列表（JSON 字符串）。
- `parent_cert_id`：父证书 ID（用于链关系/越权判定）。
- `revoked`：是否被撤销。
- `has_conflict`：是否被标记存在冲突。
- `conflict_details`：该证书关联冲突的摘要文本。
- `fabric_tx_id`：该证书关联的链上交易 ID（字段存在，是否使用取决于业务流程）。
- `fabric_block_num`：链上区块号（同上）。
- `is_sent_to_fabric`：是否已上链（同上）。
- `fabric_send_time`：上链时间（同上）。
- `cert_hash`：证书内容哈希（用于去重，唯一键）。
- `raw_cert_data`：证书原始内容（通常 Base64）。
- `created_at`：创建时间。
- `updated_at`：更新时间。

### 索引/约束
- `uk_cert_hash(cert_hash)`：证书去重关键约束。
- `idx_rir(rir)`：按来源筛选优化。
- `idx_has_conflict(has_conflict)`：冲突筛选优化。
- `idx_parent_cert(parent_cert_id)`：链关系查询优化。

---

## 3) 表：`conflict_record`（冲突结果表）

### 表用途
保存冲突检测输出，每行代表一条“证书对冲突事件”。

### 字段说明
- `id`：主键，自增。
- `cert_id_a`：冲突证书 A 的 `rpki_cert.id`。
- `cert_id_b`：冲突证书 B 的 `rpki_cert.id`。
- `conflict_type`：冲突类型（如 `IP_OUT_OF_SCOPE`、`AS_OVERLAP_SIBLING` 等）。
- `severity`：严重级别（`CRITICAL/HIGH/MEDIUM`）。
- `rule_version`：规则版本（便于规则迭代追溯）。
- `rule_reference`：规则来源标识（如 RFC 章节）。
- `conflict_key`：冲突唯一键（用于防止重复写入）。
- `details`：冲突详情文本。
- `detected_at`：检测时间。

### 索引/约束
- `uk_conflict_key(conflict_key(255))`：同一冲突不重复入库。
- `idx_conflict_cert_a(cert_id_a)` / `idx_conflict_cert_b(cert_id_b)`：按证书反查冲突优化。

---

## 4) 表：`fabric_tx_record`（链上交易日志表）

### 表用途
记录每次向 Fabric 提交交易的请求和结果，作为链路审计表。

### 字段说明
- `id`：主键，自增。
- `conflict_record_id`：关联的冲突记录 ID（当前代码可能为空，视调用路径而定）。
- `mode`：提交模式（`REAL` 或 `MOCK`）。
- `tx_id`：Fabric 交易 ID。
- `block_num`：区块号（若可获得则写入）。
- `payload`：提交的业务载荷（JSON）。
- `status`：提交状态（如 `SUCCESS`/`FAILED`）。
- `error_message`：失败错误信息。
- `created_at`：记录创建时间。

### 索引
- `idx_fabric_tx_id(tx_id)`：按交易号检索。
- `idx_fabric_status(status)`：按状态统计/筛选。

---

## 5) 表间关系（逻辑层）

- `rpki_cert` 1..n `conflict_record`
  - 通过 `cert_id_a` / `cert_id_b` 引用证书。
- `conflict_record` 1..n `fabric_tx_record`
  - 理论上可通过 `conflict_record_id` 关联交易日志（当前实现可继续加强这条绑定）。
- `rpki_cert` 自关联
  - `parent_cert_id -> rpki_cert.id` 表示父子证书链关系。

> 注：当前 SQL 没有显式外键约束（FK），属于“应用层维护关联一致性”的模式。

---

## 6) 典型数据流

1. 下载并解析证书 -> 写入 `rpki_cert`（按 `cert_hash` 去重/更新）。
2. 执行冲突检测 -> 写入 `conflict_record`，并回写 `rpki_cert.has_conflict/conflict_details`。
3. 手动推送上链 -> 写 `fabric_tx_record` 记录成功/失败与 `tx_id`。

---

## 7) 结论

- 这套库结构是**“证书主数据 + 冲突事件 + 上链审计”**三层模型，职责清晰，适合演示与迭代。
- 当前最关键业务主键是 `rpki_cert.cert_hash` 与 `conflict_record.conflict_key`，用于防重复。
- 若后续做生产级增强，建议补充：
  - 显式外键约束或一致性校验任务
  - `conflict_record_id` 与 `fabric_tx_record` 的强绑定
  - 对 `detected_at`、`rir`、`severity` 的组合索引优化报表查询。