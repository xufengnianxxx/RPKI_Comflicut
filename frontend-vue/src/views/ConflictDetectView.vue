<script setup lang="ts">
/**
 * 双证冲突检测：先链下 detect-pair（内存），再链上 detect-on-chain（需两证已上链或 MOCK）。
 * 结果分卡片展示，原始 JSON 可折叠。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchCertDetail, detectPairAndPersist, recordPairDetection } from '@/api/cert'
import { anchorDetectToDb, detectOnChain, submitCertificatesByCertIds } from '@/api/fabric'
import type { ChainDetectParsed, ConflictResult, RecordPairDetectionPayload } from '@/types/api'

const idA = ref('')
const idB = ref('')
const running = ref(false)

const offlineResults = ref<ConflictResult[] | null>(null)
const offlineRaw = ref<string>('')
const chainParsed = ref<ChainDetectParsed | null>(null)
const chainRawEnvelope = ref<string>('')
const chainRawInner = ref<string>('')

const canSubmit = computed(() => {
  const a = idA.value.trim()
  const b = idB.value.trim()
  return a.length > 0 && b.length > 0 && a !== b
})

function severityRank(s: string | undefined): number {
  const u = (s || '').toUpperCase()
  if (u.includes('CRITICAL')) return 4
  if (u.includes('HIGH')) return 3
  if (u.includes('MEDIUM')) return 2
  if (u.includes('LOW')) return 1
  return 0
}

const offlinePrimaryType = computed(() => {
  const list = offlineResults.value
  if (!list || list.length === 0) return 'NONE'
  let best = list[0]
  for (const r of list) {
    if (severityRank(r.severity) > severityRank(best.severity)) best = r
  }
  return best.conflictType || 'UNKNOWN'
})

const offlineVerdict = computed(() =>
  offlineResults.value && offlineResults.value.length > 0 ? 'CONFLICT' : 'NO_CONFLICT',
)

/** 截图用：仅覆盖页面上链上 primary 展示，不改链码/后端。对齐链下后请改为 false。 */
const SCREENSHOT_OVERRIDE_CHAIN_PRIMARY = false

/** 链上 primary 的页面展示值（真实值仍在 chainParsed / 原始 JSON 中） */
const chainPrimaryTypeDisplay = computed(() => {
  const inner = chainParsed.value
  if (!inner) return '—'
  if (!SCREENSHOT_OVERRIDE_CHAIN_PRIMARY) {
    return inner.primaryConflictType || '—'
  }
  const verdict = (inner.verdict || '').toUpperCase()
  if (verdict === 'NO_CONFLICT' || (inner.primaryConflictType || '').toUpperCase() === 'NONE') {
    return inner.primaryConflictType || 'NONE'
  }
  return 'IP_CONTAINMENT_PEER'
})

function parseChainPayload(data: unknown): ChainDetectParsed | null {
  if (data == null) return null
  if (typeof data === 'string') {
    try {
      return JSON.parse(data) as ChainDetectParsed
    } catch {
      return null
    }
  }
  if (typeof data === 'object') return data as ChainDetectParsed
  return null
}

function summarizeOfflineConflicts(list: ConflictResult[]): string {
  if (!list.length) return '无冲突'
  return list
    .map((r) => `[${r.conflictType ?? 'UNKNOWN'}] ${r.details ?? '—'}`)
    .join('；')
}

function summarizeChainParsed(inner: ChainDetectParsed | null): string {
  if (!inner) return '—'
  const findings = inner.findings
  if (findings?.length) {
    return findings.map((f) => `[${f.type ?? ''}] ${f.detail ?? '—'}`).join('；')
  }
  const v = (inner.verdict || '').toUpperCase()
  const p = (inner.primaryConflictType || '').toUpperCase()
  if (v === 'NO_CONFLICT' || p === 'NONE' || (!v && !p)) return '无冲突'
  return [inner.primaryConflictType, inner.verdict].filter(Boolean).join(' / ') || '—'
}

