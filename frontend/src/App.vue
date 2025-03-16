<script setup lang="ts">
import { ref, onMounted } from 'vue'
import CodeViewer from './components/CodeViewer.vue'

const BASE_URL = 'http://localhost:8080'
const codeContent = ref('')
const fileName = ref('')
const projectName = ref('')
const varInput = ref('')
const funcInput = ref('')
const isLoading = ref(false) // 加载状态
const uploadError = ref('') // 上传错误状态
const highlightedLines = ref<number[]>([]) // 高亮行
const highlightedLineCount = ref(0) // 影响域行数
const totalLineCount = ref(0) // 总行数
const allHighlightedLineCount = ref(0) // 所有文件影响域行数
const allTotalLineCount = ref(0) // 所有文件总行数
const fileTree = ref<any[]>([]) // 文件目录结构
const selectedFile = ref('') // 选定的文件
const projectResult = ref<any>(null) // 项目分析结果

const handleFileUpload = async (e: Event) => {
  await cleanTempDirectories();

  const file = (e.target as HTMLInputElement).files?.[0];
  uploadError.value = ''; // 重置错误信息

  if (!file) {
    alert('请选择文件');
    return;
  }
  if (!file.name.endsWith('.c') &&
    !file.name.endsWith('.cpp')) {
    alert('仅支持上传.c/.cpp文件');
    return;
  }

  try {
    // 创建FormData对象
    const formData = new FormData();
    formData.append('file', file);

    // 发送上传请求
    const response = await fetch(`${BASE_URL}/upload`, {
      method: 'POST',
      body: formData
    })

    // 处理响应
    if (!response.ok) {
      const error = await response.text();
      throw new Error(`上传失败: ${error}`);
    }
  } catch (error) {
    console.error('文件上传错误:', error);
    uploadError.value = error instanceof Error ? error.message : '未知错误';
    fileName.value = '';
    codeContent.value = '';
  }

  fileName.value = file.name;
  projectName.value = '';
  varInput.value = '';
  funcInput.value = '';
  uploadError.value = '';
  const content = await file.text();
  codeContent.value = content;
  totalLineCount.value = content.split(/\r?\n/).length;
  highlightedLines.value = [];
  highlightedLineCount.value = 0;
  allHighlightedLineCount.value = 0;
  allTotalLineCount.value = totalLineCount.value;
  fileTree.value = [];
  selectedFile.value = '';
  projectResult.value = null;
}

const handleProjectUpload = async (e: Event) => {
  await cleanTempDirectories();

  const files = (e.target as HTMLInputElement).files;
  if (!files || files.length === 0) {
    alert('请选择文件夹');
    return;
  }

  projectName.value = files[0].webkitRelativePath.split('/')[0];
  const formData = new FormData();
  for (const file of files) {
    formData.append('files', file, file.webkitRelativePath);
  }

  try {
    const response = await fetch(`${BASE_URL}/uploadProject`, {
      method: 'POST',
      body: formData
    })

    if (!response.ok) {
      const error = await response.text();
      throw new Error(`上传失败: ${error}`);
    }

    // 获取文件目录结构
    const result = await response.json();
    fileTree.value = result.fileTree;
    allTotalLineCount.value = result.allTotalLineCount;
  } catch (error) {
    allTotalLineCount.value = 0;
    console.error('文件夹上传错误:', error);
    uploadError.value = error instanceof Error ? error.message : '未知错误';
  }
  fileName.value = '';
  varInput.value = '';
  funcInput.value = '';
  uploadError.value = '';
  codeContent.value = '';
  highlightedLines.value = [];
  highlightedLineCount.value = 0;
  allHighlightedLineCount.value = 0;
  selectedFile.value = '';
  projectResult.value = null;
}

const analyze = async () => {
  if (projectName.value) {
    await analyzeProject();
  } else {
    await analyzeCode();
  }
}

const analyzeCode = async () => {
  if (!fileName.value) {
    alert('请先上传文件');
    return;
  }

  highlightedLines.value = []; // 清空旧高亮

  if (!varInput) {
    alert('请输入变量名');
    return;
  }

  isLoading.value = true
  try {
    const response = await fetch(
      `${BASE_URL}/slice?filename=${fileName.value}&variable=${varInput.value}&function=${funcInput.value}`
    );

    if (!response.ok) {
      throw new Error(`请求失败: ${response.status} ${response.statusText}`);
    }

    const result = await response.json();
    highlightedLines.value = result.lines || [];
    highlightedLineCount.value = highlightedLines.value.length;

    if (highlightedLines.value.length === 0) {
      alert('未找到匹配的影响域');
    }
  } catch (error) {
    console.error('分析错误:', error);
    alert(`分析失败: ${error instanceof Error ? error.message : '未知错误'}`);
  } finally {
    isLoading.value = false;
  }
}

const analyzeProject = async () => {
  if (!fileName.value) {
    alert('请先上传文件夹并选择文件');
    return;
  }

  highlightedLines.value = []; // 清空旧高亮

  if (!varInput.value) {
    alert('请输入变量名');
    return;
  }

  isLoading.value = true;
  try {
    const response = await fetch(
      `${BASE_URL}/sliceProject?projectName=${projectName.value}&variable=${varInput.value}&function=${funcInput.value}&filename=${selectedFile.value.replace(/\\/g, '/')}`
    );

    if (!response.ok) {
      throw new Error(`请求失败: ${response.status} ${response.statusText}`);
    }

    projectResult.value = await response.json();
    highlightedLines.value = projectResult.value.lines[selectedFile.value.replace(/\\/g, '/')] || [];
    highlightedLineCount.value = highlightedLines.value.length;
    allHighlightedLineCount.value = Object.values(projectResult.value.lines || {}).reduce(
      (sum: number, lines: any) => sum + (Array.isArray(lines) ? lines.length : 0), 0);

    if (highlightedLines.value.length === 0) {
      alert('未找到匹配的影响域');
    }
  } catch (error) {
    console.error('分析错误:', error);
    alert(`分析失败: ${error instanceof Error ? error.message : '未知错误'}`);
  } finally {
    isLoading.value = false;
  }
}

