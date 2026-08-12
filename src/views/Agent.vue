<template>
  <div class="agent-page" :class="{ 'is-temporary': temporaryEnabled }">
    <header class="agent-topbar">
      <button type="button" class="back-button" aria-label="返回社区首页" @click="router.push('/home')">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回社区</span>
      </button>

      <div class="agent-heading">
        <span class="agent-kicker">Metro Agent</span>
        <h1>{{ temporaryEnabled ? '临时对话' : '社区助手' }}</h1>
      </div>

      <button
        data-test="temporary-toggle"
        type="button"
        class="mode-toggle"
        :class="{ active: temporaryEnabled }"
        :disabled="sessionLoading || turnRunning"
        :aria-pressed="temporaryEnabled"
        @click="toggleTemporaryMode"
      >
        <el-icon><Hide /></el-icon>
        <span>{{ temporaryEnabled ? '关闭临时模式' : '开启临时模式' }}</span>
      </button>
    </header>

    <main class="agent-workspace">
      <section v-if="temporaryEnabled" class="privacy-banner" aria-live="polite">
        <div class="privacy-mark" aria-hidden="true"><el-icon><Hide /></el-icon></div>
        <div class="privacy-copy">
          <strong>临时对话已开启</strong>
          <p>本页内容不会写入聊天历史和长期记忆。关闭临时模式或到期后，内容将不可恢复。</p>
          <span v-if="temporarySession?.expiresAt" class="expiry-text">
            有效期至 {{ formatExpiry(temporarySession.expiresAt) }}
          </span>
        </div>
        <el-button
          data-test="leave-temporary"
          class="leave-button"
          :disabled="turnRunning"
          @click="leaveTemporaryMode"
        >
          清除并退出
        </el-button>
      </section>

      <section class="conversation-panel" aria-label="Agent 对话内容">
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-seal" aria-hidden="true">问</div>
          <h2>{{ temporaryEnabled ? '说完即忘' : '从社区知识开始提问' }}</h2>
          <p v-if="temporaryEnabled">
            适合处理不希望进入历史和长期记忆的问题。回答仍会基于当前可用的社区公开知识生成。
          </p>
          <p v-else>
            可以询问技术文章、社区内容和创作思路。普通对话会进入你的对话历史，并可能使用已启用的长期记忆。
          </p>
          <div class="suggestion-row" aria-label="问题示例">
            <button type="button" @click="fillSuggestion('帮我总结最近值得关注的技术主题')">社区热点</button>
            <button type="button" @click="fillSuggestion('解释一下这段技术概念，给出一个例子')">概念解释</button>
            <button type="button" @click="fillSuggestion('帮我梳理一篇技术文章的写作提纲')">写作提纲</button>
          </div>
        </div>

        <ol v-else class="message-list" aria-live="polite">
          <li v-for="message in messages" :key="message.id" class="message" :class="message.role">
            <div class="message-label">{{ message.role === 'user' ? '你' : 'Metro Agent' }}</div>
            <div class="message-bubble">
              <p v-if="message.content">{{ message.content }}</p>
              <div v-else class="answer-progress">
                <span></span><span></span><span></span>
                <em>{{ statusText }}</em>
              </div>
            </div>
          </li>
        </ol>
      </section>

      <section class="composer" aria-label="发送消息">
        <textarea
          data-test="agent-input"
          v-model="draft"
          :disabled="turnRunning || sessionLoading"
          maxlength="4000"
          rows="3"
          :placeholder="temporaryEnabled ? '输入临时问题，内容不会保存…' : '向社区助手提问…'"
          @keydown.enter.exact.prevent="sendMessage"
        ></textarea>
        <div class="composer-footer">
          <div class="mode-note">
            <el-icon><InfoFilled /></el-icon>
            <span>{{ temporaryEnabled ? '不保存历史，不读取或写入长期记忆' : '普通模式会保存对话历史' }}</span>
          </div>
          <div class="composer-actions">
            <el-button v-if="turnRunning" @click="cancelTurn">停止回答</el-button>
            <el-button
              data-test="send-agent-message"
              type="primary"
              :disabled="!canSend"
              :loading="turnStarting"
              @click="sendMessage"
            >
              发送
            </el-button>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  cancelAgentTurn,
  createAgentTurn,
  createTemporarySession,
  deleteTemporarySession,
  getAgentTurn,
  getTemporarySession,
  streamAgentTurnEvents,
} from '../api/agent'

