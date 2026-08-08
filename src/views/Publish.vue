<template>
  <div class="publish-page">
    <header class="publish-header">
      <button class="publish-header__back" type="button" @click="$router.go(-1)">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </button>
      <p class="publish-header__eyebrow">METRO COMMUNITY · {{ isEdit ? 'REVISE' : 'DRAFT' }}</p>
      <div class="publish-header__spacer" aria-hidden="true"></div>
    </header>

    <div class="publish-layout">
      <aside class="editor-meta" aria-label="文章信息">
        <div class="editor-meta__section editor-meta__status" :class="`is-${draftStatus.tone}`" aria-live="polite">
          <span class="editor-meta__status-dot" aria-hidden="true"></span>
          {{ draftStatus.label }}
        </div>

        <div class="editor-meta__section">
          <p class="editor-meta__label">文章封面</p>
          <el-upload
            class="cover-uploader"
            action="/api/file/upload"
            :show-file-list="false"
            :headers="uploadHeaders"
            :on-success="handleCoverSuccess"
            :before-upload="beforeCoverUpload"
          >
            <img v-if="form.cover" :src="form.cover" class="cover-uploader__image" alt="文章封面预览" />
            <span v-else class="cover-uploader__placeholder">
              <el-icon><Plus /></el-icon>
              添加封面
            </span>
          </el-upload>
          <el-button v-if="form.cover" class="cover-uploader__remove" type="primary" link @click="form.cover = ''">
            移除封面
          </el-button>
        </div>

        <div class="editor-meta__section">
          <label class="editor-meta__label" for="article-tags">文章标签</label>
          <el-select
            id="article-tags"
            v-model="form.tags"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入后按回车"
            class="editor-meta__tags"
          >
            <el-option v-for="item in hotTags" :key="item" :label="item" :value="item" />
          </el-select>
        </div>

        <div class="editor-meta__section editor-meta__reading">
          <div>
            <span class="editor-meta__metric-value">{{ wordCount }}</span>
            <span class="editor-meta__metric-label">字</span>
          </div>
          <div>
            <span class="editor-meta__metric-value">{{ readingMinutes }}</span>
            <span class="editor-meta__metric-label">分钟阅读</span>
          </div>
        </div>
      </aside>

      <main class="editor-canvas">
        <p class="editor-canvas__kicker">{{ isEdit ? '编辑已有文章' : '开始一篇新的记录' }}</p>
        <input
          v-model="form.title"
          class="title-input"
          type="text"
          maxlength="100"
          placeholder="给文章一个清晰的标题"
          aria-label="文章标题"
        />
        <RichArticleEditor
          v-model="form.content"
          @upload-image="uploadInlineImage"
          @word-count="wordCount = $event"
        />
      </main>
    </div>

    <footer class="footer-actions">
      <div class="footer-actions__content">
        <p class="footer-actions__tip">{{ isEdit ? '修改会在发布后更新文章内容' : '草稿会保存在你的账号中' }}</p>
        <div class="footer-actions__buttons">
          <el-button
            size="large"
            :loading="saving && !publishing"
            :disabled="saving || publishing"
            @click="handleSaveDraft"
          >
            保存草稿
          </el-button>
          <el-button type="primary" size="large" :loading="publishing" @click="handlePublish">
            {{ isEdit ? '更新发布' : '发布文章' }}
          </el-button>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import RichArticleEditor from '../components/RichArticleEditor.vue'
import request from '../utils/request'
import { getArticleForEdit, publishArticle, saveDraft } from '../api/article'
import { getHotTags } from '../api/tag'
import { nextDraftState } from '../utils/draftState'
import { createArticleSaveCoordinator } from '../utils/articleSaveCoordinator'

const AUTO_SAVE_DELAY = 1500

const route = useRoute()
const router = useRouter()
const isEdit = ref(false)
const wordCount = ref(0)
const hotTags = ref([])

const form = reactive({
  id: null,
  title: '',
  content: '',
  cover: '',
  tags: [],
})

