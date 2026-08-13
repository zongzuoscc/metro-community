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
            @click.stop="toggleFullscreen"
          >
            {{ fullscreen ? '↘' : '↗' }}
          </button>
          <button type="button" aria-label="关闭 Agent 小窗" @pointerdown.stop @click.stop="open = false">×</button>
        </div>
      </header>

      <div
        class="agent-window__workspace"
        :class="{ 'has-history-rail': fullscreen && !temporaryEnabled && !memoryCenterOpen }"
      >
        <nav
          v-if="fullscreen && !temporaryEnabled && !memoryCenterOpen"
          data-test="agent-history-rail"
          class="agent-history-rail"
          aria-label="主对话历史"
        >
          <span class="agent-history-rail__title">历</span>
          <div class="agent-history-rail__items">
            <button
              v-if="historyNextCursor"
              type="button"
              class="agent-history-more"
              :disabled="historyLoading"
              aria-label="加载更早的对话"
              @click="loadHistory(true)"
            >···</button>
            <button
              v-for="item in chronologicalHistoryItems"
              :key="item.turnId"
              :data-test="`history-turn-${item.turnId}`"
              type="button"
              class="agent-history-rail__item"
              :class="{ 'is-active': selectedHistoryTurnId === item.turnId }"
              :aria-label="`查看历史问答：${item.questionPreview}`"
              @click="restoreHistoryTurn(item.turnId)"
            >
              <i aria-hidden="true"></i>
              <span :data-test="`history-preview-${item.turnId}`" class="agent-history-preview">
                <strong>{{ item.questionPreview }}</strong>
                <em>{{ item.answerPreview }}</em>
              </span>
            </button>
          </div>
        </nav>

        <main class="agent-window__main">
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

      <section v-if="!memoryCenterOpen" ref="conversation" data-test="agent-conversation"
               class="agent-conversation" aria-live="polite" @scroll.passive="handleConversationScroll">
        <div v-if="messages.length === 0 && chronologicalHistoryItems.length === 0 && !suggestion" class="agent-empty">
          <span class="agent-empty__seal">问</span>
          <h2>{{ emptyTitle }}</h2>
          <p>{{ emptyDescription }}</p>
        </div>
        <section
          v-for="turn in chronologicalHistoryItems"
          :key="`history-${turn.turnId}`"
          :ref="element => bindHistoryTurnElement(turn.turnId, element)"
          :data-test="`history-content-${turn.turnId}`"
          class="agent-history-turn"
          :class="{ 'is-active': selectedHistoryTurnId === turn.turnId }"
        >
          <article class="agent-message is-user">
            <small>你</small>
            <p>{{ turn.userMessage }}</p>
          </article>
          <article class="agent-message is-assistant">
            <small>Metro Agent</small>
            <p>{{ turn.finalMessage }}</p>
            <div v-if="turn.citations?.length || turn.webSources?.length || turn.webSourcesExpired"
                 class="agent-message__sources">
              <section v-if="turn.citations?.length">
                <strong>站内资料</strong>
                <a v-for="citation in turn.citations"
                   :key="`history-site-${turn.turnId}-${citation.marker}`"
                   :href="citation.url">[{{ citation.marker }}] {{ citation.title }}</a>
              </section>
              <section v-if="turn.webSources?.length">
                <strong>联网来源</strong>
                <a v-for="source in turn.webSources"
                   :key="`history-web-${turn.turnId}-${source.index}`"
                   :href="source.url" target="_blank" rel="noopener noreferrer">
                  [W{{ source.index }}] {{ source.title }}<span v-if="source.siteName"> · {{ source.siteName }}</span>
                </a>
              </section>
              <p v-if="turn.webSourcesExpired" class="agent-message__sources-expired">
                较早的联网来源快照已超过 30 天保留期
              </p>
            </div>
          </article>
        </section>
        <article v-for="message in visibleTransientMessages" :key="message.id"
                 class="agent-message" :class="`is-${message.role}`">
          <small>{{ message.role === 'user' ? '你' : 'Metro Agent' }}</small>
          <p>{{ message.content }}</p>
          <div v-if="message.citations?.length || message.webSources?.length" class="agent-message__sources">
            <section v-if="message.citations?.length">
              <strong>站内资料</strong>
              <a v-for="citation in message.citations" :key="`site-${citation.marker}`"
                 :href="citation.url">[{{ citation.marker }}] {{ citation.title }}</a>
            </section>
            <section v-if="message.webSources?.length">
              <strong>联网来源</strong>
              <a v-for="source in message.webSources" :key="`web-${source.index}`"
                 :href="source.url" target="_blank" rel="noopener noreferrer">
                [W{{ source.index }}] {{ source.title }}<span v-if="source.siteName"> · {{ source.siteName }}</span>
              </a>
            </section>
          </div>
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
          <div class="agent-composer__options">
            <button data-test="web-search-toggle" type="button" :disabled="webSearchLoading"
                    :aria-pressed="webSearchEnabled" @click="toggleWebSearch">
              {{ webSearchEnabled ? '联网开' : '联网关' }}
            </button>
            <button data-test="temporary-toggle" type="button" :disabled="sessionLoading" @click="toggleTemporaryMode">
              {{ temporaryEnabled ? '退出临时' : '开启临时' }}
            </button>
          </div>
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
        </main>
      </div>
    </aside>

    <button
      data-test="agent-pet"
      type="button"
      class="agent-pet"
      :aria-label="open ? '收起 Metro Agent' : '打开 Metro Agent'"
      @pointerdown="startDrag($event, true)"
      @click="toggleDock"
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
  getAgentWebSearchSetting,
  getAgentTurnHistory,
  analyzeArticle,
  deleteTemporarySession,
  streamAgentTurnEvents,
  summarizeArticle,
  updateAgentWebSearchSetting,
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
const webSearchEnabled = ref(true)
const webSearchLoading = ref(false)
const historyItems = ref([])
const historyNextCursor = ref(null)
const historyLoading = ref(false)
const historyLoaded = ref(false)
const selectedHistoryTurnId = ref(null)
const historyTurnElements = new Map()
const conversation = ref(null)
const position = ref({ right: 24, bottom: 18 })
let drag = null
let suppressPetClickUntil = 0
let streamController = null
let authenticationEpoch = 0
let historyRefreshPending = false
let historyExtended = false

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
  webSearchEnabled.value = true
  webSearchLoading.value = false
  historyItems.value = []
  historyNextCursor.value = null
  historyLoading.value = false
  historyLoaded.value = false
  historyRefreshPending = false
  historyExtended = false
  selectedHistoryTurnId.value = null
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
/** 接口按新到旧分页；正文反转后才符合自然阅读顺序。 */
const chronologicalHistoryItems = computed(() => !temporaryEnabled.value && historyLoaded.value
  ? [...historyItems.value].reverse()
  : [])