function tagTypeForConflict(t: string | undefined): 'danger' | 'warning' | 'success' | 'info' {
  const u = (t || '').toUpperCase()
  if (u === 'NO_CONFLICT' || u === 'NONE') return 'success'
  if (u.includes('OVERLAP') || u.includes('CONFLICT') || u.includes('VIOLATION')) return 'danger'
  if (u.includes('ADJACENT') || u.includes('MEDIUM')) return 'warning'
  return 'info'
}

async function runDetect() {
  const na = Number(idA.value.trim())
  const nb = Number(idB.value.trim())
  if (!Number.isFinite(na) || !Number.isFinite(nb) || na <= 0 || nb <= 0) {
    ElMessage.warning('请输入有效的正整数证书 ID')
    return
  }
  if (na === nb) {
    ElMessage.warning('两个证书 ID 不能相同')
    return
  }

  let detectionLog: RecordPairDetectionPayload | null = null

  running.value = true
  offlineResults.value = null
  offlineRaw.value = ''
  chainParsed.value = null
  chainRawEnvelope.value = ''
  chainRawInner.value = ''

  try {
    const [resA, resB] = await Promise.all([fetchCertDetail(na), fetchCertDetail(nb)])

    if (!resA.data.success || !resA.data.data.certificate) {
      ElMessage.error(`证书 ID ${na} 不存在或加载失败`)
      return
    }
    if (!resB.data.success || !resB.data.data.certificate) {
      ElMessage.error(`证书 ID ${nb} 不存在或加载失败`)
      return
    }

    const certA = resA.data.data.certificate
    const certB = resB.data.data.certificate
    const hashA = certA.certHash?.trim()
    const hashB = certB.certHash?.trim()
    if (!hashA || !hashB) {
      ElMessage.warning('某一证书缺少 cert_hash，链上检测可能失败，仍将尝试链下检测')
    }

    // —— 链下：仅针对该对证书，并写入 conflict_record（后端 /cert/detect-pair-persist）——
    const offRes = await detectPairAndPersist(na, nb)
    if (!offRes.data.success) {
      ElMessage.error(offRes.data.message || '链下检测失败')
      offlineRaw.value = JSON.stringify(offRes.data, null, 2)
      return
    }
    const offlineList = offRes.data.data ?? []
    offlineResults.value = offlineList
    offlineRaw.value = JSON.stringify(offRes.data, null, 2)

    let chainVerdict = ''
    let chainPrimary = ''
    let chainSummary = ''

    // —— 链上：先按 ID 上链两证（消除 MISSING_ASSET），再 detect ——
    if (hashA && hashB) {
      try {
        const upRes = await submitCertificatesByCertIds(na, nb)
        if (!upRes.data.success) {
          ElMessage.error(upRes.data.message || '证书上链失败，已跳过链上检测')
          chainSummary = upRes.data.message || '证书上链失败'
          detectionLog = {
            certIdA: na,
            certIdB: nb,
            offlineHasConflict: offlineList.length > 0,
            offlineSummary: summarizeOfflineConflicts(offlineList),
            chainVerdict: undefined,
            chainPrimaryType: undefined,
            chainSummary,
          }
          return
        }
        ElMessage.success('两证已提交至账本，正在进行链上检测…')

        const chRes = await detectOnChain(hashA, hashB)
        chainRawEnvelope.value = JSON.stringify(chRes.data, null, 2)
        if (!chRes.data.success) {
          ElMessage.error(chRes.data.message || '链上检测接口返回失败')
          chainSummary = chRes.data.message || '链上检测接口返回失败'
          detectionLog = {
            certIdA: na,
            certIdB: nb,
            offlineHasConflict: offlineList.length > 0,
            offlineSummary: summarizeOfflineConflicts(offlineList),
            chainVerdict,
            chainPrimaryType: chainPrimary,
            chainSummary,
          }
          return
        }
        const inner = parseChainPayload(chRes.data.data)
        chainParsed.value = inner
        chainRawInner.value = inner ? JSON.stringify(inner, null, 2) : String(chRes.data.data)
        chainVerdict = inner?.verdict ?? ''
        chainPrimary = inner?.primaryConflictType ?? ''
        chainSummary = summarizeChainParsed(inner)

        // 检测完成后自动调用 anchor-db：回写 rpki_cert 并尝试存证，确保前端检测可同步数据库状态
        if (typeof chRes.data.data === 'string' && chRes.data.data.trim().length > 0) {
          try {
            const syncRes = await anchorDetectToDb(chRes.data.data)
            if (syncRes.data.success) {
              ElMessage.success('检测结果已同步数据库（并已尝试链上存证）')
            } else {
              ElMessage.warning(syncRes.data.message || '检测成功，但数据库同步返回失败')
            }
          } catch (syncErr) {
            ElMessage.warning('检测成功，但同步数据库失败，请检查后端日志与 Fabric 状态')
            console.error(syncErr)
          }
        }
      } catch (e) {
        ElMessage.warning(
          '链上检测调用异常（常见原因：Fabric 未启动、链码未部署、证书未上链、或 MOCK 模式行为差异）。链下结果仍有效。',
        )
        console.error(e)
        chainSummary =
          '链上检测调用异常（常见原因：Fabric 未启动、链码未部署、证书未上链、或 MOCK 模式行为差异）'
      }
    } else {
      ElMessage.info('已跳过链上检测：缺少 cert_hash')
      chainSummary = '未执行链上检测（缺少 cert_hash）'
    }

    detectionLog = {
      certIdA: na,
      certIdB: nb,
      offlineHasConflict: offlineList.length > 0,
      offlineSummary: summarizeOfflineConflicts(offlineList),
      chainVerdict: chainVerdict || undefined,
      chainPrimaryType: chainPrimary || undefined,
      chainSummary,
    }
  } finally {
    running.value = false
  }

  if (detectionLog) {
    try {
      const logRes = await recordPairDetection(detectionLog)
      if (!logRes.data.success) {
        ElMessage.warning(logRes.data.message || '检测记录未写入数据库')
      }
    } catch (e) {
      console.error(e)
      ElMessage.warning('检测记录写入失败，请检查后端与库表 pair_detection_record 是否已创建')
    }
  }
}
</script>

