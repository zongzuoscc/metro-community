<template>
  <section class="memory-center" aria-label="Agent 长期记忆中心">
    <!-- 总开关只控制是否允许 Agent 召回记忆，不会删除用户已经保存的内容。 -->
    <header class="memory-center__heading">
      <div>
        <small>MEMORY CONTROL</small>
        <h2>你的长期记忆</h2>
        <p>只保存提炼后的偏好与目标，不复制整段聊天。</p>
      </div>
      <button
        data-test="memory-master-toggle"
        type="button"
        class="memory-switch"
        :class="{ 'is-enabled': setting.enabled }"
        :disabled="loading || savingSetting"
        :aria-pressed="setting.enabled"
        @click="toggleSetting"
      >
        <i aria-hidden="true"></i>
        {{ setting.enabled ? '记忆已开启' : '记忆已暂停' }}
      </button>
    </header>

    <!-- 搜索只在已加载的本人记忆中进行，不会把关键词发送给模型。 -->
    <div class="memory-toolbar">
      <input v-model.trim="query" type="search" aria-label="搜索长期记忆" placeholder="搜索记忆内容" />
      <span>{{ filteredMemories.length }} 条</span>
    </div>

    <div v-if="loading" class="memory-center__state">正在读取你的记忆…</div>
    <div v-else-if="loadFailed" class="memory-center__state is-error">
      <p>暂时无法读取长期记忆。</p>
      <button type="button" @click="load">重试</button>
    </div>
    <div v-else-if="filteredMemories.length === 0" class="memory-center__state">
      <p>{{ query ? '没有匹配的记忆' : '还没有长期记忆' }}</p>
      <small>普通对话中明确表达的低风险偏好和目标会自动出现在这里。</small>
    </div>

    <!-- 单条记忆的编辑、暂停和删除均使用服务端版本校验，防止多页面并发覆盖。 -->
    <div v-else class="memory-list">
      <article v-for="memory in filteredMemories" :key="memory.id" class="memory-row" :class="{ 'is-paused': memory.state === 'PAUSED' }">
        <header>
          <span>{{ categoryLabel(memory.category) }}</span>
          <small>{{ memory.state === 'PAUSED' ? '已暂停' : `版本 ${memory.version}` }}</small>
        </header>

        <template v-if="editingId === memory.id">
          <textarea
            :data-test="`memory-edit-input-${memory.id}`"
            v-model="editingContent"
            rows="3"
            maxlength="1000"
            aria-label="编辑记忆内容"
          ></textarea>
          <div class="memory-row__edit-actions">
            <button :data-test="`memory-save-${memory.id}`" type="button" class="is-primary" :disabled="busyId === memory.id || !editingContent.trim()" @click="saveEdit(memory)">保存</button>
            <button type="button" @click="cancelEdit">取消</button>
          </div>
        </template>
        <template v-else>
          <p>{{ memory.content }}</p>
          <footer>
            <button :data-test="`memory-edit-${memory.id}`" type="button" :disabled="busyId === memory.id" @click="beginEdit(memory)">编辑</button>
            <button :data-test="`memory-state-${memory.id}`" type="button" :disabled="busyId === memory.id" @click="toggleState(memory)">
              {{ memory.state === 'PAUSED' ? '恢复' : '暂停' }}
            </button>
            <button :data-test="`memory-delete-${memory.id}`" type="button" class="is-danger" :disabled="busyId === memory.id" @click="remove(memory)">删除</button>
          </footer>
        </template>
      </article>
    </div>

    <p class="memory-center__privacy">临时对话永远不会读取、提取或修改这里的内容。</p>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteAgentMemory,
  getAgentMemories,
  getAgentMemorySetting,
  setAgentMemoryState,
  updateAgentMemory,
  updateAgentMemorySetting,
} from '../api/agent'

