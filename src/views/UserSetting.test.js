// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserSetting from './UserSetting.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), push: vi.fn(), confirm: vi.fn()
}))

vi.mock('../utils/request', () => ({
  default: { get: mocks.get, post: mocks.post }
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: mocks.confirm }
}))
vi.mock('../utils/websocket', () => ({ closeWebSocket: vi.fn() }))

const stubs = {
  'el-tabs': { template: '<div><slot /></div>' },
  'el-tab-pane': { template: '<section><slot /></section>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-button': { template: '<button><slot /></button>' },
  'el-divider': { template: '<hr />' },
  'el-link': { template: '<a><slot /></a>' },
  'el-upload': { template: '<div><slot /></div>' },
  'el-icon': { template: '<i><slot /></i>' },
  ArrowLeft: true,
  Plus: true,
  AiProviderSettings: true
}

describe('账号注销设置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mocks.get.mockImplementation((url) => {
      if (url === '/api/user/info') return Promise.resolve({ code: 200, data: { id: 7, username: '用户' } })
      if (url === '/api/user/account-deletion') return Promise.resolve({
        code: 200, data: { state: 'ACTIVE', requestedAt: null, purgeAfter: null }
      })
      throw new Error(`unexpected GET ${url}`)
    })
  })

  it('确认后申请七天待删除并退出当前登录', async () => {
    mocks.confirm.mockResolvedValue()
    mocks.post.mockResolvedValue({
      code: 200, data: { state: 'PENDING_DELETE', purgeAfter: '2026-08-21T12:00:00' }
    })
    localStorage.setItem('token', 'jwt')
    const wrapper = mount(UserSetting, { global: { stubs } })
    await flushPromises()

    await wrapper.get('[data-test="request-account-deletion"]').trigger('click')
    await flushPromises()

    expect(mocks.post).toHaveBeenCalledWith('/api/user/account-deletion/request', {
      confirmation: 'DELETE_MY_ACCOUNT'
    })
    expect(localStorage.getItem('token')).toBeNull()
    expect(mocks.push).toHaveBeenCalledWith('/login')
  })

  it('待删除状态会展示截止时间并允许恢复', async () => {
    mocks.get.mockImplementation((url) => {
      if (url === '/api/user/info') return Promise.resolve({ code: 200, data: { id: 7, username: '用户' } })
      return Promise.resolve({
        code: 200, data: { state: 'PENDING_DELETE', purgeAfter: '2026-08-21T12:00:00' }
      })
    })
    mocks.post.mockResolvedValue({ code: 200, data: { state: 'ACTIVE', purgeAfter: null } })
    const wrapper = mount(UserSetting, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('2026')
    await wrapper.get('[data-test="restore-account"]').trigger('click')
    await flushPromises()

    expect(mocks.post).toHaveBeenCalledWith('/api/user/account-deletion/restore')
    expect(wrapper.find('[data-test="request-account-deletion"]').exists()).toBe(true)
  })
})
