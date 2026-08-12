<template>
  <section class="provider-settings">
    <header class="provider-settings__header">
      <div>
        <p class="provider-settings__eyebrow">模型与费用</p>
        <h2>AI API</h2>
        <p>平台提供基础额度；你也可以接入自己的 API，调用费用由对应供应商向你收取。</p>
      </div>
      <span class="provider-settings__source" :class="{ 'is-user': setting.fundingSource === 'USER' }">
        {{ setting.fundingSource === 'USER' ? '当前使用用户 API' : '当前使用平台基础额度' }}
      </span>
    </header>

    <div class="provider-settings__notice">
      <strong>密钥安全说明</strong>
      <p>API Key 仅提交给本站后端加密保存，页面不会再次取回明文。自定义地址必须是公网 HTTPS。</p>
    </div>

    <div class="provider-choices" role="radiogroup" aria-label="AI 供应商">
      <button
        v-for="option in providers"
        :key="option.value"
        :data-test="`provider-${option.value.toLowerCase()}`"
        type="button"
        :class="{ 'is-active': form.provider === option.value }"
        @click="selectProvider(option)"
      >
        <strong>{{ option.label }}</strong>
        <span>{{ option.description }}</span>
      </button>
    </div>

    <form class="provider-form" @submit.prevent="save">
      <label v-if="form.provider === 'CUSTOM'">
        <span>OpenAI 兼容地址</span>
        <input data-test="provider-base-url" v-model.trim="form.baseUrl" type="url" required placeholder="https://models.example.com/v1" />
      </label>
      <label>
        <span>模型名称</span>
        <input data-test="provider-model" v-model.trim="form.model" required maxlength="128" placeholder="例如 gpt-4.1-mini" />
      </label>
      <label>
        <span>{{ setting.configured ? '替换 API Key（留空则保留原密钥）' : 'API Key' }}</span>
        <input data-test="provider-key" v-model="form.apiKey" type="password" autocomplete="new-password" :required="!setting.configured" placeholder="只在保存时发送一次" />
        <small v-if="setting.keyHint">已保存：{{ setting.keyHint }}</small>
      </label>

      <div class="provider-actions">
        <button data-test="save-provider" type="button" class="is-primary" :disabled="saving" @click="save">
          {{ saving ? '正在保存' : '保存并使用' }}
        </button>
        <button v-if="setting.configured && setting.enabled" data-test="test-provider" type="button" :disabled="testing" @click="testConnection">
          {{ testing ? '正在测试' : '测试连接' }}
        </button>
        <el-switch
          v-if="setting.configured"
          data-test="enabled-switch"
          :model-value="setting.enabled"
          active-text="已启用"
          inactive-text="已停用"
          @change="toggleEnabled"
        />
        <button v-if="setting.configured" data-test="delete-provider" type="button" class="is-danger" @click="remove">
          删除配置
        </button>
      </div>
    </form>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteAiProviderSetting,
  getAiProviderSetting,
  saveAiProviderSetting,
  setAiProviderEnabled,
  testAiProviderConnection,
} from '../api/agent'

const providers = Object.freeze([
  { value: 'OPENAI', label: 'OpenAI', description: 'GPT 系列', model: 'gpt-4.1-mini' },
  { value: 'DEEPSEEK', label: 'DeepSeek', description: 'DeepSeek Chat', model: 'deepseek-chat' },
  { value: 'QWEN', label: '通义千问', description: 'DashScope 兼容接口', model: 'qwen-plus' },
  { value: 'CUSTOM', label: '自定义', description: 'OpenAI 兼容 HTTPS', model: '' },
])
const setting = reactive({ configured: false, provider: null, baseUrl: null, model: null, keyHint: null, enabled: false, fundingSource: 'PLATFORM' })
const form = reactive({ provider: 'OPENAI', baseUrl: '', model: 'gpt-4.1-mini', apiKey: '' })
const saving = ref(false)
const testing = ref(false)

/** 将后端脱敏视图同步到表单，绝不尝试构造或回填 API Key。 */
function applySetting(value) {
  Object.assign(setting, value || {})
  if (value?.configured) {
    form.provider = value.provider
    form.baseUrl = value.baseUrl || ''
    form.model = value.model || ''
  }
  form.apiKey = ''
}

function selectProvider(option) {
  form.provider = option.value
  if (option.model) form.model = option.model
  if (option.value !== 'CUSTOM') form.baseUrl = ''
}

