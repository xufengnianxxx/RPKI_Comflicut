/**
 * 列表与详情标题用的「合成证书文件名」——按需求由数据库主键 id 派生，非磁盘 cerFileName。
 * 规则：8 位数字左补零 + .cer，例如 id=42 → 00000042.cer
 */
export function syntheticCerFileName(id: number): string {
  return `${String(id).padStart(8, '0')}.cer`
}

/** cert_hash 截短展示，中间省略 */
export function shortCertHash(hash: string | null | undefined, head = 10, tail = 6): string {
  if (!hash) return '—'
  const h = hash.trim()
  if (h.length <= head + tail + 3) return h
  return `${h.slice(0, head)}…${h.slice(-tail)}`
}
