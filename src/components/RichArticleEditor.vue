<template>
  <section class="article-editor" aria-label="文章正文编辑器">
    <div class="article-editor__toolbar" role="toolbar" aria-label="正文格式工具">
      <div class="article-editor__tool-group" aria-label="文字格式">
        <button
          type="button"
          class="article-editor__tool"
          :class="{ 'is-active': editor?.isActive('bold') }"
          :aria-pressed="editor?.isActive('bold')"
          aria-label="粗体"
          title="粗体 (Ctrl/Cmd+B)"
          @mousedown.prevent
          @click="editor?.chain().focus().toggleBold().run()"
        >
          <strong>B</strong>
        </button>
        <button
          type="button"
          class="article-editor__tool"
          :class="{ 'is-active': editor?.isActive('italic') }"
          :aria-pressed="editor?.isActive('italic')"
          aria-label="斜体"
          title="斜体 (Ctrl/Cmd+I)"
          @mousedown.prevent
          @click="editor?.chain().focus().toggleItalic().run()"
        >
          <em>I</em>
        </button>
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :class="{ 'is-active': editor?.isActive('heading', { level: 2 }) }"
          :aria-pressed="editor?.isActive('heading', { level: 2 })"
          aria-label="二级标题"
          title="二级标题"
          @mousedown.prevent
          @click="editor?.chain().focus().toggleHeading({ level: 2 }).run()"
        >
          H2
        </button>
      </div>

      <div class="article-editor__tool-group" aria-label="段落格式">
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :class="{ 'is-active': editor?.isActive('bulletList') }"
          :aria-pressed="editor?.isActive('bulletList')"
          aria-label="无序列表"
          title="无序列表"
          @mousedown.prevent
          @click="editor?.chain().focus().toggleBulletList().run()"
        >
          • 列表
        </button>
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :class="{ 'is-active': editor?.isActive('orderedList') }"
          :aria-pressed="editor?.isActive('orderedList')"
          aria-label="有序列表"
          title="有序列表"
          @mousedown.prevent
          @click="editor?.chain().focus().toggleOrderedList().run()"
        >
          1. 列表
        </button>
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :class="{ 'is-active': editor?.isActive('blockquote') }"
          :aria-pressed="editor?.isActive('blockquote')"
          aria-label="引用"
          title="引用"
          @mousedown.prevent
          @click="editor?.chain().focus().toggleBlockquote().run()"
        >
          引用
        </button>
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :class="{ 'is-active': editor?.isActive('codeBlock') }"
          :aria-pressed="editor?.isActive('codeBlock')"
          aria-label="代码块"
          title="代码块"
          @mousedown.prevent
          @click="editor?.chain().focus().toggleCodeBlock().run()"
        >
          &lt;/&gt;
        </button>
      </div>

      <div class="article-editor__tool-group" aria-label="插入内容">
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :class="{ 'is-active': editor?.isActive('link') }"
          :aria-pressed="editor?.isActive('link')"
          aria-label="添加或编辑链接"
          title="添加或编辑链接"
          @mousedown.prevent
          @click="setLink"
        >
          链接
        </button>
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          aria-label="插入三行三列表格"
          title="插入 3 × 3 表格"
          @mousedown.prevent
          @click="editor?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()"
        >
          表格
        </button>
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          aria-label="上传并插入图片"
          title="上传并插入图片"
          @mousedown.prevent
          @click="openImagePicker"
        >
          图片
        </button>
        <input
          ref="imageInput"
          class="article-editor__file-input"
          type="file"
          accept="image/*"
          tabindex="-1"
          aria-hidden="true"
          @change="handleImageSelection"
        />
      </div>

      <div class="article-editor__tool-group" aria-label="操作历史">
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :disabled="!editor?.can().undo()"
          aria-label="撤销"
          title="撤销 (Ctrl/Cmd+Z)"
          @mousedown.prevent
          @click="editor?.chain().focus().undo().run()"
        >
          撤销
        </button>
        <button
          type="button"
          class="article-editor__tool article-editor__tool--text"
          :disabled="!editor?.can().redo()"
          aria-label="重做"
          title="重做 (Ctrl/Cmd+Shift+Z)"
          @mousedown.prevent
          @click="editor?.chain().focus().redo().run()"
        >
          重做
        </button>
      </div>
    </div>

    <BubbleMenu v-if="editor" :editor="editor" :options="{ placement: 'top' }">
      <div class="article-editor__bubble" role="toolbar" aria-label="所选文字格式">
        <button
          type="button"
          class="article-editor__bubble-tool"
          :class="{ 'is-active': editor.isActive('bold') }"
          :aria-pressed="editor.isActive('bold')"
          aria-label="粗体"
          @mousedown.prevent
          @click="editor.chain().focus().toggleBold().run()"
        >
          <strong>B</strong>
        </button>
        <button
          type="button"
          class="article-editor__bubble-tool"
          :class="{ 'is-active': editor.isActive('italic') }"
          :aria-pressed="editor.isActive('italic')"
          aria-label="斜体"
          @mousedown.prevent
          @click="editor.chain().focus().toggleItalic().run()"
        >
          <em>I</em>
        </button>
        <button
          type="button"
          class="article-editor__bubble-tool article-editor__bubble-tool--text"
          :class="{ 'is-active': editor.isActive('link') }"
          :aria-pressed="editor.isActive('link')"
          aria-label="添加或编辑链接"
          @mousedown.prevent
          @click="setLink"
        >
          链接
        </button>
      </div>
    </BubbleMenu>

    <div v-if="legacyProtected" class="article-editor__legacy-warning" role="alert">
      <strong>原文保护已开启</strong>
      <p>
        该文章含有当前编辑器无法无损处理的原始 HTML 或不安全的内嵌图片。自动保存和发布已暂停，请先展开并备份原始 Markdown。
      </p>
      <details>
        <summary>查看并复制原始 Markdown</summary>
        <textarea :value="legacySource" readonly aria-label="原始 Markdown"></textarea>
      </details>
      <button
        type="button"
        class="article-editor__legacy-convert"
        data-testid="convert-legacy-markdown"
        @click="convertLegacyContent"
      >
        我已备份，转换后继续编辑
      </button>
    </div>

    <EditorContent :editor="editor" class="article-editor__content" />

    <p class="article-editor__notice">
      Markdown 扩展为 Beta：复杂表格与媒体内容可能无法无损往返，请在发布前检查。
    </p>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import { BubbleMenu } from '@tiptap/vue-3/menus'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import Link from '@tiptap/extension-link'
