# Editorial Frontend and Tiptap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the basic Markdown writing surface with a desktop-first Tiptap editor and apply one coherent paper-like visual system across the existing community UI.

**Architecture:** Keep the existing article API and `content` string unchanged. The new `RichArticleEditor` parses and serializes the Markdown subset supported by `@tiptap/markdown`, while `Publish.vue` owns draft timing and article submission. Shared CSS tokens and a lightweight app shell style are introduced before migrating the existing view styles, so reading, authoring and utility surfaces use the same visual language without changing backend contracts.

**Tech Stack:** Vue 3, Vite 7, Element Plus, Sass, Tiptap 3, existing Axios API client, Vitest for pure content and save-state tests.

## Global Constraints

- Work directly on the existing `frontend` branch. Do not create a worktree.
- Use no mock data. Exercise existing REST endpoints against the running Java service where an endpoint is needed.
- Desktop keyboard writing is the primary target. At 375px, pages must remain usable and free of horizontal overflow, but mobile editing is not a feature target.
- Use only system Chinese font stacks. Do not introduce hosted fonts or UI templates.
- Use warm paper surfaces, Song-style headings and low-saturation brick red as the single main accent. Do not use blue as the primary action color.
- Preserve the current Markdown API contract. The first release supports standard Markdown nodes only. Video, audio, arbitrary iframe embeds, collaborative editing and JSON document persistence are deferred until dedicated media APIs and a content-model migration exist.
- Image insertion must call the real `/api/file/upload` endpoint, never a data URL or fake response.

---

### Task 1: Establish the shared visual foundation and test harness

**Files:**
- Modify: `package.json`
- Modify: `src/style.css`
- Create: `src/styles/tokens.scss`
- Create: `src/styles/element-plus.scss`
- Create: `src/utils/draftState.js`
- Create: `src/utils/draftState.test.js`
- Create: `vitest.config.js`

**Consumes:** Existing Vue 3 and Vite application.

**Produces:** Named CSS variables and `nextDraftState(dirty, saving, failed)` used by the publishing surface.

- [ ] **Step 1: Write the failing state test**

```js
import { describe, expect, it } from 'vitest'
import { nextDraftState } from './draftState'

describe('nextDraftState', () => {
  it('uses stable labels for each save state', () => {
    expect(nextDraftState(true, false, false)).toEqual({ label: '未保存', tone: 'muted' })
    expect(nextDraftState(true, true, false)).toEqual({ label: '正在保存', tone: 'saving' })
    expect(nextDraftState(false, false, false)).toEqual({ label: '已保存', tone: 'saved' })
    expect(nextDraftState(true, false, true)).toEqual({ label: '保存失败', tone: 'error' })
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test -- --run src/utils/draftState.test.js`

Expected: FAIL because Vitest and `draftState.js` do not exist.

- [ ] **Step 3: Add test tooling and the minimal state helper**

Add `"test": "vitest"` to `package.json` and install `vitest` as a dev dependency. Create `src/utils/draftState.js`:

```js
export function nextDraftState(dirty, saving, failed) {
  if (failed) return { label: '保存失败', tone: 'error' }
  if (saving) return { label: '正在保存', tone: 'saving' }
  return dirty ? { label: '未保存', tone: 'muted' } : { label: '已保存', tone: 'saved' }
}
```

Create `src/styles/tokens.scss` with CSS custom properties for `--paper`, `--paper-muted`, `--ink`, `--ink-muted`, `--line`, `--accent`, `--accent-dark`, `--radius-sm`, `--radius-md`, and the 4/8/12/16/24/32px spacing scale. Import it from `src/style.css`, replace the global `Inter` stack with a Song-title and sans-body system stack, and add Element Plus CSS variable overrides in `src/styles/element-plus.scss`.

- [ ] **Step 4: Run focused validation**

Run: `npm run test -- --run src/utils/draftState.test.js && npm run build`