/** 已进入权威历史页的本地消息不重复渲染；仍在生成中的消息继续即时显示。 */
const visibleTransientMessages = computed(() => {
  const persistedTurnIds = new Set(historyItems.value.map(item => String(item.turnId)))
  return messages.value.filter(message => !message.turnId || !persistedTurnIds.has(String(message.turnId)))
})

/**
 * 放大时才读取历史摘要，小窗保持轻量。每次换账号都会推进 epoch，
 * 因此旧账号迟到的历史页不能写入新账号界面。
 */
async function toggleFullscreen() {
  fullscreen.value = !fullscreen.value
  if (fullscreen.value && !temporaryEnabled.value && !historyLoaded.value) {
    await loadHistory(false)
  }
}

/**
 * 按服务端游标追加主对话，不对时间再做人为分组。
 * 初次加载定位最新内容；向上翻页则保持原有可视内容的位置，不能突然跳到更早记录。
 */
async function loadHistory(append) {
  if (historyLoading.value) {
    // 只有“刷新最新页”需要排队；自动加载更早页的重复滚动事件可以直接合并掉。
    if (!append) historyRefreshPending = true
    return
  }
  const requestEpoch = authenticationEpoch
  const preserveOlderPages = !append && historyExtended
  const previousCursor = historyNextCursor.value
  const previousScrollHeight = append ? (conversation.value?.scrollHeight || 0) : 0
  const previousScrollTop = append ? (conversation.value?.scrollTop || 0) : 0
  historyLoading.value = true
  try {
    const page = await getAgentTurnHistory({
      beforeTurnId: append ? historyNextCursor.value : undefined,
      size: 30,
    })
    if (requestEpoch !== authenticationEpoch) return
    const pageItems = page.items || []
    if (append) {
      historyItems.value = [...historyItems.value, ...pageItems]
      historyExtended = true
    } else if (preserveOlderPages) {
      // 最新页放在前面，并按 turnId 去掉与旧页重叠的记录；已加载的更早记录不能被刷新丢弃。
      const latestTurnIds = new Set(pageItems.map(item => String(item.turnId)))
      historyItems.value = [
        ...pageItems,
        ...historyItems.value.filter(item => !latestTurnIds.has(String(item.turnId))),
      ]
    } else {
      historyItems.value = pageItems
    }
    historyNextCursor.value = preserveOlderPages ? previousCursor : (page.nextBeforeTurnId || null)
    historyLoaded.value = true
    await nextTick()
    if (requestEpoch !== authenticationEpoch || !conversation.value) return
    if (append) {
      // 更早记录会插入正文顶部，用新增高度补偿 scrollTop，用户仍停留在原来阅读的位置。
      conversation.value.scrollTop = previousScrollTop
        + Math.max(0, conversation.value.scrollHeight - previousScrollHeight)
    } else {
      conversation.value.scrollTop = conversation.value.scrollHeight
    }
  } catch {
    if (requestEpoch === authenticationEpoch) ElMessage.error('历史对话加载失败')
  } finally {
    if (requestEpoch === authenticationEpoch) {
      historyLoading.value = false
      if (historyRefreshPending) {
        historyRefreshPending = false
        // 当前请求已完全结束后再启动下一次，避免递归复用尚未释放的 loading 状态。
        loadHistory(false)
      }
    }
  }
}