import Image from '@tiptap/extension-image'
import { Table } from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import Underline from '@tiptap/extension-underline'
import Placeholder from '@tiptap/extension-placeholder'
import { hasUnsupportedRawHtml, sanitizeMarkdownImageDestinations } from '../utils/articleMarkdown'

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  // 文档身份由页面层提供；正文相同但文章 ID 不同时仍必须使旧建议失效。
  documentKey: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['update:modelValue', 'upload-image', 'word-count', 'legacy-protection'])
const imageInput = ref(null)
const initialRawMarkdown = String(props.modelValue ?? '')
const initialMarkdown = sanitizeMarkdownImageDestinations(initialRawMarkdown)
const legacySource = ref(initialRawMarkdown)
const legacyProtected = ref(
  initialMarkdown !== initialRawMarkdown || hasUnsupportedRawHtml(initialRawMarkdown),
)
// 这个本地版本只表示当前编辑器会话中的内容变化次数。
// Agent 建议必须与生成时的版本一致，否则用户后续输入可能被迟到结果覆盖。
const documentVersion = ref(0)

function countWords(editorInstance) {
  const text = editorInstance.getText().trim()

  if (!text) return 0

  const hanCharacters = text.match(/[\u3400-\u4DBF\u4E00-\u9FFF]/g) ?? []
  const latinWords = text
    .replace(/[\u3400-\u4DBF\u4E00-\u9FFF]/g, ' ')
    .match(/[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)*/gu) ?? []

  return hanCharacters.length + latinWords.length
}