// 临时内容本身绝不进入浏览器存储。这里只保存不含正文的标识，供同一标签页刷新后回源恢复。
const TEMPORARY_SESSION_KEY = 'metro.agent.temporary.session'
const TEMPORARY_TURN_KEY = 'metro.agent.temporary.turn'
const PERSISTENT_TURN_KEY = 'metro.agent.persistent.turn'
const TERMINAL_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
const RECOVERY_DELAYS_MS = Object.freeze([400, 1000, 2000, 4000, 8000])

const router = useRouter()
const temporaryEnabled = ref(false)
const temporarySession = ref(null)
const sessionLoading = ref(false)
const turnStarting = ref(false)
const activeTurnId = ref(null)
const turnState = ref(null)
const lastEventId = ref(null)
const draft = ref('')
const messages = ref([])
let streamController = null
let recoveryTimer = null
let recoveryWaitResolver = null
let recoveryStopped = false

const turnRunning = computed(() => activeTurnId.value != null && !TERMINAL_STATES.has(turnState.value))
const canSend = computed(() => draft.value.trim().length > 0
  && !turnRunning.value
  && !turnStarting.value
  && !sessionLoading.value)
const statusText = computed(() => {
  if (turnState.value === 'RETRIEVING') return '正在检索社区内容'
  if (turnState.value === 'GENERATING') return '正在组织回答'
  if (turnState.value === 'CANCELLING') return '正在停止'
  return '正在思考'
})

/** 使用浏览器原生 UUID；仅在极旧环境中使用随机串兜底，仍保证一次点击只生成一个幂等键。 */
function newClientRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, token => {
    const random = Math.floor(Math.random() * 16)
    const value = token === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}

/** 将后端返回的绝对过期时间转为用户当前时区的简短中文时间，不改动真实截止点。 */
function formatExpiry(value) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

/** 只在没有正在执行的 turn 时填入问题示例，避免用户等待回答时误改下一轮输入。 */
function fillSuggestion(value) {
  if (!turnRunning.value) draft.value = value
}

/**
 * 保存刷新恢复所需的最小元数据。
 * 这里严禁写入问题、回答或引用，真实内容只能按所有者身份向后端快照恢复。
 */
function persistTemporaryMetadata() {
  if (temporarySession.value?.sessionId) {
    sessionStorage.setItem(TEMPORARY_SESSION_KEY, temporarySession.value.sessionId)
  }
  if (activeTurnId.value != null && temporaryEnabled.value) {
    sessionStorage.setItem(TEMPORARY_TURN_KEY, String(activeTurnId.value))
  }
}

/** 同时删除临时 session 和 turn 指针，防止用户退出后刷新页面又尝试恢复旧内容。 */
function clearTemporaryMetadata() {
  sessionStorage.removeItem(TEMPORARY_SESSION_KEY)
  sessionStorage.removeItem(TEMPORARY_TURN_KEY)
}

/**
 * 普通模式只保存正在执行的 turn ID，用于同一标签页刷新后回源。
 * turn 进入终态后立即删除指针，问题和回答始终不进入 sessionStorage。
 */
function syncPersistentTurnMetadata() {
  if (!temporaryEnabled.value && activeTurnId.value > 0 && turnRunning.value) {
    sessionStorage.setItem(PERSISTENT_TURN_KEY, String(activeTurnId.value))
  } else {
    sessionStorage.removeItem(PERSISTENT_TURN_KEY)
  }
}

/** 请求后端创建或复用唯一临时会话，只在服务端确认后才切换隐私提示和本地模式。 */
async function enableTemporaryMode() {
  sessionLoading.value = true
  try {
    temporarySession.value = await createTemporarySession()
    temporaryEnabled.value = true
    messages.value = []
    activeTurnId.value = null
    turnState.value = null
    persistTemporaryMetadata()
  } catch {
    ElMessage.error('暂时无法开启临时对话，请稍后重试')
  } finally {
    sessionLoading.value = false
  }
}

/**
 * 显式删除后端临时会话后再清空界面。
 * 若后端拒绝或网络失败，保留当前内容和标识，避免界面假装数据已删除。
 */
