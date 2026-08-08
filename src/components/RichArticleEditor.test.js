// @vitest-environment jsdom

import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
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