function emitEditorValue(editorInstance) {
  documentVersion.value += 1
  emit('word-count', countWords(editorInstance))
  if (!legacyProtected.value) emit('update:modelValue', editorInstance.getMarkdown())
}

/**
 * 捕获 Agent 写作请求的不可变快照。
 * 当用户没有选区时，快照覆盖整篇正文；有选区时则只建议替换被选中的文字。
 */
function getAgentWritingSnapshot() {
  if (!editor.value) {
    return { content: '', selectedText: '', selectionFrom: 0, selectionTo: 0, documentVersion: documentVersion.value }
  }
  const { from, to } = editor.value.state.selection
  const hasSelection = from !== to
  return {
    content: editor.value.getMarkdown(),
    selectedText: hasSelection
      ? editor.value.state.doc.textBetween(from, to, '\n')
      : editor.value.getMarkdown(),
    selectionFrom: hasSelection ? from : 0,
    selectionTo: hasSelection ? to : editor.value.state.doc.content.size,
    documentVersion: documentVersion.value,
  }
}

/**
 * 在编辑器内执行最后一道乐观锁校验。
 * 只有版本仍一致、范围合法时才替换，返回 false 时调用方必须要求重新生成。
 */
function applyAgentSuggestion(candidate) {
  if (!editor.value || !candidate || candidate.documentVersion !== documentVersion.value) return false
  const from = Number(candidate.selectionFrom)
  const to = Number(candidate.selectionTo)
  if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to < from
    || to > editor.value.state.doc.content.size || typeof candidate.suggestedText !== 'string') return false

  return editor.value.chain().focus().insertContentAt({ from, to }, candidate.suggestedText, {
    updateSelection: true,
  }).run()
}

defineExpose({ getAgentWritingSnapshot, applyAgentSuggestion })

const editor = useEditor({
  content: initialMarkdown,
  contentType: 'markdown',
  extensions: [
    StarterKit.configure({
      link: false,
      underline: false,
    }),
    Markdown,
    Link.configure({
      openOnClick: false,
      linkOnPaste: true,
      autolink: true,
    }),
    Image.configure({
      allowBase64: false,
    }),
    Table.configure({
      resizable: false,
    }),
    TableRow,
    TableHeader,
    TableCell,
    Underline,
    Placeholder.configure({
      placeholder: '从一个清晰的开头写起……',
    }),
  ],
  editorProps: {
    attributes: {
      lang: 'zh-CN',
      'aria-label': '文章正文',
      spellcheck: 'true',
    },
  },
  onCreate: ({ editor: editorInstance }) => {
    emit('word-count', countWords(editorInstance))
    emit('legacy-protection', legacyProtected.value)
  },
  onUpdate: ({ editor: editorInstance }) => {
    emitEditorValue(editorInstance)
  },
})

watch(
  () => props.modelValue,
  value => {
    const rawValue = String(value ?? '')
    const sanitizedValue = sanitizeMarkdownImageDestinations(rawValue)
    const nextLegacyProtected = sanitizedValue !== rawValue || hasUnsupportedRawHtml(rawValue)

    legacySource.value = rawValue
    if (legacyProtected.value !== nextLegacyProtected) {
      legacyProtected.value = nextLegacyProtected
      emit('legacy-protection', nextLegacyProtected)
    }

    if (!editor.value || sanitizedValue === editor.value.getMarkdown()) return

    editor.value.commands.setContent(sanitizedValue, {
      contentType: 'markdown',
      emitUpdate: false,
    })
    // 外部水合通常意味着切换文章或重新加载服务端版本。即使没有触发编辑器 update，
    // 也必须推进本地版本，使上一篇文章尚未返回的 Agent 建议失效。
    documentVersion.value += 1
    emit('word-count', countWords(editor.value))
  },
)

watch(
  () => props.documentKey,
  (nextKey, previousKey) => {
    if (nextKey === previousKey) return
    // 身份变化独立于正文变化推进版本，覆盖“两篇文章内容完全相同”的边界。
    documentVersion.value += 1
  },
)