async function leaveTemporaryMode() {
  if (turnRunning.value) {
    ElMessage.warning('请先停止当前回答，再清除临时对话')
    return
  }
  sessionLoading.value = true
  try {
    await deleteTemporarySession()
    streamController?.abort()
    clearTemporaryMetadata()
    temporaryEnabled.value = false
    temporarySession.value = null
    activeTurnId.value = null
    turnState.value = null
    messages.value = []
    ElMessage.success('临时对话已清除')
  } catch {
    ElMessage.error('临时对话清除失败，请稍后重试')
  } finally {
    sessionLoading.value = false
  }
}

/** 统一处理顶部模式开关；正在生成的回答必须先停止，不允许跨隐私边界切换。 */
async function toggleTemporaryMode() {
  if (turnRunning.value) return
  if (temporaryEnabled.value) await leaveTemporaryMode()
  else await enableTemporaryMode()
}

/** 获取本轮助手消息；若尚未建立，则创建一个空气泡用于接收流式增量。 */
function ensureAssistantMessage() {
  let assistant = messages.value.at(-1)
  if (!assistant || assistant.role !== 'assistant') {
    assistant = { id: `assistant-${activeTurnId.value}`, role: 'assistant', content: '' }
    messages.value.push(assistant)
  }
  return assistant
}

/** 将 SSE 业务事件映射为界面状态，终态一定生成可见文字，不会留下假加载动画。 */
function applyStreamEvent(event) {
  if (event.id) lastEventId.value = event.id
  const payload = event.data?.payload || {}
  if (event.type === 'retrieving') turnState.value = 'RETRIEVING'
  else if (event.type === 'generating') turnState.value = 'GENERATING'
  else if (event.type === 'delta') {
    turnState.value = 'GENERATING'
    ensureAssistantMessage().content += payload.textAppend || ''
  } else if (event.type === 'done') {
    turnState.value = 'SUCCEEDED'
    ensureAssistantMessage().content = payload.finalMessage || ensureAssistantMessage().content
  } else if (event.type === 'cancelled') {
    turnState.value = 'CANCELLED'
    ensureAssistantMessage().content ||= '回答已停止。'
  } else if (event.type === 'error') {
    turnState.value = 'FAILED'
    ensureAssistantMessage().content = '这次回答没有完成，请稍后重试。'
  }
  syncPersistentTurnMetadata()
}

/** 按固定上限的退避表等待下次恢复，组件卸载时会主动唤醒并终止等待。 */
async function waitForRecovery(attempt) {
  if (attempt <= 0 || recoveryStopped) return
  const delay = RECOVERY_DELAYS_MS[Math.min(attempt - 1, RECOVERY_DELAYS_MS.length - 1)]
  await new Promise(resolve => {
    recoveryWaitResolver = resolve
    recoveryTimer = window.setTimeout(resolve, delay)
  })
  recoveryTimer = null
  recoveryWaitResolver = null
}

/**
 * 持续消费当前 turn 的 SSE；每次断流都先读权威快照，仍未结束才按游标和退避重连。
 * 循环只在当前 turn 未变更、页面未卸载且后端仍为非终态时继续，不会形成无上限快速请求。
 */
async function connectEventStream(turnId) {
  streamController?.abort()
  streamController = new AbortController()
  let recoveryAttempt = 0
  while (!recoveryStopped && activeTurnId.value === turnId && turnRunning.value) {
    await waitForRecovery(recoveryAttempt)
    if (recoveryStopped || activeTurnId.value !== turnId || !turnRunning.value) return
    try {
      await streamAgentTurnEvents(turnId, {
        after: lastEventId.value,
        signal: streamController.signal,
        onEvent: applyStreamEvent,
      })
    } catch (error) {
      if (error?.name === 'AbortError') return
    }
    if (recoveryStopped || activeTurnId.value !== turnId || !turnRunning.value) return
    const recovered = await recoverTurn(turnId)
    if (!recovered || !turnRunning.value) return
    recoveryAttempt += 1
  }
}

/** 用后端权威快照整体替换界面，消除流丢帧或页面刷新带来的局部状态歧义。 */
function applySnapshot(snapshot) {
  activeTurnId.value = snapshot.turnId
  turnState.value = snapshot.state
  const restored = []
  if (snapshot.userMessage) {
    restored.push({ id: `user-${snapshot.turnId}`, role: 'user', content: snapshot.userMessage })
  }
  if (snapshot.finalMessage || snapshot.partialMessage || snapshot.state !== 'RUNNING') {
    // FAILED 快照可能只带内部错误码，不应将它直接暴露给用户，也不能留下空消息让界面误以为仍在生成。
    const terminalFallback = snapshot.state === 'CANCELLED'
      ? '回答已停止。'
      : snapshot.state === 'FAILED'
        ? '这次回答没有完成，请稍后重试。'
        : ''
    restored.push({
      id: `assistant-${snapshot.turnId}`,
      role: 'assistant',
      content: snapshot.finalMessage || snapshot.partialMessage || terminalFallback,
    })
  }
  messages.value = restored
  syncPersistentTurnMetadata()
}

