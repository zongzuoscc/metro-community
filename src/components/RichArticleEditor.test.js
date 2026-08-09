// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RichArticleEditor from './RichArticleEditor.vue'

const svgDataUrl =
  'data:image/svg+xml,%3Csvg%20xmlns=%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox=%220%200%201%201%22%3E%3C%2Fsvg%3E'
const blobUrl = 'blob:https://metro.example/550e8400-e29b-41d4-a716-446655440000'
const safeImageUrl = 'https://metro.example/assets/station-map.png'

function unsafeImageMarkdown(prefix) {
  return `![${prefix}直接](${svgDataUrl})\n\n![${prefix}尖括号](<${blobUrl}>)\n\n![${prefix}引用][${prefix}-image]\n\n[${prefix}-image]: <${svgDataUrl}> "${prefix}引用图"`
}

async function waitForEditor() {
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}

function expectSanitizedEditor(wrapper, prefix) {
  const editor = wrapper.get('[aria-label="文章正文"]')

  expect(editor.text()).toContain(`${prefix}直接`)
  expect(editor.text()).toContain(`${prefix}尖括号`)
  expect(editor.text()).toContain(`${prefix}引用`)
  expect(editor.html()).not.toContain('data:')
  expect(editor.html()).not.toContain('blob:')
  expect(editor.findAll('img')).toHaveLength(1)
  expect(editor.get('img').attributes('src')).toBe(safeImageUrl)
  expect(editor.get('a').attributes('href')).toBe('https://metro.example/guide')
}

let wrapper

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
})

describe('RichArticleEditor Markdown image sanitization', () => {
  it('sanitizes unsafe direct, angle-bracket, and reference images while parsing the initial modelValue', async () => {
    wrapper = mount(RichArticleEditor, {
      props: {
        modelValue: [
          unsafeImageMarkdown('初始'),
          '[乘车指南](https://metro.example/guide)',
          `![安全线路图](${safeImageUrl})`,
        ].join('\n\n'),
      },
    })
    await waitForEditor()

    expectSanitizedEditor(wrapper, '初始')
  })

  it('sanitizes unsafe direct, angle-bracket, and reference images after a modelValue prop update', async () => {
    wrapper = mount(RichArticleEditor, {
      props: {
        modelValue: '[编辑前文本](https://metro.example/guide)',
      },
    })
    await waitForEditor()

    await wrapper.setProps({
      modelValue: [
        unsafeImageMarkdown('更新'),
        '[乘车指南](https://metro.example/guide)',
        `![安全线路图](${safeImageUrl})`,
      ].join('\n\n'),
    })
    await nextTick()

    expectSanitizedEditor(wrapper, '更新')
  })
})

describe('RichArticleEditor legacy Markdown protection', () => {
  it('protects raw HTML loaded after the editor has mounted', async () => {
    const legacyMarkdown = '旧正文\n\n<iframe src="https://video.example/embed"></iframe>'
    wrapper = mount(RichArticleEditor, {
      props: { modelValue: '' },
    })
    await waitForEditor()

    await wrapper.setProps({ modelValue: legacyMarkdown })
    await nextTick()

    expect(wrapper.get('[role="alert"]').exists()).toBe(true)
    expect(wrapper.get('textarea[readonly]').element.value).toBe(legacyMarkdown)
    expect(wrapper.emitted('legacy-protection')?.at(-1)).toEqual([true])
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('keeps raw HTML out of v-model until the author explicitly accepts conversion', async () => {
    const legacyMarkdown = [
      '# 旧文章',
      '<style>.legacy { color: red; }</style>',
      '<iframe src="https://video.example/embed"></iframe>',
    ].join('\n\n')
    wrapper = mount(RichArticleEditor, {
      props: { modelValue: legacyMarkdown },
    })
    await waitForEditor()

    expect(wrapper.get('[role="alert"]').text()).toContain('无法无损处理')
    expect(wrapper.get('textarea[readonly]').element.value).toBe(legacyMarkdown)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('legacy-protection')?.at(-1)).toEqual([true])

    await wrapper.get('[aria-label="插入三行三列表格"]').trigger('click')
    await nextTick()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    const confirmConversion = vi.spyOn(window, 'confirm').mockReturnValueOnce(false)
    await wrapper.get('[data-testid="convert-legacy-markdown"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').exists()).toBe(true)

    confirmConversion.mockReturnValueOnce(true)
    await wrapper.get('[data-testid="convert-legacy-markdown"]').trigger('click')
    expect(wrapper.emitted('legacy-protection')?.at(-1)).toEqual([false])
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')[0][0]).not.toContain('<iframe')
    expect(wrapper.emitted('update:modelValue')[0][0]).not.toContain('<style')
  })
})