function convertLegacyContent() {
  if (!editor.value || !legacyProtected.value) return

  const accepted = window.confirm(
    '转换后，当前编辑器不支持的 HTML、媒体标记和不安全内嵌图片将从文章中移除。请确认你已备份原始 Markdown。',
  )
  if (!accepted) return

  legacyProtected.value = false
  emit('legacy-protection', false)
  emitEditorValue(editor.value)
}

function setLink() {
  if (!editor.value) return

  const currentUrl = editor.value.getAttributes('link').href ?? ''
  const url = window.prompt('输入链接地址', currentUrl || 'https://')

  if (url === null) return
  if (!url.trim()) {
    editor.value.chain().focus().extendMarkRange('link').unsetLink().run()
    return
  }

  editor.value.chain().focus().extendMarkRange('link').setLink({ href: url.trim() }).run()
}

function openImagePicker() {
  imageInput.value?.click()
}

function handleImageSelection(event) {
  const input = event.target
  const file = input.files?.[0]
  input.value = ''

  if (!file || !file.type.startsWith('image/')) return

  emit('upload-image', file, insertImage)
}

function insertImage(url) {
  if (!editor.value || typeof url !== 'string') return

  const imageUrl = url.trim()
  if (!imageUrl || /^(?:data|blob):/i.test(imageUrl)) return

  editor.value.chain().focus().setImage({ src: imageUrl, alt: '' }).run()
}
</script>

<style scoped lang="scss">
.article-editor {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--paper);
  box-shadow: 0 1px 3px rgba(71, 48, 34, 0.1);
  color: var(--ink);
}

.article-editor__toolbar {
  box-sizing: border-box;
  display: flex;
  gap: var(--space-2);
  width: 100%;
  min-width: 0;
  padding: var(--space-2);
  overflow-x: auto;
  border-bottom: 1px solid var(--line);
  background: var(--paper-muted);
  scrollbar-width: thin;
  scrollbar-color: var(--line) transparent;
}

.article-editor__legacy-warning {
  margin: var(--space-4);
  padding: var(--space-4);
  border: 1px solid #d29a50;
  border-radius: var(--radius-sm);
  background: #fff8eb;
  color: #704817;

  p {
    margin: var(--space-2) 0;
    line-height: 1.7;
  }

  details {
    margin: var(--space-3) 0;
  }

  summary {
    cursor: pointer;
    font-weight: 600;
  }

  textarea {
    box-sizing: border-box;
    width: 100%;
    min-height: 140px;
    margin-top: var(--space-2);
    padding: var(--space-3);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--paper);
    color: var(--ink);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    resize: vertical;
  }
}

.article-editor__legacy-convert {
  padding: var(--space-2) var(--space-3);
  border: 1px solid currentColor;
  border-radius: var(--radius-sm);
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.article-editor__tool-group {
  display: flex;
  flex: 0 0 auto;
  gap: var(--space-1);
  padding-right: var(--space-2);
  border-right: 1px solid var(--line);

  &:last-child {
    padding-right: 0;
    border-right: 0;
  }
}

.article-editor__tool,
.article-editor__bubble-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 40px;
  height: 40px;
  padding: 0 var(--space-2);
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--ink-muted);
  font: inherit;
  line-height: 1;
  cursor: pointer;
  touch-action: manipulation;
  transition-property: color, background-color, transform;
  transition-duration: 140ms;
  transition-timing-function: cubic-bezier(0.16, 1, 0.3, 1);

  &:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }

  &:active:not(:disabled) {
    transform: scale(0.96);
  }

  &.is-active {
    background: var(--accent);
    color: var(--paper);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.35;
  }
}

.article-editor__tool--text {
  min-width: auto;
  padding-inline: var(--space-3);
  white-space: nowrap;
}

