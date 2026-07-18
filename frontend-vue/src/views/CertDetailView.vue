<script setup lang="ts">
/**
 * 证书详情：左右字段表展示 RpkiCert 全部字段；冲突说明高亮；返回列表。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { fetchCertDetail } from '@/api/cert'
import type { RpkiCert } from '@/types/api'
import { syntheticCerFileName } from '@/utils/certDisplay'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const cert = ref<RpkiCert | null>(null)

const id = computed(() => Number(route.params.id))

const titleName = computed(() => {
  if (cert.value?.cerFileName && cert.value.cerFileName.trim().length > 0) {
    return cert.value.cerFileName.trim()
  }
  return syntheticCerFileName(id.value)
})

/** 将 JSON 字符串格式化为可读缩进；非 JSON 则原样返回 */
function prettyJson(raw: string | null | undefined): string {
  if (raw == null || raw === '') return '—'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** 详情页行：左中文名，右值 */
const rows = computed(() => {
  const c = cert.value
  if (!c) return []
  return [
    { k: 'id', v: c.id != null ? String(c.id) : '—' },
    { k: 'cert_name', v: c.certName ?? '—' },
    { k: 'cer_file_name（磁盘）', v: c.cerFileName ?? '—' },
    { k: 'serial_number', v: c.serialNumber ?? '—' },
    { k: 'issuer', v: c.issuer ?? '—' },
    { k: 'subject', v: c.subject ?? '—' },
    { k: 'rir', v: c.rir ?? '—' },
    { k: 'not_before', v: c.notBefore ?? '—' },
    { k: 'not_after', v: c.notAfter ?? '—' },
    { k: 'ipv4_prefixes', v: prettyJson(c.ipv4Prefixes), mono: true },
    { k: 'ipv6_prefixes', v: prettyJson(c.ipv6Prefixes), mono: true },
    { k: 'as_numbers', v: prettyJson(c.asNumbers), mono: true },
    { k: 'subject_key_id', v: c.subjectKeyId ?? '—' },
    { k: 'authority_key_id', v: c.authorityKeyId ?? '—' },
    { k: 'parent_cert_id', v: c.parentCertId != null ? String(c.parentCertId) : '—' },
    { k: 'revoked', v: c.revoked == null ? '—' : c.revoked ? '是' : '否' },
    { k: 'has_conflict', v: c.hasConflict == null ? '—' : c.hasConflict ? '是' : '否', danger: c.hasConflict === true },
    { k: 'conflict_details', v: c.conflictDetails ?? '—', danger: c.hasConflict === true && !!c.conflictDetails },
    { k: 'fabric_tx_id', v: c.fabricTxId ?? '—', mono: true },
    { k: 'fabric_block_num', v: c.fabricBlockNum ?? '—' },
    { k: 'is_sent_to_fabric', v: c.isSentToFabric == null ? '—' : c.isSentToFabric ? '是' : '否' },
    { k: 'fabric_send_failed', v: c.fabricSendFailed == null ? '—' : c.fabricSendFailed ? '是' : '否' },
    { k: 'fabric_last_error', v: c.fabricLastError ?? '—', warn: !!c.fabricSendFailed },
    { k: 'fabric_send_time', v: c.fabricSendTime ?? '—' },
    { k: 'cert_hash', v: c.certHash ?? '—', mono: true },
    {
      k: 'raw_cert_data',
      v: c.rawCertData
        ? c.rawCertData.length > 500
          ? c.rawCertData.slice(0, 500) + '\n…（已截断，完整数据共 ' + c.rawCertData.length + ' 字符）'
          : c.rawCertData
        : '—',
      mono: true,
    },
    { k: 'created_at', v: c.createdAt ?? '—' },
    { k: 'updated_at', v: c.updatedAt ?? '—' },
  ]
})

async function load() {
  if (!Number.isFinite(id.value) || id.value <= 0) {
    ElMessage.error('证书 ID 不合法')
    return
  }
  loading.value = true
  try {
    const { data: body } = await fetchCertDetail(id.value)
    if (!body.success) {
      ElMessage.error(body.message || '加载失败')
      cert.value = null
      return
    }
    cert.value = body.data.certificate
    if (!cert.value) ElMessage.warning('证书不存在')
  } catch {
    cert.value = null
  } finally {
    loading.value = false
  }
}

watch(
  () => route.params.id,
  () => load(),
  { immediate: true },
)
</script>

<template>
  <div class="page" v-loading="loading">
    <h2 class="title">证书详情 - {{ titleName }}</h2>

    <div v-if="cert" class="detail-table">
      <div v-for="(row, idx) in rows" :key="idx" class="detail-row">
        <div class="detail-label">{{ row.k }}</div>
        <div
          class="detail-value"
          :class="{
            mono: row.mono,
            'text-danger': row.danger,
            'text-warn': row.warn,
          }"
        >
          {{ row.v }}
        </div>
      </div>
    </div>

    <el-empty v-else-if="!loading" description="暂无数据" />

    <div class="footer-actions">
      <el-button type="primary" :icon="ArrowLeft" @click="router.push('/certs')">返回列表</el-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  width: 100%;
}
.title {
  margin: 0 0 20px;
  font-size: 1.25rem;
}
.detail-table {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
}
.detail-row {
  display: flex;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.detail-row:last-child {
  border-bottom: none;
}
.detail-label {
  width: 220px;
  flex-shrink: 0;
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  font-weight: 500;
  color: var(--el-text-color-secondary);
  box-sizing: border-box;
}
.detail-value {
  flex: 1;
  min-width: 0;
  padding: 10px 14px;
  word-break: break-word;
  white-space: pre-wrap;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.text-danger {
  color: var(--el-color-danger);
  font-weight: 500;
}
.text-warn {
  color: var(--el-color-warning);
}
.footer-actions {
  margin-top: 24px;
}
@media (max-width: 768px) {
  .detail-label {
    width: 100%;
    border-bottom: 1px dashed var(--el-border-color-lighter);
  }
}
</style>