/**
 * 按当前用户权限回源 turn 快照，必要时再从最后 SSE 游标恢复。
 * 临时记录已过期或删除时，只清理无效指针，不会在浏览器伪造历史内容。
 */
async function recoverTurn(turnId) {
  try {
    const snapshot = await getAgentTurn(turnId)
    applySnapshot(snapshot)
    return !TERMINAL_STATES.has(snapshot.state)
  } catch (error) {
    if (error?.response?.status === 404) {
      // 只有后端明确返回不存在，才删除恢复指针；这覆盖临时过期和持久 turn 已被清理两种情况。
      sessionStorage.removeItem(TEMPORARY_TURN_KEY)
      sessionStorage.removeItem(PERSISTENT_TURN_KEY)
      activeTurnId.value = null
      turnState.value = null
      return false
    }
    // 快照网络故障不等于 turn 不存在，保留指针让外层流循环退避重试。
    return activeTurnId.value === turnId
  }
}

/**
 * 创建新 turn 并开始消费事件。临时模式下会携带当前 session 所有权标识，
 * 问题正文只存在请求内存和 Vue 响应式状态中，不写入浏览器持久存储。
 */
async function sendMessage() {
  const question = draft.value.trim()
  // turnStarting 在后端接纳前就立即置位，防止双击生成两个不同幂等键的 turn。
  if (!question || turnStarting.value || turnRunning.value) return
  if (temporaryEnabled.value && !temporarySession.value?.sessionId) {
    ElMessage.error('临时会话已经过期，请重新开启')
    return
  }

  turnStarting.value = true
  draft.value = ''
  messages.value.push({ id: `user-${Date.now()}`, role: 'user', content: question })
  messages.value.push({ id: `assistant-pending-${Date.now()}`, role: 'assistant', content: '' })
  try {
    const admission = await createAgentTurn({
      clientRequestId: newClientRequestId(),
      message: question,
      temporary: temporaryEnabled.value,
      ...(temporaryEnabled.value ? { temporarySessionId: temporarySession.value.sessionId } : {}),
      context: { page: 'agent' },
    })
    activeTurnId.value = admission.turnId
    turnState.value = admission.state
    lastEventId.value = null
    persistTemporaryMetadata()
    syncPersistentTurnMetadata()
    await connectEventStream(admission.turnId)
  } catch {
    turnState.value = 'FAILED'
    ensureAssistantMessage().content = '消息发送失败，请检查网络后重试。'
  } finally {
    turnStarting.value = false
  }
}

/** 请求后端按 run fence 取消当前回答，成功后用返回快照覆盖界面并终止流读取。 */
async function cancelTurn() {
  if (!turnRunning.value) return
  turnState.value = 'CANCELLING'
  try {
    const snapshot = await cancelAgentTurn(activeTurnId.value)
    applySnapshot(snapshot)
    streamController?.abort()
  } catch {
    ElMessage.error('暂时无法停止回答')
  }
}

/** 页面初始化时查询当前临时会话，只在后端确认仍有效时恢复模式和最后 turn。 */
async function restoreTemporaryMode() {
  sessionLoading.value = true
  try {
    const current = await getTemporarySession()
    temporarySession.value = current
    temporaryEnabled.value = true
    persistTemporaryMetadata()
    const savedTurn = Number(sessionStorage.getItem(TEMPORARY_TURN_KEY))
    if (Number.isSafeInteger(savedTurn) && savedTurn < 0) await recoverTurn(savedTurn)
  } catch (error) {
    if (error?.response?.status !== 404) ElMessage.error('临时会话状态读取失败')
    clearTemporaryMetadata()
  } finally {
    sessionLoading.value = false
  }
}

/** 恢复同一标签页中尚未结束的普通 turn；请求被后端判定不存在时才删除失效指针。 */
async function restorePersistentTurn() {
  const savedTurn = Number(sessionStorage.getItem(PERSISTENT_TURN_KEY))
  if (!Number.isSafeInteger(savedTurn) || savedTurn <= 0) return
  activeTurnId.value = savedTurn
  turnState.value = 'RUNNING'
  const running = await recoverTurn(savedTurn)
  if (running) await connectEventStream(savedTurn)
}