.article-editor__file-input {
  position: fixed;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

.article-editor__content {
  max-width: 100%;
  min-width: 0;
  background: color-mix(in srgb, var(--paper) 88%, white);
}

.article-editor__content :deep(.tiptap) {
  min-height: 520px;
  max-width: 100%;
  padding: clamp(var(--space-5), 5vw, 48px);
  overflow-x: auto;
  outline: none;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 17px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  text-wrap: pretty;
  caret-color: var(--accent);
}

.article-editor__content :deep(.tiptap > :first-child) {
  margin-top: 0;
}

.article-editor__content :deep(.tiptap > :last-child) {
  margin-bottom: 0;
}

.article-editor__content :deep(h2) {
  margin: 1.8em 0 0.7em;
  font-family: "Songti SC", STSong, SimSun, serif;
  font-size: clamp(24px, 4vw, 30px);
  line-height: 1.35;
  font-weight: 700;
  text-wrap: balance;
}

.article-editor__content :deep(p) {
  margin: 0 0 1em;
}

.article-editor__content :deep(a) {
  color: var(--accent-dark);
  text-decoration-thickness: 1px;
  text-underline-offset: 0.2em;
}

.article-editor__content :deep(blockquote) {
  margin: 1.5em 0;
  padding: var(--space-2) var(--space-4);
  border-left: 1px solid var(--accent);
  background: var(--paper-muted);
  color: var(--ink-muted);
}

.article-editor__content :deep(pre) {
  max-width: 100%;
  padding: var(--space-4);
  overflow-x: auto;
  border-radius: var(--radius-sm);
  background: var(--ink);
  color: var(--paper);
  font-family: ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre;
  overflow-wrap: normal;
}

.article-editor__content :deep(code) {
  font-family: ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace;
}

.article-editor__content :deep(:not(pre) > code) {
  padding: 0.12em 0.32em;
  border-radius: var(--radius-sm);
  background: var(--paper-muted);
  color: var(--accent-dark);
  font-size: 0.9em;
}

.article-editor__content :deep(table) {
  width: max-content;
  min-width: 100%;
  margin: 1.5em 0;
  border-collapse: collapse;
}

.article-editor__content :deep(th),
.article-editor__content :deep(td) {
  min-width: 120px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}

.article-editor__content :deep(th) {
  background: var(--paper-muted);
  font-family: "Songti SC", STSong, SimSun, serif;
}

.article-editor__content :deep(img) {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto;
  margin: var(--space-5) auto;
  outline: 1px solid rgba(71, 48, 34, 0.12);
  outline-offset: -1px;
}

.article-editor__content :deep(p.is-editor-empty:first-child::before) {
  content: attr(data-placeholder);
  float: left;
  height: 0;
  color: var(--ink-muted);
  pointer-events: none;
  opacity: 0.7;
}

.article-editor__bubble {
  display: flex;
  gap: 2px;
  padding: var(--space-1);
  border-radius: var(--radius-md);
  background: var(--ink);
  box-shadow: 0 8px 24px rgba(41, 35, 30, 0.2);
}

.article-editor__bubble-tool {
  color: var(--paper-muted);

  &.is-active {
    background: var(--accent);
    color: var(--paper);
  }
}

.article-editor__bubble-tool--text {
  padding-inline: var(--space-3);
}

.article-editor__notice {
  margin: 0;
  padding: var(--space-2) var(--space-4);
  border-top: 1px solid var(--line);
  background: var(--paper-muted);
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (hover: hover) {
  .article-editor__tool:hover:not(:disabled),
  .article-editor__bubble-tool:hover:not(:disabled) {
    background: color-mix(in srgb, var(--accent) 14%, transparent);
    color: var(--accent-dark);
  }

  .article-editor__tool.is-active:hover,
  .article-editor__bubble-tool.is-active:hover {
    background: var(--accent-dark);
    color: var(--paper);
  }
}

@media (max-width: 600px) {
  .article-editor__content :deep(.tiptap) {
    min-height: 440px;
    padding: var(--space-4);
    font-size: 16px;
  }

  .article-editor__notice {
    padding-inline: var(--space-3);
  }
}

@media (prefers-reduced-motion: reduce) {
  .article-editor__tool,
  .article-editor__bubble-tool {
    transition-duration: 0.01ms;
  }
}
</style>