const memories = ref([])
const setting = reactive({ enabled: true, version: 0 })
const query = ref('')
const loading = ref(true)
const loadFailed = ref(false)
const savingSetting = ref(false)
const busyId = ref(null)
const editingId = ref(null)
const editingContent = ref('')

const filteredMemories = computed(() => {
  const keyword = query.value.toLowerCase()
  if (!keyword) return memories.value
  return memories.value.filter(memory => (
    memory.content.toLowerCase().includes(keyword)
      || categoryLabel(memory.category).includes(keyword)
  ))
})

function categoryLabel(category) {
  return ({ PREFERENCE: '偏好与边界', GOAL: '目标与项目', PROFILE: '个人信息' })[category] || '其他记忆'
}

/** 设置与记忆并行读取；任一失败都显示可重试状态，避免渲染不完整的隐私控制界面。 */
async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const [currentSetting, currentMemories] = await Promise.all([
      getAgentMemorySetting(),
      getAgentMemories(),
    ])
    Object.assign(setting, currentSetting)
    memories.value = currentMemories
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function toggleSetting() {
  if (savingSetting.value) return
  savingSetting.value = true
  try {
    Object.assign(setting, await updateAgentMemorySetting({
      enabled: !setting.enabled,
      expectedVersion: setting.version,
    }))
    ElMessage.success(setting.enabled ? '长期记忆已开启' : '长期记忆已暂停')
  } catch {
    ElMessage.error('记忆开关更新失败，请刷新后重试')
  } finally {
    savingSetting.value = false
  }
}

/** 编辑框使用内容副本；在用户点击保存前，列表中的服务端事实保持不变。 */
function beginEdit(memory) {
  editingId.value = memory.id
  editingContent.value = memory.content
}

/** 取消时彻底清理草稿，避免下次编辑其他记忆时混入旧内容。 */
function cancelEdit() {
  editingId.value = null
  editingContent.value = ''
}

/** 保存成功后用后端返回的新版本替换本地记忆，作为下次乐观锁操作的基线。 */
async function saveEdit(memory) {
  busyId.value = memory.id
  try {
    const updated = await updateAgentMemory(memory.id, {
      content: editingContent.value.trim(),
      expectedVersion: memory.version,
    })
    replace(updated)
    cancelEdit()
    ElMessage.success('记忆已更新')
  } catch {
    ElMessage.error('记忆更新失败，可能已在其他页面修改')
  } finally {
    busyId.value = null
  }
}

/** 暂停不删除内容，恢复后仍使用同一条不可变内容版本。 */
async function toggleState(memory) {
  busyId.value = memory.id
  try {
    const updated = await setAgentMemoryState(memory.id, {
      paused: memory.state !== 'PAUSED',
      expectedVersion: memory.version,
    })
    replace(updated)
    ElMessage.success(updated.state === 'PAUSED' ? '这条记忆已暂停使用' : '这条记忆已恢复使用')
  } catch {
    ElMessage.error('记忆状态更新失败，请刷新后重试')
  } finally {
    busyId.value = null
  }
}

