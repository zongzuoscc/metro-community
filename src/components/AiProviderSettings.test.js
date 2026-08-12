// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getAiProviderSetting: vi.fn(),
  saveAiProviderSetting: vi.fn(),
  setAiProviderEnabled: vi.fn(),
  testAiProviderConnection: vi.fn(),
  deleteAiProviderSetting: vi.fn(),
}))

vi.mock('../api/agent', () => api)
vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn().mockResolvedValue('confirm'),
  },
}))
const { default: AiProviderSettings } = await import('./AiProviderSettings.vue')

beforeEach(() => Object.values(api).forEach(mock => mock.mockReset()))

function mountSettings() {
  return mount(AiProviderSettings, {
    global: {
      stubs: {
        ElAlert: { template: '<div><slot /><slot name="title" /></div>' },
        ElSwitch: { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<button data-test="enabled-switch" @click="$emit(\'change\', !modelValue)">{{ modelValue }}</button>' },
      },
    },
  })
}

describe('用户 AI API 设置', () => {
  it('未配置时明确展示平台基础额度，并可保存用户密钥但不回显明文', async () => {
    api.getAiProviderSetting.mockResolvedValue({ configured: false, fundingSource: 'PLATFORM' })
    api.saveAiProviderSetting.mockResolvedValue({
      configured: true, provider: 'OPENAI', model: 'gpt-4.1-mini', keyHint: '••••cret',
      enabled: true, fundingSource: 'USER',
    })
    const wrapper = mountSettings()
    await flushPromises()
    expect(wrapper.text()).toContain('平台基础额度')

    await wrapper.get('[data-test="provider-openai"]').trigger('click')
    await wrapper.get('[data-test="provider-model"]').setValue('gpt-4.1-mini')
    await wrapper.get('[data-test="provider-key"]').setValue('sk-browser-secret')
    await wrapper.get('[data-test="save-provider"]').trigger('click')
    await flushPromises()

    expect(api.saveAiProviderSetting).toHaveBeenCalledWith(expect.objectContaining({
      provider: 'OPENAI', apiKey: 'sk-browser-secret', enabled: true,
    }))
    expect(wrapper.text()).toContain('••••cret')
    expect(wrapper.text()).not.toContain('sk-browser-secret')
  })

  it('自定义供应商必须显示 HTTPS 地址输入，并提供测试、停用和删除入口', async () => {
    api.getAiProviderSetting.mockResolvedValue({
      configured: true, provider: 'CUSTOM', baseUrl: 'https://models.example.com/v1',
      model: 'community-model', keyHint: '••••1234', enabled: true, fundingSource: 'USER',
    })
    api.testAiProviderConnection.mockResolvedValue({ connected: true, provider: 'custom', model: 'community-model' })
    api.setAiProviderEnabled.mockResolvedValue({ enabled: false, fundingSource: 'PLATFORM' })
    api.deleteAiProviderSetting.mockResolvedValue(undefined)
    const wrapper = mountSettings()
    await flushPromises()

    expect(wrapper.find('[data-test="provider-base-url"]').exists()).toBe(true)
    await wrapper.get('[data-test="test-provider"]').trigger('click')
    await flushPromises()
    expect(api.testAiProviderConnection).toHaveBeenCalled()

    await wrapper.get('[data-test="enabled-switch"]').trigger('click')
    await flushPromises()
    expect(api.setAiProviderEnabled).toHaveBeenCalledWith(false)

    await wrapper.get('[data-test="delete-provider"]').trigger('click')
    await flushPromises()
    expect(api.deleteAiProviderSetting).toHaveBeenCalled()
  })
})