const uploadHeaders = computed(() => {
  const token = localStorage.getItem('token')
  return token ? { token } : {}
})

const readingMinutes = computed(() => Math.max(1, Math.ceil(wordCount.value / 400)))

function hasRequiredContent() {
  return Boolean(form.title.trim() && form.content.trim())
}

function buildPayload() {
  return {
    id: form.id,
    title: form.title.trim(),
    content: form.content,
    cover: form.cover,
    tags: form.tags,
  }
}

const saveCoordinator = createArticleSaveCoordinator({
  autoSaveDelay: AUTO_SAVE_DELAY,
  hasRequiredContent,
  buildPayload,
  saveDraft,
  publish: (payload) => publishArticle({ ...payload, isPublish: true }),
  onDraftSaved: (response) => {
    if (response.data) form.id = response.data
  },
  onDraftFailed: () => ElMessage.error('草稿保存失败，请检查网络后重试'),
})

const saving = computed(() => saveCoordinator.state.saving)
const publishing = computed(() => saveCoordinator.state.publishing)
const draftStatus = computed(() => nextDraftState(
  saveCoordinator.state.dirty,
  saveCoordinator.state.saving,
  saveCoordinator.state.failed,
))

async function handleSaveDraft() {
  const saved = await saveCoordinator.saveCurrentDraft()
  if (saved) ElMessage.success('草稿已保存')
}

async function handlePublish() {
  if (!hasRequiredContent()) {
    ElMessage.warning('请先填写标题和正文')
    return
  }

  const published = await saveCoordinator.requestPublish()
  if (published) {
    ElMessage.success('文章已发布')
    await router.push('/home')
  }
}

async function uploadInlineImage(file, insertImage) {
  const body = new FormData()
  body.append('file', file)

  try {
    const response = await request.post('/api/file/upload', body)
    insertImage(response.data)
  } catch (error) {
    ElMessage.error('图片上传失败')
  }
}

function handleCoverSuccess(response) {
  if (response.code === 200) form.cover = response.data
}

function beforeCoverUpload(file) {
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  if (!allowedTypes.includes(file.type)) {
    ElMessage.error('仅支持 JPG、PNG、WEBP 或 GIF 图片')
    return false
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('图片最大 10MB')
    return false
  }
  return true
}

async function loadHotTags() {
  try {
    const response = await getHotTags()
    hotTags.value = response.data || []
  } catch (error) {
    // 标签是辅助信息，不阻断写作。
  }
}

async function loadArticle(id) {
  saveCoordinator.beginHydration()
  try {
    const response = await getArticleForEdit(id)
    if (saveCoordinator.state.disposed) return
    const article = response.data
    form.id = article.id
    form.title = article.title || ''
    form.content = article.content || ''
    form.cover = article.cover || ''
    form.tags = article.tagList || []
  } catch (error) {
    if (!saveCoordinator.state.disposed) ElMessage.error('加载文章失败')
  } finally {
    await saveCoordinator.completeHydration()
  }
}

watch(
  [() => form.title, () => form.content, () => form.cover, () => form.tags],
  () => {
    saveCoordinator.markChanged()
  },
  { deep: true },
)

onMounted(() => {
  loadHotTags()
  const id = route.query.id
  if (id) {
    isEdit.value = true
    loadArticle(id)
  }
})

onBeforeUnmount(saveCoordinator.dispose)
</script>

<style scoped lang="scss">
.publish-page {
  min-height: 100vh;
  padding-bottom: 104px;
  background: var(--paper);
  color: var(--ink);
}

.publish-header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  min-height: 68px;
  padding: 0 clamp(var(--space-4), 5vw, 72px);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--paper) 92%, transparent);
}

.publish-header__back {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  gap: var(--space-1);
  padding: var(--space-2) 0;
  border: 0;
  background: transparent;
  color: var(--ink-muted);
  font: inherit;
  cursor: pointer;

  &:hover,
  &:focus-visible {
    color: var(--accent-dark);
  }
}

