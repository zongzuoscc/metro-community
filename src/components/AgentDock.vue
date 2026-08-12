<template>
  <div
    v-if="authenticated"
    data-test="agent-dock"
    class="agent-dock"
    :class="{ 'is-open': open, 'is-fullscreen': fullscreen }"
    :style="dockStyle"
  >
    <aside v-if="open" data-test="agent-window" class="agent-window" aria-label="Metro Agent 对话小窗">
      <header
        data-test="agent-drag-handle"
        class="agent-window__header"
        @pointerdown="startDrag"
      >
        <div class="agent-window__identity">
          <strong>Metro Agent</strong>
          <span>{{ memoryCenterOpen ? '记忆都由你决定' : contextLabel }}</span>
        </div>
        <div class="agent-window__controls">
          <button
            v-if="!temporaryEnabled"
            data-test="memory-center-toggle"
            type="button"
            :aria-label="memoryCenterOpen ? '返回 Agent 对话' : '打开长期记忆中心'"
            @pointerdown.stop
            @click.stop="memoryCenterOpen = !memoryCenterOpen"
          >
            {{ memoryCenterOpen ? '问' : '忆' }}
          </button>
          <button
            data-test="agent-expand"
            type="button"
            :aria-label="fullscreen ? '还原小窗' : '放大到全屏'"
            @pointerdown.stop
            @click.stop="fullscreen = !fullscreen"
          >
            {{ fullscreen ? '↘' : '↗' }}
          </button>
          <button type="button" aria-label="关闭 Agent 小窗" @pointerdown.stop @click.stop="open = false">×</button>
        </div>
      </header>

      <AgentMemoryCenter v-if="memoryCenterOpen" data-test="memory-center" />

      <section v-else-if="pageContext.kind !== 'general'" class="agent-context">
        <div class="agent-context__title">
          <span>{{ pageContext.kind === 'article' ? '当前文章' : '当前草稿' }}</span>
          <strong>{{ pageContext.title || '未命名内容' }}</strong>
        </div>
        <div class="agent-context__actions">
          <template v-if="pageContext.kind === 'article'">
            <button data-test="summarize-article" type="button" :disabled="taskLoading" @click="runArticleSummary">
              总结全文
            </button>
            <button type="button" :disabled="taskLoading" @click="runArticleAnalysis('CORE')">核心观点</button>
            <button type="button" :disabled="taskLoading" @click="runArticleAnalysis('CONTROVERSY')">争议点</button>
          </template>
          <template v-else-if="pageContext.kind === 'writing'">
            <button data-test="polish-writing" type="button" :disabled="taskLoading" @click="runWritingAction('POLISH')">
              润色
            </button>
            <button type="button" :disabled="taskLoading" @click="runWritingAction('SHORTEN')">缩短</button>
            <button type="button" :disabled="taskLoading" @click="runWritingAction('EXPAND')">扩写</button>
          </template>
        </div>
      </section>

      <section v-if="!memoryCenterOpen" ref="conversation" class="agent-conversation" aria-live="polite">
        <div v-if="messages.length === 0 && !suggestion" class="agent-empty">
          <span class="agent-empty__seal">问</span>
          <h2>{{ emptyTitle }}</h2>
          <p>{{ emptyDescription }}</p>
        </div>
        <article v-for="message in messages" :key="message.id" class="agent-message" :class="`is-${message.role}`">
          <small>{{ message.role === 'user' ? '你' : 'Metro Agent' }}</small>
          <p>{{ message.content }}</p>
        </article>
        <div v-if="taskLoading" class="agent-loading"><i></i><i></i><i></i><span>{{ taskStatus }}</span></div>

        <article v-if="suggestion" class="agent-suggestion">
          <header>
            <strong>修改建议</strong>
            <span>已生成，等待你确认</span>
          </header>
          <div class="agent-suggestion__before">
            <small>原文</small>
            <p>{{ suggestion.originalText }}</p>
          </div>
          <div class="agent-suggestion__after">
            <small>建议</small>
            <p>{{ suggestion.suggestedText }}</p>
          </div>
          <div class="agent-suggestion__actions">
            <button data-test="apply-suggestion" type="button" class="is-primary" @click="applySuggestion">应用到编辑器</button>
            <button data-test="reject-suggestion" type="button" @click="suggestion = null">拒绝</button>
          </div>
          <p class="agent-suggestion__notice">不会自动改写或发布。内容在生成后若已变更，建议将拒绝应用。</p>
        </article>
      </section>

      <footer v-if="!memoryCenterOpen" class="agent-composer">
        <div class="agent-composer__meta">
          <span>{{ temporaryEnabled ? '临时对话，不保存历史' : '普通对话' }}</span>
          <button data-test="temporary-toggle" type="button" :disabled="sessionLoading" @click="toggleTemporaryMode">
            {{ temporaryEnabled ? '退出临时' : '开启临时' }}
          </button>
        </div>
        <div class="agent-composer__box">
          <textarea
            v-model="draft"
            rows="2"
            maxlength="4000"
            :disabled="taskLoading"
            placeholder="向 Metro Agent 提问…"
            @keydown.enter.exact.prevent="sendChat"
          ></textarea>
          <button type="button" :disabled="!draft.trim() || taskLoading" aria-label="发送" @click="sendChat">↑</button>
        </div>
        <p class="agent-funding">{{ fundingLabel }}</p>
      </footer>
    </aside>

    <button
      data-test="agent-pet"
      type="button"
      class="agent-pet"
      :aria-label="open ? '收起 Metro Agent' : '打开 Metro Agent'"
      @click="open = !open"
    >
      <img class="agent-pet__figure" :src="metroPet" alt="" aria-hidden="true" />
      <span class="agent-pet__name">Metro</span>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AgentMemoryCenter from './AgentMemoryCenter.vue'