Expected: one passing test and a successful Vite build.

- [ ] **Step 5: Commit**

```bash
git add package.json package-lock.json vitest.config.js src/style.css src/styles src/utils/draftState.*
git commit -m "feat: establish editorial frontend tokens"
```

### Task 2: Add a reusable Tiptap Markdown editor

**Files:**
- Modify: `package.json`
- Create: `src/components/RichArticleEditor.vue`
- Create: `src/utils/articleMarkdown.js`
- Create: `src/utils/articleMarkdown.test.js`

**Consumes:** `v-model` Markdown string from `Publish.vue`, the existing Axios upload client, and CSS tokens from Task 1.

**Produces:** `RichArticleEditor` with `v-model`, `upload-image` event, `word-count` event and keyboard-accessible toolbar commands.

- [ ] **Step 1: Write failing Markdown boundary tests**

```js
import { describe, expect, it } from 'vitest'
import { estimateReadingMinutes, normalizeMarkdown } from './articleMarkdown'

describe('article Markdown helpers', () => {
  it('counts Chinese and Latin text without counting Markdown markers', () => {
    expect(estimateReadingMinutes('## 标题\n\nhello **Metro**')).toBe(1)
  })

  it('normalizes a document before it is sent to the existing API', () => {
    expect(normalizeMarkdown('标题  \n\n\n正文')).toBe('标题\n\n正文')
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test -- --run src/utils/articleMarkdown.test.js`

Expected: FAIL because the helper module does not exist.

- [ ] **Step 3: Add Tiptap and implement the editor boundary**

Install compatible Tiptap 3 packages: `@tiptap/core`, `@tiptap/vue-3`, `@tiptap/starter-kit`, `@tiptap/markdown`, `@tiptap/extension-link`, `@tiptap/extension-image`, `@tiptap/extension-table`, `@tiptap/extension-table-row`, `@tiptap/extension-table-header`, `@tiptap/extension-table-cell`, `@tiptap/extension-underline`, and `@tiptap/extension-placeholder`.

Implement `src/utils/articleMarkdown.js`:

```js
export function normalizeMarkdown(value = '') {
  return value.replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim()
}

export function estimateReadingMinutes(value = '') {
  const characters = value.replace(/[`*_#>[\]()!-]/g, '').replace(/\s/g, '').length
  return Math.max(1, Math.ceil(characters / 400))
}
```

Implement `RichArticleEditor.vue` with a `useEditor` instance. Configure `StarterKit`, `Markdown`, `Link`, `Image`, `Table`, `TableRow`, `TableHeader`, `TableCell`, `Underline`, and `Placeholder`. Initialize editor content with `{ content: modelValue, contentType: 'markdown' }`; on editor updates emit `update:modelValue` using `editor.getMarkdown()`. Add visible toolbar buttons for bold, italic, H2, bullet list, ordered list, quote, code block, link, table, image, undo and redo. Add a BubbleMenu for selected text. Ensure each button has an `aria-label` and does not steal focus from the editor.

For image insertion, emit `upload-image` from the component with a `File`, wait for the parent to return an OSS URL through an `insertImage(url)` callback, and call `editor.chain().focus().setImage({ src: url, alt: '' }).run()`. Do not accept base64 images or arbitrary raw HTML embeds.

- [ ] **Step 4: Run validation**

Run: `npm run test -- --run src/utils/articleMarkdown.test.js && npm run build`

Expected: helper tests pass and the new editor compiles.

- [ ] **Step 5: Commit**

```bash
git add package.json package-lock.json src/components/RichArticleEditor.vue src/utils/articleMarkdown.*
git commit -m "feat: add rich Markdown article editor"
```

### Task 3: Rebuild the publishing flow around real drafts

**Files:**
- Modify: `src/views/Publish.vue`
- Modify: `src/api/article.js`
- Test: `src/utils/draftState.test.js`

**Consumes:** `RichArticleEditor`, `nextDraftState`, `estimateReadingMinutes`, `getArticleForEdit`, `saveDraft`, `publishArticle`, `getHotTags`, and `/api/file/upload`.

**Produces:** Paper-like desktop writing page with non-navigating manual drafts, debounced auto-save, meaningful save feedback and real image uploads.

- [ ] **Step 1: Extend the failing save-state test**

```js
it('keeps a failure visible until new input is saved', () => {
  expect(nextDraftState(true, false, true)).toEqual({ label: '保存失败', tone: 'error' })
  expect(nextDraftState(false, false, false)).toEqual({ label: '已保存', tone: 'saved' })
})
```

- [ ] **Step 2: Run focused tests to verify the regression boundary**

Run: `npm run test -- --run src/utils/draftState.test.js`

Expected: PASS before the view refactor, proving the state contract is protected.

- [ ] **Step 3: Replace the current layout and submission behavior**

In `Publish.vue`, replace `<v-md-editor>` with `<RichArticleEditor v-model="form.content" @upload-image="uploadInlineImage" @word-count="wordCount = $event" />`. Replace the 900px single-column layout with an `aside.editor-meta` and `main.editor-canvas` grid at widths above 960px. The metadata rail contains the cover uploader, tags, word count, estimated reading time and the fixed-height save-status slot. The main canvas contains the title input and editor.

Implement a 1500ms watcher debounce that calls `save(false)` only after a title and content exist. `save(false)` calls `saveDraft(payload)`, updates `form.id` from `response.data`, sets `dirty` to false and never navigates. `handleSaveDraft()` calls the same function and remains on `/publish`. `handlePublish()` calls `publishArticle({ ...payload, isPublish: true })`, shows success and then navigates to `/home`.

Use this upload implementation:

```js
async function uploadInlineImage(file, insertImage) {
  const body = new FormData()
  body.append('file', file)
  const response = await request.post('/api/file/upload', body)
  insertImage(response.data)
}
```

Clear the debounce timer in `onBeforeUnmount`. Do not invoke automatic saving while the initial article is loading or while an existing save request is in flight.

- [ ] **Step 4: Run build and manual real-service flow**

Run: `npm run build`

Then run the frontend against the Java service and verify: create a title and body, wait for “已保存”, refresh and reopen the draft, paste a real PNG, save a manual draft without navigation, and publish the article.

- [ ] **Step 5: Commit**

```bash
git add src/views/Publish.vue src/api/article.js src/utils/draftState.test.js
git commit -m "feat: rebuild the article authoring flow"
```

### Task 4: Apply the editorial system to reading and community surfaces

**Files:**
- Modify: `src/views/Home.vue`
- Modify: `src/views/ArticleDetail.vue`
- Modify: `src/views/Login.vue`
- Modify: `src/views/Register.vue`
- Modify: `src/views/ResetPassword.vue`
- Modify: `src/views/UserCenter.vue`
- Modify: `src/views/UserSetting.vue`
- Modify: `src/views/FavoriteDetail.vue`
- Modify: `src/views/Message.vue`
- Modify: `src/views/Chat.vue`
- Modify: `src/views/admin/AdminLayout.vue`
- Modify: `src/views/admin/ArticleAudit.vue`
- Modify: `src/views/admin/ReportManage.vue`
- Modify: `src/views/admin/UserManage.vue`

**Consumes:** Shared tokens from Task 1 and unchanged API view models.

**Produces:** A coherent content-first desktop UI without altering request paths, response shapes or admin authorization.

- [ ] **Step 1: Write a failing layout smoke test**

Create `src/utils/editorialLayout.test.js`:

```js
import { describe, expect, it } from 'vitest'
import { desktopContentWidth } from './editorialLayout'

