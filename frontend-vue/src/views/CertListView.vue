<script setup lang="ts">
/**
 * 证书列表：分页 15、合成 .cer 文件名、关键字搜索（id / cert_hash）、跳转详情。
 */
import { ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { fetchCertPage } from '@/api/cert'
import type { RpkiCert } from '@/types/api'
import { syntheticCerFileName, shortCertHash } from '@/utils/certDisplay'

const router = useRouter()
const loading = ref(false)
const tableData = ref<RpkiCert[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(15)
const keyword = ref('')

async function load() {
  loading.value = true
  try {
    const { data: body } = await fetchCertPage({
      page: currentPage.value,
      size: pageSize.value,
      keyword: keyword.value.trim() || undefined,
    })
    if (!body.success) {
      ElMessage.error(body.message || '加载失败')
      return
    }
    const page = body.data
    tableData.value = page.records ?? []
    total.value = page.total ?? 0
  } catch {
    /* axios 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function goDetail(row: RpkiCert) {
  if (row.id != null) router.push(`/certs/${row.id}`)
}

function displayCerName(row: RpkiCert): string {
  if (row.cerFileName && row.cerFileName.trim().length > 0) {
    return row.cerFileName.trim()
  }
  return row.id != null ? syntheticCerFileName(row.id) : '—'
}

function onSearch() {
  currentPage.value = 1
  load()
}

watch([currentPage, pageSize], () => {
  load()
})

onMounted(() => {
  load()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2 class="title">证书列表</h2>
      <div class="toolbar-right">
        <el-input
          v-model="keyword"
          clearable
          placeholder="按证书 ID 或 cert_hash 模糊搜索"
          class="search-input"
          @keyup.enter="onSearch"
        />
        <el-button type="primary" :icon="Search" @click="onSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="tableData"
      stripe
      border
      style="width: 100%"
      :header-cell-style="{ whiteSpace: 'nowrap' }"
    >
      <el-table-column prop="id" label="ID" width="72" fixed />
      <el-table-column label="证书文件名" min-width="140">
        <template #default="{ row }">
          <el-link type="primary" @click="goDetail(row)">
            {{ displayCerName(row) }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="cert_hash" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ shortCertHash(row.certHash) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="rir" label="RIR" width="90" />
      <el-table-column prop="notBefore" label="not_before" min-width="110" />
      <el-table-column prop="notAfter" label="not_after" min-width="110" />
      <el-table-column label="has_conflict" width="110" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.hasConflict === true" type="danger" effect="dark">有冲突</el-tag>
          <el-tag v-else-if="row.hasConflict === false" type="success">无冲突</el-tag>
          <el-tag v-else type="info">未知</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="is_sent_to_fabric" width="130" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.isSentToFabric === true" type="success">是</el-tag>
          <el-tag v-else type="info">否</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-wrap">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[15, 30, 50]"
        layout="total, prev, pager, next, jumper, sizes"
        background
        @size-change="() => (currentPage = 1)"
      />
    </div>
  </div>
</template>

<style scoped>
.page {
  width: 100%;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.title {
  margin: 0;
  font-size: 1.25rem;
}
.toolbar-right {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.search-input {
  width: min(320px, 100%);
}
.pager-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
</style>