onMounted(async () => {
  recoveryStopped = false
  if (!localStorage.getItem('token')) {
    router.push('/login')
    return
  }
  await restoreTemporaryMode()
  if (!temporaryEnabled.value) await restorePersistentTurn()
})

onBeforeUnmount(() => {
  recoveryStopped = true
  streamController?.abort()
  if (recoveryTimer != null) window.clearTimeout(recoveryTimer)
  recoveryWaitResolver?.()
})
</script>

<style scoped lang="scss">
.agent-page {
  min-height: 100vh;
  color: var(--ink);
  background:
    linear-gradient(rgba(216, 202, 188, .32) 1px, transparent 1px),
    var(--paper-muted);
  background-size: 100% 32px;
}

.agent-topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  min-height: 72px;
  padding: 10px clamp(16px, 4vw, 52px);
  background: rgba(255, 253, 249, .97);
  border-bottom: 1px solid var(--line);
}

.back-button,
.mode-toggle {
  min-height: 42px;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--ink-muted);
  background: transparent;
  font: inherit;
  cursor: pointer;
  transition: color 160ms ease, background-color 160ms ease, transform 120ms ease;
}

.back-button {
  justify-self: start;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
}

.mode-toggle {
  justify-self: end;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-4);
  border: 1px solid var(--line);
  background: #fffdf9;
}

.mode-toggle.active {
  color: #fffaf3;
  border-color: #493f37;
  background: #493f37;
}

.back-button:active,
.mode-toggle:active { transform: scale(.96); }
.mode-toggle:disabled { cursor: not-allowed; opacity: .56; }

.agent-heading { text-align: center; }
.agent-kicker {
  display: block;
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .15em;
  text-transform: uppercase;
}
.agent-heading h1 {
  margin: 1px 0 0;
  font-size: 25px;
  line-height: 1.2;
  letter-spacing: .04em;
}

.agent-workspace {
  display: flex;
  flex-direction: column;
  width: min(920px, calc(100% - 32px));
  min-height: calc(100vh - 72px);
  margin: 0 auto;
  padding: var(--space-5) 0 var(--space-6);
  gap: var(--space-4);
}