import {
  createAgentTurn,
  createTemporarySession,
  createWritingSuggestion,
  analyzeArticle,
  deleteTemporarySession,
  streamAgentTurnEvents,
  summarizeArticle,
} from '../api/agent'
import { clearAgentPageContext, useAgentPageContext } from '../composables/useAgentPageContext'
import metroPet from '../assets/agent/metro-pet.png'

const pageContext = useAgentPageContext()
const authenticated = ref(Boolean(localStorage.getItem('token')))
const open = ref(false)
const fullscreen = ref(false)
const temporaryEnabled = ref(false)
const temporarySession = ref(null)
const sessionLoading = ref(false)
const draft = ref('')
const messages = ref([])
const taskLoading = ref(false)
const taskStatus = ref('正在思考')
const suggestion = ref(null)
const memoryCenterOpen = ref(false)
const funding = ref({ fundingSource: 'PLATFORM', provider: null, model: null })
const conversation = ref(null)
const position = ref({ right: 24, bottom: 18 })
let drag = null
let streamController = null
let authenticationEpoch = 0

/**
 * 清除只属于当前账号的内存状态，并使所有正在等待的旧请求失效。
 * 这里不能尝试用新账号令牌删除旧账号的临时会话；后端会按绝对期限自动清理。
 */
function resetPrivateState() {
  authenticationEpoch += 1
  streamController?.abort()
  streamController = null
  messages.value = []
  suggestion.value = null
  memoryCenterOpen.value = false
  temporaryEnabled.value = false
  temporarySession.value = null
  draft.value = ''
  taskLoading.value = false
  sessionLoading.value = false
  funding.value = { fundingSource: 'PLATFORM', provider: null, model: null }
  open.value = false
  fullscreen.value = false
  clearAgentPageContext()
}

/** 登录、退出或跨标签令牌变化时，先清理旧账号状态，再刷新桌宠可见性。 */
function syncAuthentication() {
  resetPrivateState()
  authenticated.value = Boolean(localStorage.getItem('token'))
}

/** 浏览器原生 storage 事件负责同步另一个标签页发生的登录和退出。 */
function handleAuthenticationStorage(event) {
  if (event.key === 'token' || event.key === null) syncAuthentication()
}

const dockStyle = computed(() => fullscreen.value
  ? undefined
  : { right: `${position.value.right}px`, bottom: `${position.value.bottom}px` })