/** 主对话滚到顶部附近时自动读取上一页，小窗同样可以连续回看全部历史。 */
function handleConversationScroll() {
  if (!conversation.value || temporaryEnabled.value || !historyLoaded.value
      || historyLoading.value || !historyNextCursor.value) return
  if (conversation.value.scrollTop <= 24) loadHistory(true)
}

/**
 * 记录每一轮在连续对话正文中的 DOM 锚点。Vue 在旧节点卸载时会传入 null，
 * 必须同步删除，避免后续误滚动到已经销毁的元素。
 */
function bindHistoryTurnElement(turnId, element) {
  if (element) historyTurnElements.set(turnId, element)
  else historyTurnElements.delete(turnId)
}

/** 点击轨道只定位到同一主对话中的目标轮次，绝不替换或裁剪其他问答。 */
function restoreHistoryTurn(turnId) {
  selectedHistoryTurnId.value = turnId
  historyTurnElements.get(turnId)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

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
  const pendingUserMessage = { id: `user-${Date.now()}`, role: 'user', content: question, turnId: null }
  messages.value.push(pendingUserMessage)
  selectedHistoryTurnId.value = null
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
    pendingUserMessage.turnId = admission.turnId
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
          messages.value.push({
            id: `assistant-${admission.turnId}`,
            role: 'assistant',
            content: payload.finalMessage,
            turnId: admission.turnId,
            citations: payload.citations || [],
            webSources: payload.webSources || [],
          })
          if (payload.fundingSource) funding.value = payload
          // 新的持久问答完成后立即刷新权威主对话。刷新完成前保留当前历史数组，
          // 避免小窗把此前问答暂时隐藏；turnId 去重会在新页返回后移除即时副本。
          if (!temporaryEnabled.value) {
            loadHistory(false)
          }
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
 * 联网偏好按主对话保存；旧请求即使迟到也不能覆盖换账号后的新状态。
 * 临时模式只复用该偏好，不会因此把临时消息写入主对话。
 */
async function toggleWebSearch() {
  if (webSearchLoading.value || taskLoading.value) return
  const requestEpoch = authenticationEpoch
  const target = !webSearchEnabled.value
  webSearchLoading.value = true
  try {
    const saved = await updateAgentWebSearchSetting(target)
    if (requestEpoch !== authenticationEpoch) return
    webSearchEnabled.value = Boolean(saved.enabled)
  } catch {
    if (requestEpoch === authenticationEpoch) ElMessage.error('联网设置保存失败，请稍后重试')
  } finally {
    if (requestEpoch === authenticationEpoch) webSearchLoading.value = false
  }
}

/** 登录后从后端恢复主对话偏好，默认开启且不使用 localStorage 保存账号数据。 */
async function restoreWebSearchSetting() {
  if (!authenticated.value) return
  const requestEpoch = authenticationEpoch
  try {
    const setting = await getAgentWebSearchSetting()
    if (requestEpoch === authenticationEpoch) webSearchEnabled.value = Boolean(setting.enabled)
  } catch {
    // 后端暂不可用时保留默认开启；发送请求仍会由服务端会话事实决定最终行为。
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

/**
 * 桌宠本体和小窗标题栏共用同一套指针拖动。
 * 只有超过小幅移动阈值才认定为拖动，轻点桌宠仍然保留打开小窗的行为。
 */
function startDrag(event, fromPet = false) {
  if (fullscreen.value || event.button !== 0) return
  event.preventDefault()
  drag = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    right: position.value.right,
    bottom: position.value.bottom,
    fromPet,
    moved: false,
  }
  // 指针移出桌宠或标题栏后仍需继续接收事件，否则快速拖动会中途丢失。
  event.currentTarget?.setPointerCapture?.(event.pointerId)
  window.addEventListener('pointermove', moveDock)
  window.addEventListener('pointerup', stopDrag, { once: true })
  window.addEventListener('pointercancel', stopDrag, { once: true })
}

function moveDock(event) {
  if (!drag || event.pointerId !== drag.pointerId) return
  const deltaX = drag.startX - event.clientX
  const deltaY = drag.startY - event.clientY
  // 屏蔽触摸屏和鼠标的微小抖动，防止普通点击被错判为拖动。
  if (!drag.moved && Math.hypot(deltaX, deltaY) < 5) return
  drag.moved = true
  const maxRight = Math.max(8, window.innerWidth - 110)
  const maxBottom = Math.max(8, window.innerHeight - 120)
  position.value = {
    right: Math.min(maxRight, Math.max(8, drag.right + deltaX)),
    bottom: Math.min(maxBottom, Math.max(8, drag.bottom + deltaY)),
  }
}

function stopDrag(event) {
  if (drag && event.pointerId === drag.pointerId) {
    // 浏览器会在 pointerup 之后再派发 click；短时间屏蔽该 click，避免拖完又打开小窗。
    if (drag.fromPet && drag.moved) suppressPetClickUntil = performance.now() + 400
    drag = null
  }
  window.removeEventListener('pointermove', moveDock)
  // pointercancel 也会走到这里，此时要主动清掉尚未触发的 pointerup 监听。
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
}

/**
 * 拖动尾声不触发展开，真正的轻点则正常切换小窗。
 * 主对话正文属于普通小窗能力，所以首次打开就加载；全屏只额外展示定位轨道。
 */
function toggleDock() {
  if (performance.now() < suppressPetClickUntil) return
  open.value = !open.value
  if (open.value && !temporaryEnabled.value && !historyLoaded.value) loadHistory(false)
}

onBeforeUnmount(() => {
  streamController?.abort()
  window.removeEventListener('pointermove', moveDock)
  window.removeEventListener('pointerup', stopDrag)
  window.removeEventListener('pointercancel', stopDrag)
  window.removeEventListener('metro-auth-changed', syncAuthentication)
  window.removeEventListener('storage', handleAuthenticationStorage)
})

onMounted(() => {
  window.addEventListener('metro-auth-changed', syncAuthentication)
  window.addEventListener('storage', handleAuthenticationStorage)
  restoreWebSearchSetting()
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
  grid-template-rows: auto minmax(0, 1fr);
  width: min(420px, calc(100vw - 126px));
  height: min(650px, calc(100vh - 44px));
  overflow: hidden;
  border: 1px solid #cbb8a8;
  border-radius: 12px;
  background: #fffdf9;
  box-shadow: 0 24px 64px rgba(61, 41, 26, .2), 0 4px 14px rgba(61, 41, 26, .1);
}

/*
 * 小窗正文与全屏历史轨道共用一个工作区。轨道没有出现时只保留一列，
 * 因此普通小窗不会为了历史功能白白损失宽度。
 */
.agent-window__workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
}
.agent-window__main {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-width: 0;
  min-height: 0;
}
.agent-window__main > [data-test="memory-center"] { grid-row: 1 / -1; min-height: 0; }
.agent-window__main > .agent-context { grid-row: 1; }
.agent-window__main > .agent-conversation { grid-row: 2; }
.agent-window__main > .agent-composer { grid-row: 3; }

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

/*
 * 历史轨道只表达“主对话中的位置”，每一道短线代表一轮已经成功完成的问答。
 * 摘要默认收起，鼠标或键盘聚焦时才浮出，避免把全屏重新做成沉重的会话列表。
 */
.agent-history-rail {
  position: relative;
  z-index: 8;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 0;
  padding: 10px 5px;
  border-right: 1px solid #d8cabc;
  background: #f8f1e8;
}
.agent-history-rail__title {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  margin-bottom: 8px;
  border: 1px solid #c9b5a3;
  border-radius: 50%;
  color: #7f3d34;
  font: 700 12px/1 "Songti SC", SimSun, serif;
}
.agent-history-rail__items {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  width: 100%;
  min-height: 0;
  padding-top: 2px;
}
.agent-history-rail__item,
.agent-history-more {
  position: relative;
  display: grid;
  place-items: center;
  width: 30px;
  min-height: 17px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
}
.agent-history-rail__item i {
  display: block;
  width: 12px;
  height: 2px;
  border-radius: 999px;
  background: #b5aaa0;
  transition: width .16s ease, background .16s ease;
}
.agent-history-rail__item:nth-child(3n + 1) i { width: 19px; }
.agent-history-rail__item:nth-child(3n + 2) i { width: 8px; }
.agent-history-rail__item:hover i,
.agent-history-rail__item:focus-visible i,
.agent-history-rail__item.is-active i { width: 25px; background: #7f3d34; }
.agent-history-preview {
  position: absolute;
  top: -8px;
  left: 35px;
  z-index: 30;
  display: none;
  width: min(430px, calc(100vw - 190px));
  padding: 15px 17px;
  border: 1px solid #d2c5b9;
  border-radius: 12px;
  background: rgba(255, 253, 249, .98);
  box-shadow: 0 16px 42px rgba(61, 41, 26, .18);
  text-align: left;
  pointer-events: none;
}
.agent-history-preview strong,
.agent-history-preview em {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
}
.agent-history-preview strong {
  margin-bottom: 8px;
  color: #29231e;
  font: 700 14px/1.55 "Songti SC", SimSun, serif;
  -webkit-line-clamp: 1;
}
.agent-history-preview em {
  color: #7a7169;
  font-size: 12px;
  font-style: normal;
  line-height: 1.65;
  -webkit-line-clamp: 3;
}
.agent-history-rail__item:hover .agent-history-preview,
.agent-history-rail__item:focus-visible .agent-history-preview { display: block; }
.agent-history-more { margin-top: 4px; color: #7f3d34; font: 700 13px/1 sans-serif; }
.agent-history-more:hover { background: #eadfd3; }

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
.agent-history-turn {
  padding: 2px 5px;
  border-radius: 10px;
  scroll-margin-top: 16px;
  transition: background .2s ease;
}
.agent-history-turn.is-active { background: #fbf4ea; }
.agent-message small { display: block; margin-bottom: 4px; color: #766d64; font-size: 10px; }
.agent-message p { margin: 0; padding: 10px 12px; border-radius: 9px; background: #f3ebe1; font-size: 13px; line-height: 1.75; white-space: pre-wrap; }
.agent-message.is-user { margin-left: 42px; text-align: right; }
.agent-message.is-user p { background: #e9ddd0; text-align: left; }
.agent-message__sources { display: grid; gap: 8px; margin-top: 7px; padding: 9px 10px; border-left: 2px solid #caa99d; background: #fbf7f1; }
.agent-message__sources section { display: grid; gap: 3px; }
.agent-message__sources strong { color: #7f3d34; font-size: 10px; }
.agent-message__sources a { overflow: hidden; color: #5c5650; font-size: 10px; line-height: 1.5; text-decoration: none; text-overflow: ellipsis; white-space: nowrap; }
.agent-message__sources a:hover { color: #a55245; text-decoration: underline; }
.agent-message__sources a span { color: #8b8178; }
.agent-message__sources-expired {
  margin: 0;
  padding: 0 !important;
  background: transparent !important;
  color: #8b8178;
  font-size: 10px !important;
  line-height: 1.5 !important;
}
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
.agent-composer__options { display: flex; gap: 8px; }
.agent-composer__options [aria-pressed="false"] { color: #8b8178; }
.agent-composer__box { display: grid; grid-template-columns: 1fr 40px; gap: 6px; align-items: end; padding: 6px 6px 6px 10px; border: 1px solid #cdbbaa; border-radius: 8px; background: #fffdf9; }
.agent-composer textarea { min-height: 38px; resize: none; border: 0; outline: 0; background: transparent; color: #29231e; font: inherit; font-size: 12px; }
.agent-composer__box > button { width: 40px; height: 40px; border: 0; border-radius: 6px; background: #a55245; color: #fff; font-size: 17px; cursor: pointer; }
.agent-composer__box > button:disabled { opacity: .45; cursor: not-allowed; }
.agent-funding { margin: 6px 1px 0; color: #847970; font-size: 9px; }

.agent-pet { position: relative; width: 96px; min-height: 116px; padding: 4px 6px 8px; border: 0; background: transparent; cursor: grab; touch-action: none; user-select: none; filter: drop-shadow(0 9px 8px rgba(47, 32, 21, .18)); }
.agent-pet:active { cursor: grabbing; }
.agent-pet__figure { display: block; width: 92px; height: 112px; object-fit: contain; image-rendering: pixelated; }
.agent-pet__name { position: absolute; right: 4px; bottom: 0; padding: 3px 7px; border: 1px solid #d8cabc; border-radius: 999px; background: #fffdf9; color: #766d64; font-size: 9px; }
.agent-pet:hover { transform: translateY(-3px); }

.agent-dock.is-fullscreen { inset: 14px; align-items: stretch; }
.agent-dock.is-fullscreen .agent-window { flex: 1; width: auto; height: 100%; border-radius: 10px; }
.agent-dock.is-fullscreen .agent-window__workspace.has-history-rail {
  grid-template-columns: 42px minmax(0, 1fr);
}
.agent-dock.is-fullscreen .agent-pet { align-self: flex-end; }

@keyframes agent-pulse { to { transform: translateY(-3px); opacity: .45; } }

@media (max-width: 720px) {
  /* 小屏关闭状态仍允许桌宠拖动；位置边界由同一套指针逻辑统一限制。 */
  .agent-window { position: fixed; inset: 10px; width: auto; height: auto; }
  .agent-dock.is-open .agent-pet { display: none; }
}
</style>
