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

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['update:modelValue', 'upload-image', 'word-count'])
const imageInput = ref(null)

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
  emit('update:modelValue', editorInstance.getMarkdown())
  emit('word-count', countWords(editorInstance))
}

const editor = useEditor({
  content: props.modelValue,
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
  },
  onUpdate: ({ editor: editorInstance }) => {
    emitEditorValue(editorInstance)
  },
})

watch(
  () => props.modelValue,
  value => {
    if (!editor.value || value === editor.value.getMarkdown()) return

    editor.value.commands.setContent(value, {
      contentType: 'markdown',
      emitUpdate: false,
    })
    emit('word-count', countWords(editor.value))
  },
)

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