.privacy-banner {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-4) var(--space-5);
  color: #eee7df;
  background: #493f37;
  box-shadow: 0 4px 14px rgba(41, 35, 30, .14);
}
.privacy-mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  color: #493f37;
  background: #eee4d6;
}
.privacy-copy strong {
  display: block;
  margin-bottom: 2px;
  font-family: "Songti SC", SimSun, serif;
  font-size: 18px;
}
.privacy-copy p { margin: 0; color: #d8ccc0; font-size: 13px; line-height: 1.7; }
.expiry-text { display: block; margin-top: 3px; color: #bfb1a4; font-size: 12px; font-variant-numeric: tabular-nums; }
.leave-button { min-height: 40px; color: #493f37; border: 0; background: #eee4d6; }

.conversation-panel {
  flex: 1;
  min-height: 480px;
  padding: clamp(24px, 5vw, 52px);
  border: 1px solid var(--line);
  background: #fffdf9;
  box-shadow: 0 2px 10px rgba(84, 66, 52, .08);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  min-height: 370px;
  max-width: 650px;
  margin: 0 auto;
}
.empty-seal {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  margin-bottom: var(--space-5);
  border-radius: 50%;
  color: #fffaf3;
  background: var(--accent);
  font-family: "Songti SC", SimSun, serif;
  font-size: 25px;
  box-shadow: 0 0 0 7px #f1ddd9;
}
.empty-state h2 { margin: 0 0 var(--space-3); font-size: clamp(26px, 4vw, 36px); line-height: 1.35; text-wrap: balance; }
.empty-state p { max-width: 62ch; margin: 0; color: var(--ink-muted); line-height: 1.8; text-wrap: pretty; }
.suggestion-row { display: flex; flex-wrap: wrap; gap: var(--space-2); margin-top: var(--space-5); }
.suggestion-row button {
  min-height: 40px;
  padding: 0 var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  color: var(--ink-muted);
  background: var(--paper);
  font: inherit;
  cursor: pointer;
  transition: color 160ms ease, border-color 160ms ease, transform 120ms ease;
}
.suggestion-row button:active { transform: scale(.96); }

.message-list { display: flex; flex-direction: column; gap: var(--space-5); margin: 0; padding: 0; list-style: none; }
.message { max-width: min(78%, 680px); }
.message.user { align-self: flex-end; }
.message.assistant { align-self: flex-start; }
.message-label { margin-bottom: 6px; color: var(--ink-muted); font-size: 12px; }
.message.user .message-label { text-align: right; }
.message-bubble { padding: var(--space-4) var(--space-5); border-radius: var(--radius-md); line-height: 1.8; }
.message.user .message-bubble { color: #fffaf3; background: var(--accent); border-bottom-right-radius: 2px; }
.message.assistant .message-bubble { color: var(--ink); background: var(--paper); border: 1px solid var(--line); border-bottom-left-radius: 2px; }
.message-bubble p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }

.answer-progress { display: flex; align-items: center; min-height: 27px; gap: 5px; color: var(--ink-muted); }
.answer-progress span { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); animation: thinking 1.2s ease-in-out infinite; }
.answer-progress span:nth-child(2) { animation-delay: 120ms; }
.answer-progress span:nth-child(3) { animation-delay: 240ms; }
.answer-progress em { margin-left: var(--space-2); font-size: 13px; font-style: normal; }

.composer {
  padding: var(--space-4);
  border: 1px solid var(--line);
  background: #fffdf9;
  box-shadow: 0 2px 10px rgba(84, 66, 52, .08);
}
.composer textarea {
  display: block;
  width: 100%;
  min-height: 88px;
  resize: vertical;
  padding: 2px;
  border: 0;
  outline: 0;
  color: var(--ink);
  background: transparent;
  font: inherit;
  line-height: 1.7;
}
.composer textarea::placeholder { color: #a39990; }
.composer-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--space-4); padding-top: var(--space-3); border-top: 1px solid var(--line); }
.mode-note { display: flex; align-items: center; gap: 6px; color: var(--ink-muted); font-size: 12px; }
.composer-actions { display: flex; gap: var(--space-2); }

@keyframes thinking {
  0%, 70%, 100% { opacity: .28; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-3px); }
}

@media (hover: hover) {
  .back-button:hover { color: var(--accent); background: var(--paper); }
  .mode-toggle:hover:not(:disabled) { color: var(--accent); border-color: var(--accent); }
  .mode-toggle.active:hover:not(:disabled) { color: #fffaf3; border-color: #342d28; background: #342d28; }
  .suggestion-row button:hover { color: var(--accent); border-color: var(--accent); }
}

@media (max-width: 680px) {
  .agent-topbar { grid-template-columns: auto 1fr auto; min-height: 64px; padding: 8px 12px; }
  .back-button span,
  .mode-toggle span { display: none; }
  .back-button,
  .mode-toggle { justify-content: center; width: 42px; padding: 0; }
  .agent-heading h1 { font-size: 21px; }
  .agent-kicker { font-size: 9px; }
  .agent-workspace { width: 100%; min-height: calc(100vh - 64px); padding: 0 0 max(16px, env(safe-area-inset-bottom)); gap: 0; }
  .privacy-banner { grid-template-columns: auto 1fr; padding: var(--space-3); box-shadow: none; }
  .privacy-mark { width: 36px; height: 36px; }
  .privacy-copy p { font-size: 12px; }
  .leave-button { grid-column: 2; justify-self: start; margin-top: 4px; }
  .conversation-panel { min-height: calc(100vh - 326px); padding: var(--space-5) var(--space-4); border-width: 0 0 1px; box-shadow: none; }
  .empty-state { min-height: 330px; }
  .empty-state h2 { font-size: 27px; }
  .suggestion-row { display: grid; width: 100%; }
  .suggestion-row button { text-align: left; }
  .message { max-width: 90%; }
  .message-bubble { padding: var(--space-3) var(--space-4); }
  /* 移动端保持正常文档流，避免短视口下输入区越过消息内容并遮住临时隐私提示。 */
  .composer { padding: var(--space-3); border-width: 1px 0 0; box-shadow: none; }
  .composer-footer { align-items: flex-end; }
  .mode-note { max-width: 58%; line-height: 1.45; }
}

@media (prefers-reduced-motion: reduce) {
  .answer-progress span { animation: none; opacity: .65; }
  .back-button,
  .mode-toggle,
  .suggestion-row button { transition: none; }
}
</style>