const selectFile = async (path: string) => {
  selectedFile.value = path;

  // 检查文件是否为.c文件
  if (!path.endsWith('.c')) {
    codeContent.value = '// 请选择一个.c文件';
    fileName.value = '';
    totalLineCount.value = 0;
    highlightedLines.value = [];
    highlightedLineCount.value = 0;
    return;
  }

  try {
    const response = await fetch(`${BASE_URL}/getFileContent?path=${encodeURIComponent(projectName.value + '/' + path)}`);
    if (!response.ok) {
      throw new Error(`无法加载文件内容: ${response.statusText}`);
    }

    const content = await response.text();
    codeContent.value = content;
    fileName.value = path;
    totalLineCount.value = content.split(/\r?\n/).length;
    if (projectResult.value === null) {
      highlightedLines.value = [];
      highlightedLineCount.value = 0;
      allHighlightedLineCount.value = 0;
    } else {
      highlightedLines.value = projectResult.value.lines[selectedFile.value.replace(/\\/g, '/')] || [];
      highlightedLineCount.value = highlightedLines.value.length;
      allHighlightedLineCount.value = Object.values(projectResult.value.lines || {}).reduce(
        (sum: number, lines: any) => sum + (Array.isArray(lines) ? lines.length : 0), 0);
    }
  } catch (error) {
    console.error('加载文件内容错误:', error);
    codeContent.value = '// 加载文件内容失败';
    fileName.value = '';
    totalLineCount.value = 0;
    highlightedLines.value = [];
    highlightedLineCount.value = 0;
    allHighlightedLineCount.value = 0;
  }
}

// 页面加载时清理临时目录
const cleanTempDirectories = async () => {
  try {
    await fetch(`${BASE_URL}/cleanTempDirectories`);
  } catch (error) {
    console.error('清理临时目录失败:', error);
  }
}

onMounted(() => {
  cleanTempDirectories();
})
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
      <!-- 文件上传按钮 -->
      <div class="file-upload">
        <label>
          <input type="file" @change="handleFileUpload" accept=".c" class="upload-input">
          📁 上传C文件
        </label>
        <span v-if="fileName" class="info">{{ fileName.replace(/\\/g, '/') }}</span>
        <span v-if="totalLineCount > 0" class="stats-info">影响域：{{ highlightedLineCount }} / {{ totalLineCount }}
          行</span>
      </div>

      <!-- 文件夹上传按钮 -->
      <div class="file-upload">
        <label>
          <input type="file" @change="handleProjectUpload" webkitdirectory directory class="upload-input">
          📁 上传文件夹
        </label>
        <span v-if="projectName" class="info">{{ projectName }}</span>
        <span v-if="fileTree.length > 0" class="stats-info">全局影响域：{{ allHighlightedLineCount }} / {{ allTotalLineCount
        }} 行</span>
      </div>

      <!-- 输入组 -->
      <div class="input-group">
        <input v-model="varInput" placeholder="变量名" :disabled="isLoading" class="var-input">
        <span class="at-symbol">@</span>
        <input v-model="funcInput" placeholder="函数名" :disabled="isLoading" class="func-input">
        <button @click="analyze" :disabled="isLoading || !fileName" class="analyze-btn">
          {{ isLoading ? '分析中...' : '开始分析' }}
        </button>
        <span v-if="varInput" class="info">{{ varInput }} @ {{ funcInput }}</span>
      </div>
    </div>

    <div class="main-content">
      <!-- 文件目录结构 -->
      <div class="file-tree-wrapper" v-if="fileTree.length > 0">
        <div class="file-tree">
          <ul>
            <li v-for="file in fileTree" :key="file.path" @click="selectFile(file.path)"
              :class="{ 'selected': selectedFile === file.path }">
              {{ file.path.replace(/\\/g, '/') }} <!-- 显示完整相对路径 -->
            </li>
          </ul>
        </div>
      </div>

      <!-- 代码查看器 -->
      <CodeViewer v-if="codeContent" :code="codeContent" :highlighted-lines="highlightedLines" />
    </div>
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

.info {
  color: #888;
  font-size: 0.9em;
}

.stats-info {
  color: #87ceeb;
  font-family: monospace;
  margin-left: auto;
}

.input-group {
  display: flex;
  gap: 1rem;
  align-items: center;
}

input[type="text"],
input[type="number"] {
  flex: 1;
  padding: 0.8rem;
  background: #3c3c3c;
  border: 1px solid #454545;
  border-radius: 4px;
  color: white;
  min-width: 120px;
}

input[type="text"]:disabled,
input[type="number"]:disabled {
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

.main-content {
  display: flex;
  gap: 2rem;
  height: 70vh;
}

.file-tree-wrapper {
  flex: 0 0 300px;
  min-width: 300px;
  overflow: auto;
}

.file-tree {
  background: #2d2d2d;
  padding: 1rem;
  border-radius: 8px;
  height: 100%;
}

.file-tree ul {
  list-style: none;
  padding: 0;
}

.file-tree li {
  cursor: pointer;
  padding: 0.5rem;
  border-radius: 4px;
  transition: background 0.3s;
}

.file-tree li:hover {
  background: #3c3c3c;
}

.file-tree li.selected {
  background: #3c3c3c;
  color: #007acc;
}
</style>