const contextLabel = computed(() => {
  if (pageContext.value.kind === 'article') return '正在陪你阅读'
  if (pageContext.value.kind === 'writing') return '修改前会等你确认'
  return '随时可以开始对话'
})
const emptyTitle = computed(() => pageContext.value.kind === 'article'
  ? '需要我陪你读这篇文章吗？'
  : pageContext.value.kind === 'writing'
    ? '选中文字后，我可以先给出建议'
    : '今天想了解什么？')
const emptyDescription = computed(() => pageContext.value.kind === 'writing'
  ? '润色、缩短和扩写都不会直接改动草稿。'
  : '你可以直接提问，也可以使用当前页面的快捷能力。')
const fundingLabel = computed(() => funding.value.fundingSource === 'USER'
  ? `本次使用你的 ${funding.value.provider || '自定义'} API${funding.value.model ? ` · ${funding.value.model}` : ''}`
  : '本次由平台基础额度提供')

/** 将最新内容滚动到小窗可见区，不抢占输入框焦点。 */
async function scrollToLatest() {
  await nextTick()
  if (conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight
}

/** 文章分析的所有快捷项都走服务端发布指针，不把浏览器中的正文当作可信输入。 */
async function runArticleAnalysis(operation) {
  if (!pageContext.value.articleId || taskLoading.value) return
  const articleId = pageContext.value.articleId
  taskLoading.value = true
  taskStatus.value = operation === 'CORE' ? '正在提炼核心观点' : '正在分析争议点'
  const requestEpoch = authenticationEpoch
  try {
    const result = await analyzeArticle(articleId, operation)
    if (requestEpoch !== authenticationEpoch || pageContext.value.articleId !== articleId) return
    funding.value = result
    messages.value.push({ id: `analysis-${operation}-${Date.now()}`, role: 'assistant', content: result.content })
    await scrollToLatest()
  } catch {
    if (requestEpoch === authenticationEpoch) ElMessage.error('文章分析失败，请稍后重试')
  } finally {
    if (requestEpoch === authenticationEpoch) taskLoading.value = false
  }
}

/** 文章正文由后端按发布指针加载，前端只传 ID，避免总结到被篡改的 DOM 内容。 */
async function runArticleSummary() {
  if (!pageContext.value.articleId || taskLoading.value) return
  const articleId = pageContext.value.articleId
  taskLoading.value = true
  taskStatus.value = '正在阅读当前文章'
  const requestEpoch = authenticationEpoch
  try {
    const result = await summarizeArticle(articleId)
    if (requestEpoch !== authenticationEpoch || pageContext.value.articleId !== articleId) return
    funding.value = result
    messages.value.push({ id: `summary-${Date.now()}`, role: 'assistant', content: result.content })
    await scrollToLatest()
  } catch {
    if (requestEpoch === authenticationEpoch) ElMessage.error('文章总结失败，请稍后重试')
  } finally {
    if (requestEpoch === authenticationEpoch) taskLoading.value = false
  }
}

/** 生成写作建议时携带选区和文档版本，为后续确认应用提供乐观锁。 */
async function runWritingAction(operation) {
  if (taskLoading.value || typeof pageContext.value.getWritingSnapshot !== 'function') return
  const snapshot = pageContext.value.getWritingSnapshot()
  const documentKey = pageContext.value.documentKey
  const requestEpoch = authenticationEpoch
  taskLoading.value = true
  taskStatus.value = '正在生成可撤销的写作建议'
  suggestion.value = null
  try {
    const result = await createWritingSuggestion({ operation, ...snapshot })
    if (requestEpoch !== authenticationEpoch || pageContext.value.documentKey !== documentKey) return
    funding.value = result
    suggestion.value = { ...result, documentKey }
    await scrollToLatest()
  } catch {
    if (requestEpoch === authenticationEpoch) ElMessage.error('写作建议生成失败，草稿未被修改')
  } finally {
    if (requestEpoch === authenticationEpoch) taskLoading.value = false
  }
}

/** 最终应用由编辑器再次校验版本和选区，迟到建议绝不覆盖新输入。 */
function applySuggestion() {
  if (!suggestion.value || typeof pageContext.value.applyWritingSuggestion !== 'function') return
  if (suggestion.value.documentKey !== pageContext.value.documentKey) {
    suggestion.value = null
    ElMessage.warning('已经切换文章，请重新生成建议')
    return
  }
  const applied = pageContext.value.applyWritingSuggestion(suggestion.value)
  if (applied) {
    suggestion.value = null
    ElMessage.success('已应用到编辑器，仍可使用撤销')
  } else {
    ElMessage.warning('草稿已发生变化，请重新生成建议')
  }
}

/** 自由对话沿用既有 turn/SSE 契约，并把页面类型与文章 ID 显式交给后端。 */
async function sendChat() {
  const question = draft.value.trim()
  if (!question || taskLoading.value) return
  taskLoading.value = true
  taskStatus.value = '正在检索社区内容'
  draft.value = ''
  messages.value.push({ id: `user-${Date.now()}`, role: 'user', content: question })
  const requestEpoch = authenticationEpoch
  try {
    const admission = await createAgentTurn({
      clientRequestId: crypto.randomUUID(),
      message: question,
      temporary: temporaryEnabled.value,
      temporarySessionId: temporaryEnabled.value ? temporarySession.value?.sessionId : undefined,
      context: {
        page: pageContext.value.kind,
        articleId: pageContext.value.articleId,
      },
    })
    // admission 返回前账号可能已切换；此时不能用新账号令牌继续订阅旧账号的 turn。
    if (requestEpoch !== authenticationEpoch) return
    streamController?.abort()
    streamController = new AbortController()
    await streamAgentTurnEvents(admission.turnId, {
      signal: streamController.signal,
      onEvent: event => {
        if (requestEpoch !== authenticationEpoch) return
        const payload = event.data?.payload || {}
        if (event.type === 'retrieving') taskStatus.value = '正在检索社区内容'
        if (event.type === 'generating') taskStatus.value = '正在组织回答'
        if (event.type === 'done') {
          messages.value.push({ id: `assistant-${admission.turnId}`, role: 'assistant', content: payload.finalMessage })
          if (payload.fundingSource) funding.value = payload
        }
      },
    })
    await scrollToLatest()
  } catch {
    if (requestEpoch === authenticationEpoch) ElMessage.error('消息发送失败，请检查网络后重试')
  } finally {
    if (requestEpoch === authenticationEpoch) taskLoading.value = false
  }
}

/**
 * 临时模式必须先由后端创建隔离 session，退出时也必须等后端确认删除。
 * 这样界面状态不会与真实隐私边界脱节。
 */
async function toggleTemporaryMode() {
  if (taskLoading.value || sessionLoading.value) return
  const requestEpoch = authenticationEpoch
  sessionLoading.value = true
  try {
    if (temporaryEnabled.value) {
      await deleteTemporarySession()
      if (requestEpoch !== authenticationEpoch) return
      temporaryEnabled.value = false
      temporarySession.value = null
      messages.value = []
      ElMessage.success('临时对话已清除')
    } else {
      // 临时会话不允许展示长期记忆，进入前先关闭记忆中心，避免形成错误的隐私暗示。
      memoryCenterOpen.value = false
      const createdSession = await createTemporarySession()
      if (requestEpoch !== authenticationEpoch) return
      temporarySession.value = createdSession
      temporaryEnabled.value = true
      messages.value = []
    }
  } catch {
    if (requestEpoch === authenticationEpoch) {
      ElMessage.error(temporaryEnabled.value ? '临时对话清除失败' : '暂时无法开启临时对话')
    }
  } finally {
    if (requestEpoch === authenticationEpoch) sessionLoading.value = false
  }
}

/** 桌面窗口使用指针差值拖动，移动的是包含桌宠与小窗的整体容器。 */
function startDrag(event) {
  if (fullscreen.value || event.button !== 0) return
  drag = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    right: position.value.right,
    bottom: position.value.bottom,
  }
  window.addEventListener('pointermove', moveDock)
  window.addEventListener('pointerup', stopDrag, { once: true })
}