async function load() {
  try {
    applySetting(await getAiProviderSetting())
  } catch {
    ElMessage.error('AI 配置加载失败')
  }
}

async function save() {
  saving.value = true
  try {
    applySetting(await saveAiProviderSetting({ ...form, enabled: true }))
    ElMessage.success('AI API 已加密保存')
  } catch {
    ElMessage.error('保存失败，请检查模型、密钥和地址')
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  testing.value = true
  try {
    const result = await testAiProviderConnection()
    ElMessage.success(`连接成功：${result.provider} · ${result.model}`)
  } catch {
    ElMessage.error('连接失败，请检查额度、密钥与模型名称')
  } finally {
    testing.value = false
  }
}

async function toggleEnabled(enabled) {
  try {
    applySetting(await setAiProviderEnabled(enabled))
    ElMessage.success(enabled ? '已切换到用户 API' : '已切换到平台基础额度')
  } catch {
    ElMessage.error('状态切换失败')
  }
}

async function remove() {
  try {
    await ElMessageBox.confirm('将永久删除服务器保存的加密凭据，是否继续？', '删除 AI API', { type: 'warning' })
    await deleteAiProviderSetting()
    applySetting({ configured: false, provider: null, baseUrl: null, model: null, keyHint: null, enabled: false, fundingSource: 'PLATFORM' })
    ElMessage.success('AI API 配置已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('删除失败')
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.provider-settings { max-width: 660px; color: #29231e; }
.provider-settings__header { display: flex; justify-content: space-between; gap: 26px; border-bottom: 1px solid #d8cabc; padding-bottom: 22px; }
.provider-settings__eyebrow { margin: 0 0 5px !important; color: #a55245 !important; font-size: 11px !important; font-weight: 700; letter-spacing: .14em; }
.provider-settings__header h2 { margin: 0; font: 700 27px/1.25 "Songti SC", SimSun, serif; }
.provider-settings__header p { margin: 8px 0 0; max-width: 450px; color: #766d64; font-size: 13px; line-height: 1.7; }
.provider-settings__source { align-self: flex-start; flex: 0 0 auto; padding: 6px 9px; border: 1px solid #d8cabc; color: #766d64; font-size: 11px; }
.provider-settings__source.is-user { border-color: #a55245; background: #f8eee8; color: #7f3d34; }
.provider-settings__notice { margin: 20px 0; padding: 13px 15px; border-left: 3px solid #a55245; background: #f8f1e8; }
.provider-settings__notice strong { font-size: 12px; }
.provider-settings__notice p { margin: 5px 0 0; color: #766d64; font-size: 11px; line-height: 1.7; }
.provider-choices { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.provider-choices button { min-height: 76px; padding: 11px; border: 1px solid #d8cabc; border-radius: 5px; background: #fffdf9; text-align: left; cursor: pointer; }
.provider-choices button.is-active { border-color: #a55245; background: #f8eee8; box-shadow: inset 0 -2px #a55245; }
.provider-choices strong, .provider-choices span { display: block; }
.provider-choices strong { font-size: 13px; }
.provider-choices span { margin-top: 6px; color: #766d64; font-size: 10px; line-height: 1.4; }
.provider-form { display: grid; gap: 16px; margin-top: 22px; }
.provider-form label > span { display: block; margin-bottom: 7px; font-size: 12px; font-weight: 700; }
.provider-form input { box-sizing: border-box; width: 100%; height: 42px; padding: 0 12px; border: 1px solid #cbb8a8; border-radius: 5px; outline: 0; background: #fffdf9; color: #29231e; }
.provider-form input:focus { border-color: #a55245; box-shadow: 0 0 0 2px rgba(165, 82, 69, .12); }
.provider-form small { display: block; margin-top: 6px; color: #766d64; }
.provider-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding-top: 4px; }
.provider-actions > button { min-height: 38px; padding: 0 15px; border: 1px solid #cbb8a8; border-radius: 5px; background: #fffdf9; color: #29231e; cursor: pointer; }
.provider-actions > .is-primary { border-color: #a55245; background: #a55245; color: #fff; }
.provider-actions > .is-danger { margin-left: auto; border-color: transparent; color: #a55245; }
@media (max-width: 720px) {
  .provider-settings__header { display: block; }
  .provider-settings__source { display: inline-block; margin-top: 12px; }
  .provider-choices { grid-template-columns: 1fr 1fr; }
}
</style>
