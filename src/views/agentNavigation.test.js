import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const read = relativePath => readFileSync(
  fileURLToPath(new URL(relativePath, import.meta.url)),
  'utf8',
)

describe('Agent 页面入口', () => {
  it('registers an authenticated Agent route', () => {
    const router = read('../router/index.js')

    expect(router).toMatch(/path:\s*['"]\/agent['"][\s\S]*name:\s*['"]Agent['"]/)
    expect(router).toMatch(/path:\s*['"]\/agent['"][\s\S]*requiresAuth:\s*true/)
    expect(router).toContain("import('../views/Agent.vue')")
  })

  it('keeps the Agent entry reachable in both desktop and narrow navigation', () => {
    const home = read('./Home.vue')

    expect(home).toContain('class="agent-entry"')
    expect(home).toContain("$router.push('/agent')")
    expect(home).toMatch(/@media \(max-width: 760px\)[\s\S]*\.agent-entry-label\s*\{\s*display:\s*none;/)
  })

  it('keeps the mobile composer in document flow so it cannot cover the privacy notice', () => {
    const agent = read('./Agent.vue')

    expect(agent).not.toMatch(/@media \(max-width: 680px\)[\s\S]*\.composer\s*\{[^}]*position:\s*sticky;/)
    expect(agent).toMatch(/@media \(max-width: 680px\)[\s\S]*\.conversation-panel\s*\{[^}]*min-height:/)
  })
})