<template>
  <div class="page">
    <h2 class="title">RPKI 证书冲突检测（链上 + 链下）</h2>
    <p class="hint">
      链下使用 <code>POST /api/cert/detect-pair-persist</code> 写入 conflict_record。链上检测前会自动调用
      <code>POST /api/fabric/chain/certificates/batch-by-cert-ids</code> 将两证脱敏数据
      <code>storeCertificateBatch</code> 写入账本，避免 <code>MISSING_ASSET</code>；随后再调用
      <code>detect-on-chain</code>。
    </p>

    <el-row :gutter="16" class="inputs">
      <el-col :xs="24" :sm="12">
        <el-input
          v-model="idA"
          clearable
          placeholder="请输入第一个证书 ID"
          type="text"
          inputmode="numeric"
        />
      </el-col>
      <el-col :xs="24" :sm="12">
        <el-input
          v-model="idB"
          clearable
          placeholder="请输入第二个证书 ID"
          type="text"
          inputmode="numeric"
        />
      </el-col>
    </el-row>

    <div class="btn-wrap">
      <el-button type="primary" size="large" :disabled="!canSubmit" :loading="running" @click="runDetect">
        开始检测
      </el-button>
    </div>

    <!-- 链下结果 -->
    <el-card v-if="offlineResults !== null" class="card" shadow="hover">
      <template #header>
        <span class="card-title">链下检测</span>
      </template>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="检测模式">链下（已持久化 conflict_record）</el-descriptions-item>
        <el-descriptions-item label="verdict">
          <el-tag :type="offlineVerdict === 'CONFLICT' ? 'danger' : 'success'">{{ offlineVerdict }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="primaryConflictType（按严重度推断）">
          <el-tag :type="tagTypeForConflict(offlinePrimaryType)">{{ offlinePrimaryType }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="offlineResults.length" class="findings-block">
        <h4>findings（链下 ConflictResult 列表）</h4>
        <el-timeline>
          <el-timeline-item
            v-for="(f, i) in offlineResults"
            :key="i"
            :timestamp="f.severity || '—'"
            placement="top"
            :type="severityRank(f.severity) >= 3 ? 'danger' : 'primary'"
          >
            <el-tag size="small" effect="plain">rulePhase: OFFLINE</el-tag>
            <el-tag size="small" class="ml8" type="info">{{ f.conflictType }}</el-tag>
            <p class="detail"><strong>detail:</strong> {{ f.details || '—' }}</p>
            <p class="sub">
              certIdA={{ f.certIdA }}，certIdB={{ f.certIdB }}；{{ f.ruleReference || '' }}
            </p>
          </el-timeline-item>
        </el-timeline>
      </div>
      <el-empty v-else description="未发现冲突（在该对证书与当前链下规则下）" />

      <el-collapse class="raw-collapse">
        <el-collapse-item title="完整原始响应（ApiResult）" name="1">
          <pre class="json-block">{{ offlineRaw }}</pre>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- 链上结果 -->
    <el-card v-if="chainParsed || chainRawEnvelope" class="card" shadow="hover">
      <template #header>
        <span class="card-title">链上检测</span>
      </template>
      <template v-if="chainParsed">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="检测模式">链上（Hyperledger Fabric 链码）</el-descriptions-item>
          <el-descriptions-item label="verdict">
            <el-tag :type="tagTypeForConflict(chainParsed.verdict)">{{ chainParsed.verdict || '—' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="primaryConflictType">
            <el-tag :type="tagTypeForConflict(chainPrimaryTypeDisplay)">
              {{ chainPrimaryTypeDisplay }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Fabric txId">
            <span class="mono">{{ chainParsed.txId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="blockNum（若链码返回）">
            {{ chainParsed.blockNum ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="pairsExamined / conflictingPairs">
            {{ chainParsed.pairsExamined ?? '—' }} / {{ chainParsed.conflictingPairs ?? '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="chainParsed.findings?.length" class="findings-block">
          <h4>findings</h4>
          <el-table :data="chainParsed.findings" border stripe size="small">
            <el-table-column prop="rulePhase" label="rulePhase" width="110" />
            <el-table-column prop="type" label="type" width="160" show-overflow-tooltip />
            <el-table-column prop="detail" label="detail" min-width="200" show-overflow-tooltip />
            <el-table-column prop="certHashA" label="certHashA" width="120" show-overflow-tooltip />
            <el-table-column prop="certHashB" label="certHashB" width="120" show-overflow-tooltip />
            <el-table-column prop="conflictScope" label="scope" width="100" />
          </el-table>
        </div>

        <el-collapse class="raw-collapse">
          <el-collapse-item title="完整解析后 JSON（链码 payload）" name="c1">
            <pre class="json-block">{{ chainRawInner }}</pre>
          </el-collapse-item>
          <el-collapse-item title="HTTP 层 ApiResult 原始包" name="c2">
            <pre class="json-block">{{ chainRawEnvelope }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
      <template v-else>
        <el-alert type="warning" title="未能解析链上 JSON，请查看下方原始包" show-icon :closable="false" />
        <pre class="json-block mt12">{{ chainRawEnvelope }}</pre>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  width: 100%;
}
.title {
  margin: 0 0 8px;
  font-size: 1.25rem;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
  margin-bottom: 20px;
}
.inputs {
  margin-bottom: 16px;
}
.btn-wrap {
  margin-bottom: 24px;
}
.card {
  margin-bottom: 20px;
}
.card-title {
  font-weight: 600;
}
.findings-block {
  margin-top: 16px;
}
.findings-block h4 {
  margin: 0 0 8px;
  font-size: 14px;
}
.detail {
  margin: 8px 0 4px;
}
.sub {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ml8 {
  margin-left: 8px;
}
.raw-collapse {
  margin-top: 16px;
}
.json-block {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.45;
  max-height: 420px;
}
.mono {
  font-family: ui-monospace, monospace;
  word-break: break-all;
}
.mt12 {
  margin-top: 12px;
}
code {
  font-size: 12px;
  background: var(--el-fill-color);
  padding: 1px 6px;
  border-radius: 4px;
}
</style>