describe('editorial layout', () => {
  it('caps wide reading layouts while keeping a usable narrow fallback', () => {
    expect(desktopContentWidth(1600)).toBe(1180)
    expect(desktopContentWidth(375)).toBe(343)
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test -- --run src/utils/editorialLayout.test.js`

Expected: FAIL because `editorialLayout.js` does not exist.

- [ ] **Step 3: Implement the shared layout helper and migrate views**

Create `src/utils/editorialLayout.js`:

```js
export function desktopContentWidth(viewportWidth) {
  return Math.max(0, Math.min(1180, viewportWidth - 32))
}
```

In `Home.vue`, replace primary blue button and active-tab colors with `var(--accent)`, reduce feed-card shadows to borders, and make article title the primary visual element. In `ArticleDetail.vue`, place title, author metadata, cover and rendered content on the paper reading surface, style the action row as compact outline controls, and preserve all existing like, collect, report and comment handlers.

In authentication and profile-related pages, replace decorative gradients and default blue actions with the shared paper surface, system font hierarchy and focus treatment. In chat, message, favorite and settings pages, apply tokenized borders, spacing and empty states without altering live message or pagination logic. In admin pages, keep dense tables and filters, but inherit tokenized color, typography, focus and button rules rather than article-style typography.

- [ ] **Step 4: Run tests and build**

Run: `npm run test -- --run src/utils/editorialLayout.test.js && npm run build`

Expected: layout test passes and all lazy-loaded routes compile.

- [ ] **Step 5: Commit**

```bash
git add src/views src/utils/editorialLayout.*
git commit -m "feat: apply editorial design across community views"
```

### Task 5: Verify visual behavior against the real application

**Files:**
- Modify: `README.md`
- Modify: `package.json` only if a verification script is needed

**Consumes:** Completed frontend build and running Java service.

**Produces:** Documented start instructions and evidence that the real UI works at required viewports.

- [ ] **Step 1: Start the real services**

Run the Java service with actual environment variables, then run `npm run dev -- --host 127.0.0.1` from the `frontend` branch. Do not replace API calls with static fixtures.

- [ ] **Step 2: Validate desktop writing and reading**

At 1440px viewport width, verify `/publish`, `/home`, `/article/:id`, `/login`, `/user/:id`, `/message`, `/chat` and `/admin/audit`. Confirm the publisher has a stable save-status slot, keyboard focus is visible, toolbar controls have labels, and no fixed footer masks content.

- [ ] **Step 3: Validate narrow safe fallback**

At 375px, verify `/publish`, `/home` and `/article/:id`. Confirm the publishing metadata rail collapses above the editor, actions stay visible, and long Chinese titles, tags and buttons neither overflow nor overlap.

- [ ] **Step 4: Build from a clean dependency state**

Run: `npm ci && npm run test -- --run && npm run build`

Expected: all tests pass and Vite emits a production bundle without unresolved imports.

- [ ] **Step 5: Update documentation and commit**

Document that the frontend uses Tiptap with Markdown-compatible storage, state the real backend URL configuration, and explain that audio/video require a later media API and are not enabled by default.

```bash
git add README.md package.json package-lock.json
git commit -m "docs: document the editorial frontend workflow"
```

## Spec Coverage Review

- Visual system and system font constraint: Tasks 1 and 4.
- Tiptap, WYSIWYG interactions and Markdown compatibility: Tasks 2 and 3.
- Existing real draft and image APIs, no mock data: Task 3.
- Full existing front-end visual migration: Task 4.
- Desktop-first plus narrow safe fallback and real-service verification: Task 5.
- Deferred audio, video, embeds, JSON persistence, AI and recommendation work: Global Constraints and Task 5 documentation.

## Plan Self-Review

- Scope is limited to the frontend and its existing REST contracts. Media APIs and recommendation behavior are intentionally excluded.
- All named helper interfaces appear before use, and each implementation task includes a failing test and a validation command.
- The plan contains no deferred implementation placeholders. Deferred product capabilities are explicit scope exclusions.