.publish-header__eyebrow,
.editor-canvas__kicker,
.editor-meta__label {
  margin: 0;
  color: var(--ink-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.publish-layout {
  display: grid;
  grid-template-columns: minmax(188px, 240px) minmax(0, 820px);
  gap: clamp(var(--space-6), 6vw, 88px);
  width: min(1180px, calc(100% - 2 * clamp(var(--space-4), 5vw, 72px)));
  margin: clamp(var(--space-6), 8vw, 88px) auto;
}

.editor-meta {
  align-self: start;
  border-top: 2px solid var(--ink);
}

.editor-meta__section {
  padding: var(--space-5) 0;
  border-bottom: 1px solid var(--line);
}

.editor-meta__status {
  display: flex;
  align-items: center;
  min-height: 22px;
  gap: var(--space-2);
  color: var(--ink-muted);
  font-size: 14px;

  &.is-saving { color: var(--accent-dark); }
  &.is-error { color: #9a4038; }
  &.is-saved { color: #59745a; }
}

.editor-meta__status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.editor-meta__label {
  display: block;
  margin-bottom: var(--space-3);
}

.cover-uploader {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border: 1px dashed var(--line);
  border-radius: var(--radius-sm);
  background: var(--paper-muted);
  cursor: pointer;

  :deep(.el-upload) {
    display: block;
    width: 100%;
    height: 100%;
  }

  &:hover { border-color: var(--accent); }
}

.cover-uploader__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  width: 100%;
  height: 100%;
  color: var(--ink-muted);
  font-size: 13px;

  .el-icon { font-size: 24px; }
}

.cover-uploader__image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-uploader__remove { margin-top: var(--space-2); padding-inline: 0; }
.editor-meta__tags { width: 100%; }

.editor-meta__reading {
  display: flex;
  gap: var(--space-5);
}

.editor-meta__metric-value {
  margin-right: 4px;
  color: var(--ink);
  font-family: "Songti SC", STSong, SimSun, serif;
  font-size: 24px;
  font-weight: 700;
}

.editor-meta__metric-label { color: var(--ink-muted); font-size: 12px; }

.editor-canvas { min-width: 0; }
.editor-canvas__kicker { margin-bottom: var(--space-4); }

.title-input {
  box-sizing: border-box;
  width: 100%;
  margin-bottom: var(--space-6);
  padding: 0 0 var(--space-4);
  border: 0;
  border-bottom: 1px solid var(--line);
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-family: "Songti SC", STSong, SimSun, serif;
  font-size: clamp(34px, 5vw, 52px);
  font-weight: 700;
  line-height: 1.25;

  &::placeholder { color: color-mix(in srgb, var(--ink-muted) 60%, transparent); }
  &:focus { border-color: var(--accent); }
}

.footer-actions {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  border-top: 1px solid var(--line);
  background: color-mix(in srgb, var(--paper) 94%, transparent);
}

.footer-actions__content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  width: min(1180px, calc(100% - 2 * clamp(var(--space-4), 5vw, 72px)));
  min-height: 76px;
  margin: 0 auto;
}

.footer-actions__tip { margin: 0; color: var(--ink-muted); font-size: 13px; }
.footer-actions__buttons { display: flex; gap: var(--space-3); }

@media (max-width: 959px) {
  .publish-layout { grid-template-columns: minmax(0, 1fr); gap: var(--space-6); }
  .editor-meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: var(--space-5); }
  .editor-meta__status { grid-column: 1 / -1; }
  .editor-meta__section { min-width: 0; }
}

@media (max-width: 599px) {
  .publish-header { grid-template-columns: 1fr auto; }
  .publish-header__eyebrow { display: none; }
  .publish-header__spacer { display: none; }
  .editor-meta { grid-template-columns: 1fr; }
  .editor-meta__status { grid-column: auto; }
  .footer-actions__content { min-height: 68px; }
  .footer-actions__tip { display: none; }
  .footer-actions__buttons { width: 100%; }
  .footer-actions__buttons :deep(.el-button) { flex: 1; }
}
</style>
