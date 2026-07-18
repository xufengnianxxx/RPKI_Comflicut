-- =============================================================================
-- 步骤 2：按 slot 构造冲突场景（依赖 01_pick_50_and_backup.sql 已执行）
-- =============================================================================

USE rpki_db;

-- ---------------------------------------------------------------------------
-- Hub：slot 50 — 共同 parent_cert_id 锚点（对等可比）
-- ---------------------------------------------------------------------------
UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick ph ON ph.slot = 50 AND c.id = ph.id
SET
  c.parent_cert_id = NULL,
  c.ipv4_prefixes = '["0.0.0.0/0"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '["65000"]',
  c.revoked = 0;

-- ---------------------------------------------------------------------------
-- slot 1–16：对等（同 parent = id(slot50)）
-- ---------------------------------------------------------------------------
UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p ON p.slot BETWEEN 1 AND 16 AND c.id = p.id
INNER JOIN rpki_cert_conflict_pick hub ON hub.slot = 50
SET c.parent_cert_id = hub.id;

-- IPv4 对等三对
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 1 AND c.id = p.id
SET c.ipv4_prefixes = '["10.10.0.0/16"]', c.ipv6_prefixes = '[]', c.as_numbers = '[]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 2 AND c.id = p.id
SET c.ipv4_prefixes = '["10.10.1.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '[]', c.revoked = 0;

UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 3 AND c.id = p.id
SET c.ipv4_prefixes = '["10.11.0.0/23"]', c.ipv6_prefixes = '[]', c.as_numbers = '[]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 4 AND c.id = p.id
SET c.ipv4_prefixes = '["10.11.1.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '[]', c.revoked = 0;

UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 5 AND c.id = p.id
SET c.ipv4_prefixes = '["10.12.0.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '[]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 6 AND c.id = p.id
SET c.ipv4_prefixes = '["10.12.1.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '[]', c.revoked = 0;

-- IPv6 对等三对
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 7 AND c.id = p.id
SET c.ipv4_prefixes = '[]', c.ipv6_prefixes = '["2001:db8:a::/48"]', c.as_numbers = '[]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 8 AND c.id = p.id
SET c.ipv4_prefixes = '[]', c.ipv6_prefixes = '["2001:db8:a:1::/64"]', c.as_numbers = '[]', c.revoked = 0;

UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 9 AND c.id = p.id
SET c.ipv4_prefixes = '[]', c.ipv6_prefixes = '["2001:db8:b::/47"]', c.as_numbers = '[]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 10 AND c.id = p.id
SET c.ipv4_prefixes = '[]', c.ipv6_prefixes = '["2001:db8:b:1::/48"]', c.as_numbers = '[]', c.revoked = 0;

UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 11 AND c.id = p.id
SET c.ipv4_prefixes = '[]', c.ipv6_prefixes = '["fd00:cafe:0:0::/64"]', c.as_numbers = '[]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 12 AND c.id = p.id
SET c.ipv4_prefixes = '[]', c.ipv6_prefixes = '["fd00:cafe:0:1::/64"]', c.as_numbers = '[]', c.revoked = 0;

-- AS 对等 + IP-AS 启发式
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 13 AND c.id = p.id
SET c.ipv4_prefixes = '["10.13.0.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65010"]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 14 AND c.id = p.id
SET c.ipv4_prefixes = '["10.13.0.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65010","65011"]', c.revoked = 0;

UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 15 AND c.id = p.id
SET c.ipv4_prefixes = '["10.14.0.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65020"]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 16 AND c.id = p.id
SET c.ipv4_prefixes = '["10.14.0.128/25"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65021"]', c.revoked = 0;

-- ---------------------------------------------------------------------------
-- slot 17–20：继承越权（parent_cert_id 指向 slot17，非 hub）
-- ---------------------------------------------------------------------------
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 17 AND c.id = p.id
SET
  c.parent_cert_id = NULL,
  c.ipv4_prefixes = '["192.0.2.0/24"]',
  c.ipv6_prefixes = '["2001:db8:1::/48"]',
  c.as_numbers = '["65001","65002"]',
  c.revoked = 0;

UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p18 ON p18.slot = 18 AND c.id = p18.id
INNER JOIN rpki_cert_conflict_pick p17 ON p17.slot = 17
SET
  c.parent_cert_id = p17.id,
  c.ipv4_prefixes = '["198.51.100.0/24"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '["65001"]',
  c.revoked = 0;

UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p19 ON p19.slot = 19 AND c.id = p19.id
INNER JOIN rpki_cert_conflict_pick p17 ON p17.slot = 17
SET
  c.parent_cert_id = p17.id,
  c.ipv4_prefixes = '["192.0.2.0/25"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '["65003"]',
  c.revoked = 0;

UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p20 ON p20.slot = 20 AND c.id = p20.id
INNER JOIN rpki_cert_conflict_pick p17 ON p17.slot = 17
SET
  c.parent_cert_id = p17.id,
  c.ipv4_prefixes = '["192.0.2.128/25"]',
  c.ipv6_prefixes = '["2001:db8:2::/48"]',
  c.as_numbers = '["65001"]',
  c.revoked = 0;

-- ---------------------------------------------------------------------------
-- slot 21–23：多跳撤销残留
-- ---------------------------------------------------------------------------
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 21 AND c.id = p.id
SET
  c.parent_cert_id = NULL,
  c.ipv4_prefixes = '["172.28.21.0/24"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '[]',
  c.revoked = 1;

UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p22 ON p22.slot = 22 AND c.id = p22.id
INNER JOIN rpki_cert_conflict_pick p21 ON p21.slot = 21
SET
  c.parent_cert_id = p21.id,
  c.ipv4_prefixes = '["172.28.22.0/24"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '[]',
  c.revoked = 0;

UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p23 ON p23.slot = 23 AND c.id = p23.id
INNER JOIN rpki_cert_conflict_pick p22 ON p22.slot = 22
SET
  c.parent_cert_id = p22.id,
  c.ipv4_prefixes = '["172.28.23.0/24"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '[]',
  c.revoked = 0;

-- ---------------------------------------------------------------------------
-- slot 24–25：直接父子撤销残留
-- ---------------------------------------------------------------------------
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 24 AND c.id = p.id
SET
  c.parent_cert_id = NULL,
  c.ipv4_prefixes = '["172.28.24.0/24"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '[]',
  c.revoked = 1;

UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p25 ON p25.slot = 25 AND c.id = p25.id
INNER JOIN rpki_cert_conflict_pick p24 ON p24.slot = 24
SET
  c.parent_cert_id = p24.id,
  c.ipv4_prefixes = '["172.28.25.0/24"]',
  c.ipv6_prefixes = '[]',
  c.as_numbers = '[]',
  c.revoked = 0;

-- ---------------------------------------------------------------------------
-- slot 26–29：链上异长相邻 + AS_MISMATCH（对等：同 hub）
-- ---------------------------------------------------------------------------
UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p ON p.slot BETWEEN 26 AND 29 AND c.id = p.id
INNER JOIN rpki_cert_conflict_pick hub ON hub.slot = 50
SET c.parent_cert_id = hub.id;

UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 26 AND c.id = p.id
SET c.ipv4_prefixes = '["10.40.0.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65040"]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 27 AND c.id = p.id
SET c.ipv4_prefixes = '["10.40.1.0/25"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65040"]', c.revoked = 0;

UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 28 AND c.id = p.id
SET c.ipv4_prefixes = '["10.41.0.0/23"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65050"]', c.revoked = 0;
UPDATE rpki_cert c INNER JOIN rpki_cert_conflict_pick p ON p.slot = 29 AND c.id = p.id
SET c.ipv4_prefixes = '["10.41.1.0/24"]', c.ipv6_prefixes = '[]', c.as_numbers = '["65051"]', c.revoked = 0;

-- ---------------------------------------------------------------------------
-- slot 30–49：隔离 /24，避免与上述用例交叉误报
-- ---------------------------------------------------------------------------
UPDATE rpki_cert c
INNER JOIN rpki_cert_conflict_pick p ON p.slot BETWEEN 30 AND 49 AND c.id = p.id
INNER JOIN rpki_cert_conflict_pick hub ON hub.slot = 50
SET
  c.parent_cert_id = hub.id,
  c.ipv4_prefixes = CONCAT('["172.27.', p.slot, '.0/24"]'),
  c.ipv6_prefixes = '[]',
  c.as_numbers = '[]',
  c.revoked = 0;
