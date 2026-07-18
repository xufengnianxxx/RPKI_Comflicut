<script setup lang="ts">
/**
 * 双证检测流水：每次「冲突检测」页完成一次检测后写入，含无冲突记录。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchPairDetectionRecords } from '@/api/cert'
import type { PairDetectionRecord } from '@/types/api'

const loading = ref(false)
const records = ref<PairDetectionRecord[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(15)

async function load() {
  loading.value = true
  try {
    const res = await fetchPairDetectionRecords({ page: page.value, size: size.value })
    if (!res.data.success || !res.data.data) {
      ElMessage.error(res.data.message || '加载失败')
      return
    }
    records.value = res.data.data.records ?? []
    total.value = res.data.data.total ?? 0
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onSizeChange(s: number) {
  size.value = s
  page.value = 1
  load()
}

onMounted(() => {
  load()
})
</script>

<template>
  <div class="page">
    <h2 class="title">检测记录</h2>
    <p class="hint">
      在「冲突检测」中每次点击「开始检测」并成功完成链下检测后，会在此追加一条记录：包含两个证书
      ID、链下是否冲突及摘要；若执行了链上检测，会附带链上结论摘要。
    </p>

    <el-table v-loading="loading" :data="records" border stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="72" />
      <el-table-column label="证书 A" width="88">
        <template #default="{ row }">
          <router-link :to="`/certs/${row.certIdA}`" class="link">{{ row.certIdA }}</router-link>
        </template>
      </el-table-column>
      <el-table-column label="证书 B" width="88">
        <template #default="{ row }">
          <router-link :to="`/certs/${row.certIdB}`" class="link">{{ row.certIdB }}</router-link>
        </template>
      </el-table-column>
      <el-table-column label="链下" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.offlineHasConflict" type="danger" size="small">有冲突</el-tag>
          <el-tag v-else type="success" size="small">无冲突</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="offlineSummary" label="链下说明" min-width="200" show-overflow-tooltip />
      <el-table-column prop="chainVerdict" label="链上 verdict" width="130" show-overflow-tooltip />
      <el-table-column prop="chainPrimaryType" label="链上主类型" width="130" show-overflow-tooltip />
      <el-table-column prop="chainSummary" label="链上说明" min-width="180" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="检测时间" width="170" />
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 15, 30, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
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
  margin-bottom: 16px;
}
.pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.link {
  color: var(--el-color-primary);
  text-decoration: none;
}
.link:hover {
  text-decoration: underline;
}
</style>
