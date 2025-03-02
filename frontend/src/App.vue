<script setup lang="ts">
import { ref } from 'vue'
import CodeViewer from './components/CodeViewer.vue'

const codeContent = ref('')
const fileName = ref('')
const varInput = ref('')
const funcInput = ref('')
const highlightedLines = ref<number[]>([])
const isLoading = ref(false) // 加载状态
const uploadError = ref('') // 上传错误状态
const totalLineCount = ref(0);
const highlightedLineCount = ref(0);

const handleFileUpload = async (e: Event) => {
  const file = (e.target as HTMLInputElement).files?.[0]
  uploadError.value = '' // 重置错误信息

  if (!file) {
    alert('请选择文件')
    return
  }
  if (!file.name.endsWith('.c') && !file.name.endsWith('.cpp')) {
    alert('仅支持上传.c/.cpp文件')
    return
  }

  try {
    // 创建FormData对象
    const formData = new FormData()
    formData.append('file', file)

    // 发送上传请求
    const response = await fetch('http://localhost:8080/upload', {
      method: 'POST',
      body: formData
    })

    // 处理响应
    if (!response.ok) {
      const error = await response.text()
      throw new Error(`上传失败: ${error}`)
    }

    // 更新前端状态
    fileName.value = file.name
    codeContent.value = await file.text()
    highlightedLines.value = []

  } catch (error) {
    console.error('文件上传错误:', error)
    uploadError.value = error instanceof Error ? error.message : '未知错误'
    fileName.value = ''
    codeContent.value = ''
  }

  const content = await file.text();
  codeContent.value = content;
  totalLineCount.value = content.split(/\r?\n/).length;
  highlightedLines.value = [];
  highlightedLineCount.value = 0;
}

const analyzeCode = async () => {
  if (!fileName.value) {
    alert('请先上传文件')
    return
  }

  highlightedLines.value = []; // 清空旧高亮

  if (!varInput) {
    alert('请输入变量名')
    return
  }

  isLoading.value = true
  try {
    const response = await fetch(
      `http://localhost:8080/slice?filename=${fileName.value}&variable=${varInput.value}&function=${funcInput.value}`
    )

    if (!response.ok) {
      throw new Error(`请求失败: ${response.status} ${response.statusText}`)
    }

    const result = await response.json()
    highlightedLines.value = result.lines || []
    highlightedLineCount.value = highlightedLines.value.length;

    if (highlightedLines.value.length === 0) {
      alert('未找到匹配的影响域')
    }
  } catch (error) {
    console.error('分析错误:', error)
    alert(`分析失败: ${error instanceof Error ? error.message : '未知错误'}`)
  } finally {
    isLoading.value = false
  }
}
</script>

<template>
  <div class="container">
    <!-- 页面标题 -->
    <h1>软件变更影响域展示</h1>

    <!-- 添加错误提示 -->
    <div v-if="uploadError" class="error-message">
      ❌ {{ uploadError }}
    </div>

    <!-- 上传区域 -->
    <div class="upload-section">
      <div class="file-upload">
        <label>
          <input type="file" @change="handleFileUpload" accept=".c,.cpp" class="upload-input">
          📁 上传C/C++文件
        </label>
        <span v-if="fileName" class="file-name">{{ fileName }}</span>
      </div>

      <!-- 输入组 -->
      <div class="input-group">
        <input v-model="varInput" placeholder="变量名" :disabled="isLoading" class="var-input">
        <span class="at-symbol">@</span>
        <input v-model="funcInput" placeholder="函数名" :disabled="isLoading" class="func-input">
        <button @click="analyzeCode" :disabled="isLoading || !fileName" class="analyze-btn">
          {{ isLoading ? '分析中...' : '开始分析' }}
        </button>
      </div>
    </div>

    <div v-if="totalLineCount > 0" class="stats-info">
      影响域：{{ highlightedLineCount }} / {{ totalLineCount }} 行
    </div>

    <!-- 代码查看器 -->
    <CodeViewer v-if="codeContent" :code="codeContent" :highlighted-lines="highlightedLines" />
  </div>
</template>

<style scoped>
.container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 2rem;
  color: #e0e0e0;
}

h1 {
  text-align: center;
  color: #007acc;
  margin-bottom: 2rem;
}

.upload-section {
  background: #2d2d2d;
  padding: 1.5rem;
  border-radius: 8px;
  margin-bottom: 2rem;
}

.file-upload {
  margin-bottom: 1rem;
  display: flex;
  align-items: center;
  gap: 1rem;
}

.upload-input {
  display: none;
}

.upload-input+label {
  cursor: pointer;
  padding: 0.5rem 1rem;
  background: #007acc;
  border-radius: 4px;
  transition: background 0.3s;
}

.upload-input+label:hover {
  background: #0062a3;
}

.file-name {
  color: #888;
  font-size: 0.9em;
}

.input-group {
  display: flex;
  gap: 1rem;
  align-items: center;
}

input[type="text"] {
  flex: 1;
  padding: 0.8rem;
  background: #3c3c3c;
  border: 1px solid #454545;
  border-radius: 4px;
  color: white;
  min-width: 120px;
}

input[type="text"]:disabled {
  background: #2a2a2a;
  cursor: not-allowed;
}

.at-symbol {
  font-weight: bold;
  color: #888;
}

.analyze-btn {
  padding: 0.8rem 1.5rem;
  background: #007acc;
  border: none;
  border-radius: 4px;
  color: white;
  cursor: pointer;
  transition: background 0.3s;
}

.analyze-btn:disabled {
  background: #005999;
  cursor: not-allowed;
}

.analyze-btn:not(:disabled):hover {
  background: #0062a3;
}

.error-message {
  color: #ff4444;
  background: #2d2d2d;
  padding: 1rem;
  border-radius: 4px;
  margin-bottom: 1rem;
  border: 1px solid #ff4444;
}

.stats-info {
  margin: 10px 0;
  padding: 8px;
  background: #2d2d2d;
  border-radius: 4px;
  color: #87ceeb;
  font-family: monospace;
}
</style>