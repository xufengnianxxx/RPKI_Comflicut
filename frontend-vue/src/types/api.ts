/**
 * 与后端 com.rpki.conflictchecker.dto.Result<T> 对齐
 */
export interface ApiResult<T> {
  success: boolean
  code: number
  message: string
  data: T
}

/** MyBatis-Plus 分页结构（Jackson 序列化后一般为 camelCase） */
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages?: number
}

/** 与 RpkiCert 实体字段对齐（JSON 驼峰） */
export interface RpkiCert {
  id?: number
  certName?: string | null
  cerFileName?: string | null
  serialNumber?: string | null
  issuer?: string | null
  subject?: string | null
  rir?: string | null
  notBefore?: string | null
  notAfter?: string | null
  ipv4Prefixes?: string | null
  ipv6Prefixes?: string | null
  asNumbers?: string | null
  subjectKeyId?: string | null
  authorityKeyId?: string | null
  parentCertId?: number | null
  revoked?: boolean | null
  hasConflict?: boolean | null
  conflictDetails?: string | null
  fabricTxId?: string | null
  fabricBlockNum?: string | null
  isSentToFabric?: boolean | null
  fabricSendTime?: string | null
  fabricSendFailed?: boolean | null
  fabricLastError?: string | null
  certHash?: string | null
  rawCertData?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface CertDetailData {
  certificate: RpkiCert | null
  conflicts: ConflictRecord[]
}

export interface ConflictRecord {
  id?: number
  certIdA?: number
  certIdB?: number
  conflictType?: string
  severity?: string
  details?: string
  conflictKey?: string
  detectedAt?: string
}

/** 链下双证检测单条结果 */
export interface ConflictResult {
  certIdA?: number
  certIdB?: number
  conflictType?: string
  severity?: string
  ruleVersion?: string
  details?: string
  ruleReference?: string
}

/** 链上 detect JSON（解析 data 字符串后） */
export interface ChainDetectParsed {
  txId?: string
  verdict?: string
  primaryConflictType?: string
  legacyWorstCode?: string
  findings?: ChainFinding[]
  rawFindings?: string[]
  log?: string[]
  persistedEvidenceKey?: string
  persistedEvidenceKeys?: string[]
  involvedCertHashes?: string[]
  pairsExamined?: number
  conflictingPairs?: number
  blockNum?: string | number
}

export interface ChainFinding {
  type?: string
  detail?: string
  certHashA?: string
  certHashB?: string
  conflictScope?: string
  involvedCertHashChild?: string
  rulePhase?: string
}

/** 双证检测流水（pair_detection_record） */
export interface PairDetectionRecord {
  id?: number
  certIdA?: number
  certIdB?: number
  offlineHasConflict?: boolean
  offlineSummary?: string | null
  chainVerdict?: string | null
  chainPrimaryType?: string | null
  chainSummary?: string | null
  createdAt?: string | null
}

export interface RecordPairDetectionPayload {
  certIdA: number
  certIdB: number
  offlineHasConflict: boolean
  offlineSummary: string
  chainVerdict?: string
  chainPrimaryType?: string
  chainSummary?: string
}
