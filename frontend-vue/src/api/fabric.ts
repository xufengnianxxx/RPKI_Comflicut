import request from '@/utils/request'
import type { ApiResult } from '@/types/api'

/** 与后端 FabricSubmitResponseDto 对齐 */
export interface FabricSubmitSummary {
  txId?: string
  rawResponse?: string
  storedCount?: number
  mock?: boolean
}

/**
 * 按数据库证书 ID 将两证脱敏数据 storeCertificateBatch 上链，避免 detect 报 MISSING_ASSET。
 */
export function submitCertificatesByCertIds(certIdA: number, certIdB: number) {
  return request.post<ApiResult<FabricSubmitSummary>>(
    '/api/fabric/chain/certificates/batch-by-cert-ids',
    { certIdA, certIdB },
  )
}

/**
 * 链上双证检测。后端返回 Result&lt;String&gt;，data 为链码 JSON 字符串，需再 JSON.parse。
 */
export function detectOnChain(certHashA: string, certHashB: string) {
  return request.post<ApiResult<string>>('/api/fabric/chain/detect-on-chain', null, {
    params: { certHashA, certHashB },
  })
}

/**
 * 将 detect-on-chain 的原始 JSON 结果回写数据库并触发存证锚定：
 * - 更新 rpki_cert.has_conflict / fabric_tx_id
 * - 触发 recordConflictEvidenceBatch（若存在 findings）
 * - 记录 fabric_tx_record 审计流水
 */
export function anchorDetectToDb(detectResultJson: string) {
  return request.post<ApiResult<unknown>>('/api/fabric/chain/detect-on-chain/anchor-db', detectResultJson, {
    headers: { 'Content-Type': 'text/plain' },
  })
}
