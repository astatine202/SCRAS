<!-- frontend/src/components/CodeViewer.vue -->
<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  code: string
  highlightedLines: number[]
}>()

// 正确处理换行符
const lines = ref(props.code.split(/\r?\n/))

watch(() => props.code, (newCode) => {
  lines.value = newCode.split(/\r?\n/)
})
</script>

<template>
  <div class="code-wrapper">
    <div class="code-container">
      <div class="line-numbers">
        <div v-for="(_, index) in lines" :key="index" class="line-number">
          {{ index + 1 }}
        </div>
      </div>

      <div class="code-content">
        <div v-for="(line, index) in lines" :key="index" class="code-line"
          :class="{ highlighted: highlightedLines.includes(index + 1) }">
          {{ line || ' ' }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 外层滚动容器 */
.code-wrapper {
  border: 1px solid #3c3c3c;
  border-radius: 4px;
  overflow: auto;
  max-height: 60vh;
  background: #1e1e1e;
  margin-top: 1rem;
}

/* 代码容器布局 */
.code-container {
  display: flex;
  min-width: fit-content;
  font-family: 'Consolas', monospace;
  font-size: 14px;
  justify-content: flex-start;
}

/* 行号列样式 */
.line-numbers {
  padding: 10px 15px;
  background: #252526;
  color: #858585;
  text-align: right;
  user-select: none;
  position: sticky;
  left: 0;
  z-index: 1;
  text-align: right;
}

/* 代码内容区域 */
.code-content {
  flex: 1;
  padding: 10px 0 10px 15px;
  min-width: 800px;
  text-align: left;
}

/* 单行代码样式 */
.code-line {
  white-space: pre-wrap;
  line-height: 1.5;
  padding: 0 15px 0 8px;
  transition: background 0.3s;
}

/* 高亮样式 */
.code-line.highlighted {
  background: #264f78;
  box-shadow: inset 3px 0 0 #007acc;
}
</style>