import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const source = (name) => readFileSync(fileURLToPath(new URL(`./${name}`, import.meta.url)), 'utf8')

describe('narrow-screen navigation', () => {
  it('keeps chat contacts and the active conversation mutually reachable on narrow screens', () => {
    const chat = source('Chat.vue')

    expect(chat).toContain('is-mobile-conversation-open')
    expect(chat).toContain('closeMobileConversation')
    expect(chat).toMatch(/@media \(max-width: 700px\)[\s\S]*\.chat-main/)
  })

  it('removes the space-hungry home controls before the 320px layout can overlap', () => {
    const home = source('Home.vue')

    expect(home).toMatch(/@media \(max-width: 760px\)[\s\S]*\.navbar-right \.action-btns \{ display: none; \}/)
    expect(home).toMatch(/@media \(max-width: 400px\)[\s\S]*\.navbar-left \.logo \{ margin-right: 0;/)
  })
})
