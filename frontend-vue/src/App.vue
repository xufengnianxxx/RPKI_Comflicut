<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import { computed } from 'vue'

const route = useRoute()
const active = computed(() => route.path)
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header" height="56px">
      <div class="brand">RPKI 证书冲突校验</div>
      <el-menu
        :default-active="active"
        mode="horizontal"
        router
        class="top-menu"
        :ellipsis="false"
      >
        <el-menu-item index="/certs">证书列表</el-menu-item>
        <el-menu-item index="/conflict-detect">冲突检测</el-menu-item>
        <el-menu-item index="/detection-records">检测记录</el-menu-item>
      </el-menu>
    </el-header>
    <el-main class="app-main">
      <RouterView v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </RouterView>
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  flex-direction: column;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 0 16px;
  flex-wrap: wrap;
  height: auto !important;
  min-height: 56px;
}
.brand {
  font-weight: 600;
  font-size: 1.05rem;
  white-space: nowrap;
}
.top-menu {
  flex: 1;
  border-bottom: none !important;
  min-width: 0;
}
.app-main {
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding: 16px !important;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>

<style>
html,
body {
  margin: 0;
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'PingFang SC', sans-serif;
}
</style>
