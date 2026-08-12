// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  getAgentMemories: vi.fn(),
  getAgentMemorySetting: vi.fn(),
  updateAgentMemory: vi.fn(),
  setAgentMemoryState: vi.fn(),
  updateAgentMemorySetting: vi.fn(),
  deleteAgentMemory: vi.fn(),
  confirm: vi.fn(),
}))

vi.mock('../api/agent', () => ({
  getAgentMemories: mocks.getAgentMemories,
  getAgentMemorySetting: mocks.getAgentMemorySetting,
  updateAgentMemory: mocks.updateAgentMemory,
  setAgentMemoryState: mocks.setAgentMemoryState,
  updateAgentMemorySetting: mocks.updateAgentMemorySetting,
  deleteAgentMemory: mocks.deleteAgentMemory,
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: mocks.confirm },
}))

const { default: AgentMemoryCenter } = await import('./AgentMemoryCenter.vue')

beforeEach(() => {
  Object.values(mocks).forEach(mock => mock.mockReset())
  mocks.getAgentMemorySetting.mockResolvedValue({ enabled: true, version: 0 })
  mocks.getAgentMemories.mockResolvedValue([
    { id: 7, category: 'PREFERENCE', content: '我喜欢简洁的回答', version: 1, state: 'ACTIVE' },
    { id: 8, category: 'GOAL', content: '正在学习数据库事务', version: 3, state: 'PAUSED' },
  ])
  mocks.confirm.mockResolvedValue('confirm')
})

describe('桌宠长期记忆中心', () => {
  it('shows active and paused memories and lets the owner pause then resume one item', async () => {
    mocks.setAgentMemoryState
      .mockResolvedValueOnce({ id: 7, category: 'PREFERENCE', content: '我喜欢简洁的回答', version: 1, state: 'PAUSED' })
      .mockResolvedValueOnce({ id: 7, category: 'PREFERENCE', content: '我喜欢简洁的回答', version: 1, state: 'ACTIVE' })
    const wrapper = mount(AgentMemoryCenter)
    await flushPromises()

    expect(wrapper.text()).toContain('我喜欢简洁的回答')
    expect(wrapper.text()).toContain('已暂停')
    await wrapper.get('[data-test="memory-state-7"]').trigger('click')
    await flushPromises()
    expect(mocks.setAgentMemoryState).toHaveBeenCalledWith(7, { paused: true, expectedVersion: 1 })
    expect(wrapper.get('[data-test="memory-state-7"]').text()).toContain('恢复')

    await wrapper.get('[data-test="memory-state-7"]').trigger('click')
    await flushPromises()
    expect(mocks.setAgentMemoryState).toHaveBeenLastCalledWith(7, { paused: false, expectedVersion: 1 })
  })

  it('edits with the current version, disables all recall, and deletes after confirmation', async () => {
    mocks.updateAgentMemory.mockResolvedValue({
      id: 7, category: 'PREFERENCE', content: '请先给结论', version: 2, state: 'ACTIVE',
    })
    mocks.updateAgentMemorySetting.mockResolvedValue({ enabled: false, version: 1 })
    mocks.deleteAgentMemory.mockResolvedValue(undefined)
    const wrapper = mount(AgentMemoryCenter)
    await flushPromises()

    await wrapper.get('[data-test="memory-edit-7"]').trigger('click')
    await wrapper.get('[data-test="memory-edit-input-7"]').setValue('请先给结论')
    await wrapper.get('[data-test="memory-save-7"]').trigger('click')
    await flushPromises()
    expect(mocks.updateAgentMemory).toHaveBeenCalledWith(7, {
      content: '请先给结论', expectedVersion: 1,
    })

    await wrapper.get('[data-test="memory-master-toggle"]').trigger('click')
    await flushPromises()
    expect(mocks.updateAgentMemorySetting).toHaveBeenCalledWith({ enabled: false, expectedVersion: 0 })

    await wrapper.get('[data-test="memory-delete-7"]').trigger('click')
    await flushPromises()
    expect(mocks.deleteAgentMemory).toHaveBeenCalledWith(7)
    expect(wrapper.text()).not.toContain('请先给结论')
  })
})