/** 删除是破坏性操作，必须先二次确认；用户取消不作为异常弹窗。 */
async function remove(memory) {
  try {
    await ElMessageBox.confirm('删除后 Agent 将立即停止使用这条记忆，是否继续？', '删除长期记忆', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
    busyId.value = memory.id
    await deleteAgentMemory(memory.id)
    memories.value = memories.value.filter(item => item.id !== memory.id)
    if (editingId.value === memory.id) cancelEdit()
    ElMessage.success('记忆已删除')
  } catch (error) {
    // Element Plus 取消确认时会拒绝 Promise；取消不是错误，也不需要打扰用户。
    if (error !== 'cancel' && error !== 'close') ElMessage.error('记忆删除失败，请稍后重试')
  } finally {
    busyId.value = null
  }
}

/** 保持列表顺序不变，只替换操作目标，避免界面焦点和滚动位置跳动。 */
function replace(updated) {
  memories.value = memories.value.map(memory => memory.id === updated.id ? updated : memory)
}

onMounted(load)
</script>

<style scoped lang="scss">
/* 记忆中心沿用桌宠小窗的纸张色系，同时保持内部独立滚动，不撑开外层悬浮窗。 */
.memory-center { display: grid; min-height: 0; overflow: auto; padding: 18px; color: #29231e; background: #fffdf9; }
.memory-center__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 14px; border-bottom: 1px solid #e2d7cc; }
.memory-center__heading small { color: #a55245; font-size: 9px; font-weight: 750; letter-spacing: .13em; }
.memory-center__heading h2 { margin: 4px 0 3px; font: 700 19px/1.25 "Songti SC", STSong, SimSun, serif; }
.memory-center__heading p { margin: 0; color: #766d64; font-size: 10px; line-height: 1.55; }
.memory-switch { display: flex; align-items: center; gap: 7px; min-height: 32px; padding: 0 9px; border: 1px solid #d0c1b3; border-radius: 4px; background: #f7f0e8; color: #766d64; font-size: 10px; cursor: pointer; }
.memory-switch i { width: 20px; height: 11px; border-radius: 8px; background: #b8ada4; box-shadow: inset 9px 0 #fff; transition: .16s ease; }
.memory-switch.is-enabled { color: #7f3d34; border-color: #c98a7f; }
.memory-switch.is-enabled i { background: #a55245; box-shadow: inset -9px 0 #fff; }
.memory-toolbar { display: flex; align-items: center; gap: 10px; margin: 14px 0 8px; }
.memory-toolbar input { min-width: 0; flex: 1; height: 34px; padding: 0 10px; border: 1px solid #d8cabc; border-radius: 4px; outline: none; background: #fff; }
.memory-toolbar input:focus { border-color: #a55245; }
.memory-toolbar span { color: #8b8178; font-size: 10px; }
.memory-center__state { display: grid; place-content: center; min-height: 180px; color: #766d64; text-align: center; }
.memory-center__state p { margin: 0 0 7px; }
.memory-center__state small { max-width: 260px; line-height: 1.6; }
.memory-center__state button { justify-self: center; padding: 5px 12px; border: 1px solid #cbb8a8; background: #fff; cursor: pointer; }
.memory-list { border-top: 1px solid #e9dfd5; }
.memory-row { padding: 12px 2px; border-bottom: 1px solid #e9dfd5; }
.memory-row.is-paused { opacity: .62; }
.memory-row header { display: flex; justify-content: space-between; gap: 10px; color: #8c493f; font-size: 10px; font-weight: 700; }
.memory-row header small { color: #8b8178; font-weight: 400; }
.memory-row p { margin: 7px 0 9px; font: 400 12px/1.65 "Songti SC", STSong, SimSun, serif; white-space: pre-wrap; }
.memory-row footer, .memory-row__edit-actions { display: flex; justify-content: flex-end; gap: 4px; }
.memory-row button { min-height: 28px; padding: 0 8px; border: 1px solid transparent; border-radius: 3px; background: transparent; color: #766d64; font-size: 10px; cursor: pointer; }
.memory-row button:hover { background: #f6eee5; color: #29231e; }
.memory-row button.is-primary { border-color: #a55245; background: #a55245; color: #fff; }
.memory-row button.is-danger { color: #a55245; }
.memory-row textarea { box-sizing: border-box; width: 100%; margin: 8px 0; padding: 8px; resize: vertical; border: 1px solid #cbb8a8; border-radius: 4px; font: inherit; }
.memory-center__privacy { margin: 14px 0 0; padding: 9px 10px; border-left: 2px solid #a55245; background: #f8f1e8; color: #766d64; font-size: 9px; line-height: 1.55; }
/* 手机窄屏下将总开关换行，防止标题与按钮互相挤压。 */
@media (max-width: 520px) {
  .memory-center { padding: 14px; }
  .memory-center__heading { display: grid; }
  .memory-switch { justify-self: start; }
}
</style>