function moveDock(event) {
  if (!drag || event.pointerId !== drag.pointerId) return
  const maxRight = Math.max(8, window.innerWidth - 110)
  const maxBottom = Math.max(8, window.innerHeight - 120)
  position.value = {
    right: Math.min(maxRight, Math.max(8, drag.right + drag.startX - event.clientX)),
    bottom: Math.min(maxBottom, Math.max(8, drag.bottom + drag.startY - event.clientY)),
  }
}

function stopDrag(event) {
  if (drag && event.pointerId === drag.pointerId) drag = null
  window.removeEventListener('pointermove', moveDock)
}

onBeforeUnmount(() => {
  streamController?.abort()
  window.removeEventListener('pointermove', moveDock)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('metro-auth-changed', syncAuthentication)
  window.removeEventListener('storage', handleAuthenticationStorage)
})

onMounted(() => {
  window.addEventListener('metro-auth-changed', syncAuthentication)
  window.addEventListener('storage', handleAuthenticationStorage)
})
</script>

<style scoped lang="scss">
.agent-dock {
  position: fixed;
  z-index: 1600;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  color: var(--ink, #29231e);
}

.agent-window {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  width: min(420px, calc(100vw - 126px));
  height: min(650px, calc(100vh - 44px));
  overflow: hidden;
  border: 1px solid #cbb8a8;
  border-radius: 12px;
  background: #fffdf9;
  box-shadow: 0 24px 64px rgba(61, 41, 26, .2), 0 4px 14px rgba(61, 41, 26, .1);
}

.agent-window__header {
  display: flex;
  align-items: center;
  min-height: 58px;
  padding: 8px 10px 8px 16px;
  border-bottom: 1px solid #d8cabc;
  cursor: grab;
  user-select: none;
  touch-action: none;
}
.agent-window__header:active { cursor: grabbing; }
.agent-window__identity { min-width: 0; flex: 1; }
.agent-window__identity strong { display: block; font: 700 16px/1.2 "Songti SC", SimSun, serif; }
.agent-window__identity span { display: block; margin-top: 3px; color: #766d64; font-size: 11px; }
.agent-window__controls { display: flex; gap: 2px; }
.agent-window__controls button {
  width: 40px; height: 40px; border: 0; border-radius: 7px; background: transparent;
  color: #766d64; font: inherit; font-size: 18px; cursor: pointer;
}
.agent-window__controls button:hover { background: #f1e8dc; color: #29231e; }

.agent-context { padding: 12px 14px; border-bottom: 1px solid #d8cabc; background: #fbf6ef; }
.agent-context__title { display: flex; gap: 6px; min-width: 0; font-size: 11px; }
.agent-context__title span { flex: 0 0 auto; color: #a55245; font-weight: 700; }
.agent-context__title strong { overflow: hidden; color: #766d64; text-overflow: ellipsis; white-space: nowrap; }
.agent-context__actions { display: flex; gap: 7px; margin-top: 9px; overflow-x: auto; }
.agent-context__actions button {
  min-height: 34px; padding: 0 11px; border: 1px solid #d8cabc; border-radius: 6px;
  background: #fffdf9; color: #29231e; font: inherit; font-size: 12px; white-space: nowrap; cursor: pointer;
}
.agent-context__actions button:first-child { border-color: #a55245; color: #7f3d34; background: #f7e9e4; }

.agent-conversation { min-height: 0; overflow: auto; padding: 16px; overscroll-behavior: contain; }
.agent-empty { display: grid; align-content: center; min-height: 100%; padding: 20px; text-align: center; }
.agent-empty__seal { display: grid; place-items: center; width: 48px; height: 48px; margin: 0 auto 14px; border: 1px solid #a55245; border-radius: 50%; color: #a55245; font-family: serif; }
.agent-empty h2 { margin: 0; font: 700 20px/1.4 "Songti SC", SimSun, serif; }
.agent-empty p { max-width: 290px; margin: 9px auto 0; color: #766d64; font-size: 12px; line-height: 1.7; }
.agent-message { margin-bottom: 14px; }
.agent-message small { display: block; margin-bottom: 4px; color: #766d64; font-size: 10px; }
.agent-message p { margin: 0; padding: 10px 12px; border-radius: 9px; background: #f3ebe1; font-size: 13px; line-height: 1.75; white-space: pre-wrap; }
.agent-message.is-user { margin-left: 42px; text-align: right; }
.agent-message.is-user p { background: #e9ddd0; text-align: left; }
.agent-loading { display: flex; align-items: center; gap: 5px; color: #7f3d34; font-size: 11px; }
.agent-loading i { width: 5px; height: 5px; border-radius: 50%; background: #a55245; animation: agent-pulse 1s infinite alternate; }
.agent-loading i:nth-child(2) { animation-delay: .2s; }
.agent-loading i:nth-child(3) { animation-delay: .4s; }
.agent-loading span { margin-left: 4px; }

.agent-suggestion { border: 1px solid #d8cabc; border-radius: 8px; overflow: hidden; background: #fff; }
.agent-suggestion header { display: flex; justify-content: space-between; padding: 10px 12px; background: #f7f1e7; font-size: 11px; }
.agent-suggestion header span { color: #766d64; }
.agent-suggestion__before, .agent-suggestion__after { padding: 11px 12px; }
.agent-suggestion__before { color: #80645d; background: #fbf5f1; text-decoration: line-through; }
.agent-suggestion__after { border-top: 1px solid #eaded3; }
.agent-suggestion small { display: block; margin-bottom: 5px; color: #766d64; text-decoration: none; }
.agent-suggestion p { margin: 0; font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.agent-suggestion__actions { display: grid; grid-template-columns: 1fr auto; gap: 7px; padding: 9px; border-top: 1px solid #d8cabc; }
.agent-suggestion__actions button { min-height: 36px; border: 1px solid #d8cabc; border-radius: 5px; background: #fffdf9; font: inherit; cursor: pointer; }
.agent-suggestion__actions .is-primary { border-color: #a55245; background: #a55245; color: #fff; }
.agent-suggestion__notice { padding: 9px 11px; color: #766d64; background: #f7f1e7; font-size: 10px !important; }

.agent-composer { padding: 9px 11px 11px; border-top: 1px solid #d8cabc; background: #f7f1e7; }
.agent-composer__meta { display: flex; justify-content: space-between; margin-bottom: 6px; color: #766d64; font-size: 10px; }
.agent-composer__meta button { border: 0; background: transparent; color: #7f3d34; font: inherit; cursor: pointer; }
.agent-composer__box { display: grid; grid-template-columns: 1fr 40px; gap: 6px; align-items: end; padding: 6px 6px 6px 10px; border: 1px solid #cdbbaa; border-radius: 8px; background: #fffdf9; }
.agent-composer textarea { min-height: 38px; resize: none; border: 0; outline: 0; background: transparent; color: #29231e; font: inherit; font-size: 12px; }
.agent-composer__box > button { width: 40px; height: 40px; border: 0; border-radius: 6px; background: #a55245; color: #fff; font-size: 17px; cursor: pointer; }
.agent-composer__box > button:disabled { opacity: .45; cursor: not-allowed; }
.agent-funding { margin: 6px 1px 0; color: #847970; font-size: 9px; }

.agent-pet { position: relative; width: 96px; min-height: 116px; padding: 4px 6px 8px; border: 0; background: transparent; cursor: pointer; filter: drop-shadow(0 9px 8px rgba(47, 32, 21, .18)); }
.agent-pet__figure { display: block; width: 92px; height: 112px; object-fit: contain; image-rendering: pixelated; }
.agent-pet__name { position: absolute; right: 4px; bottom: 0; padding: 3px 7px; border: 1px solid #d8cabc; border-radius: 999px; background: #fffdf9; color: #766d64; font-size: 9px; }
.agent-pet:hover { transform: translateY(-3px); }

.agent-dock.is-fullscreen { inset: 14px; align-items: stretch; }
.agent-dock.is-fullscreen .agent-window { width: 100%; height: 100%; border-radius: 10px; }
.agent-dock.is-fullscreen .agent-pet { align-self: flex-end; }

@keyframes agent-pulse { to { transform: translateY(-3px); opacity: .45; } }

@media (max-width: 720px) {
  .agent-dock { right: 10px !important; bottom: 10px !important; }
  .agent-window { position: fixed; inset: 10px; width: auto; height: auto; }
  .agent-dock.is-open .agent-pet { display: none; }
}
</style>
