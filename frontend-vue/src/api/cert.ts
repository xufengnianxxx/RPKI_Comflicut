import request from '@/utils/request'
import type {
  ApiResult,
  CertDetailData,
  ConflictResult,
  PageResult,
  PairDetectionRecord,
  RecordPairDetectionPayload,
  RpkiCert,
} from '@/types/api'

/**
 * 分页拉取证书列表（每页 15 条由调用方传 size）
 * @param keyword 可选：按 cert_hash LIKE 或 id 字符串模糊匹配（需后端支持 keyword 参数）
 */
export function fetchCertPage(params: {
  page: number
  size: number
  rir?: string
  hasConflict?: boolean
  keyword?: string
}) {
  return request.get<ApiResult<PageResult<RpkiCert>>>('/api/cert/certs', { params })
}

/** 证书详情 + 关联冲突记录 */
export function fetchCertDetail(id: number) {
  return request.get<ApiResult<CertDetailData>>(`/api/cert/${id}/detail`)
}

/**
 * 双证链下检测（内存、不落库）——避免误触全库 POST /detect-conflicts
 */
export function detectPairOffline(certIdA: number, certIdB: number) {
  return request.post<ApiResult<ConflictResult[]>>('/api/cert/detect-pair', {
    certIdA,
    certIdB,
  })
}

/** 双证链下检测并持久化（写 conflict_record + rpki_cert 冲突摘要） */
export function detectPairAndPersist(certIdA: number, certIdB: number) {
  return request.post<ApiResult<ConflictResult[]>>('/api/cert/detect-pair-persist', {
    certIdA,
    certIdB,
  })
}

/** 分页查询双证检测流水 */
export function fetchPairDetectionRecords(params: { page: number; size: number }) {
  return request.get<ApiResult<PageResult<PairDetectionRecord>>>('/api/cert/pair-detection-records', {
    params,
  })
}

/** 上报一次「开始检测」完整流水（链下 + 链上摘要） */
export function recordPairDetection(body: RecordPairDetectionPayload) {
  return request.post<ApiResult<number>>('/api/cert/pair-detection-records', body)
}
