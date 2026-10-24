import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '../../../i18n'
import ChatMarkdown, { MAX_DEPTH, type ChatNode } from './ChatMarkdown'

const text = (value: string): ChatNode => ({ type: 'text', text: value })
const ALLOWED = new Set(['DIV', 'P', 'STRONG', 'EM', 'DEL', 'CODE', 'PRE', 'BLOCKQUOTE', 'UL', 'OL', 'LI', 'A', 'BR', 'SPAN'])

function render(nodes: unknown[] | null) {
  i18n.global.locale.value = 'en'
  return mount(ChatMarkdown, { props: { nodes: nodes as ChatNode[] | null } })
}

function tagsIn(element: Element): string[] {
  return [...element.querySelectorAll('*')].map((child) => child.tagName)
}

describe('ChatMarkdown: the supported subset', () => {
  it('draws bold, italic, strikethrough and inline code', () => {
    const wrapper = render([
      {
        type: 'paragraph',
        children: [
          { type: 'strong', children: [text('bold')] },
          text(' '),
          { type: 'em', children: [text('italic')] },
          text(' '),
          { type: 'del', children: [text('gone')] },
          text(' '),
          { type: 'code', text: 'x = 1' },
        ],
      },
    ])

    expect(wrapper.get('strong').text()).toBe('bold')
    expect(wrapper.get('em').text()).toBe('italic')
    expect(wrapper.get('del').text()).toBe('gone')
    expect(wrapper.get('code').text()).toBe('x = 1')
    wrapper.unmount()
  })

  it('draws a line break inside a paragraph', () => {
    const wrapper = render([{ type: 'paragraph', children: [text('one'), { type: 'br' }, text('two')] }])

    expect(wrapper.get('p').element.querySelector('br')).not.toBeNull()
    expect(wrapper.get('p').text()).toBe('onetwo')
    wrapper.unmount()
  })

  it('draws ordered lists from their start number and unordered lists', () => {
    const wrapper = render([
      { type: 'list', ordered: true, start: 3, children: [{ type: 'item', children: [text('a')] }] },
      { type: 'list', ordered: false, start: 1, children: [{ type: 'item', children: [text('b')] }] },
    ])

    const ordered = wrapper.get('ol')
    expect(ordered.attributes('start')).toBe('3')
    expect(ordered.findAll('li').map((item) => item.text())).toEqual(['a'])
    expect(wrapper.get('ul').findAll('li').map((item) => item.text())).toEqual(['b'])
    wrapper.unmount()
  })

  it('draws a quote and a code block with its text as text', () => {
    const wrapper = render([
      { type: 'quote', children: [text('quoted')] },
      { type: 'code_block', text: 'let x = <b>1</b>' },
    ])

    expect(wrapper.get('blockquote').text()).toBe('quoted')
    expect(wrapper.get('pre code').text()).toBe('let x = <b>1</b>')
    expect(wrapper.find('pre b').exists()).toBe(false)
    wrapper.unmount()
  })

  it('draws an external link as a link through the interstitial and shows its host', () => {
    const wrapper = render([{ type: 'link', href: 'https://example.org/a', external: true, children: [text('the site')] }])

    const link = wrapper.get('a')
    expect(link.text()).toContain('the site')
    expect(link.text(), 'the target host is shown next to the text').toContain('example.org')
    expect(link.attributes('href')).toMatch(/^\/leave#[A-Za-z0-9_-]+$/)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer nofollow ugc')
    wrapper.unmount()
  })

  it('draws a trusted link straight to its target', () => {
    const wrapper = render([{ type: 'link', href: 'https://onelitefeather.net/rules', external: false, children: [text('rules')] }])

    expect(wrapper.get('a').attributes('href')).toBe('https://onelitefeather.net/rules')
    wrapper.unmount()
  })

  it('shows nothing for an empty tree', () => {
    const wrapper = render(null)

    expect(wrapper.element.children).toHaveLength(0)
    wrapper.unmount()
  })
})

describe('ChatMarkdown: hostile trees', () => {
  it('never draws a link whose target is not http(s), and keeps its text', () => {
    const wrapper = render([{ type: 'link', href: 'javascript:alert(1)', external: true, children: [text('click')] }])

    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.text()).toContain('click')
    wrapper.unmount()
  })

  it.each([
    ['data:', 'data:text/html,<script>alert(1)</script>'],
    ['file:', 'file:///etc/passwd'],
    ['protocol-relative', '//evil.example/x'],
    ['relative', '/admin'],
    ['user info', 'https://user:pass@evil.example/'],
  ])('refuses a %s target', (_label, href) => {
    const wrapper = render([{ type: 'link', href, external: true, children: [text('x')] }])

    expect(wrapper.find('a').exists()).toBe(false)
    wrapper.unmount()
  })

  it('draws text that looks like markup as plain text, with no element created', () => {
    const payload = '<img src=x onerror="alert(1)"><script>alert(2)</script><iframe src="https://evil.example"></iframe>'
    const wrapper = render([{ type: 'paragraph', children: [text(payload)] }])

    expect(wrapper.text()).toBe(payload)
    expect(wrapper.element.querySelectorAll('img, script, iframe, object, embed, style').length).toBe(0)
    wrapper.unmount()
  })

  it('draws only the elements of the subset, whatever the tree names', () => {
    const wrapper = render([
      { type: 'script', text: 'alert(1)' },
      { type: 'img', href: 'https://example.org/x.png' },
      { type: 'paragraph', children: [{ type: 'iframe', children: [text('inside')] }, { type: 'heading', children: [text('h')] }] },
      { type: 'paragraph', children: [{ type: 'strong', children: [{ type: 'link', href: 'https://example.org/ok', external: true, children: [text('ok')] }] }] },
    ])

    const names = tagsIn(wrapper.element as Element)
    expect(names.filter((name) => !ALLOWED.has(name))).toEqual([])
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    wrapper.unmount()
  })

  it('ignores an unknown node type but keeps its readable children', () => {
    const wrapper = render([{ type: 'mystery', text: 'hidden', children: [text('shown')] }])

    expect(wrapper.text()).toContain('shown')
    wrapper.unmount()
  })

  it('stops after a bounded number of nodes instead of building an unbounded tree', () => {
    const many = Array.from({ length: 5000 }, () => ({ type: 'paragraph', children: [text('x')] }))
    const wrapper = render(many)

    expect(wrapper.findAll('p').length).toBeLessThanOrEqual(1000)
    wrapper.unmount()
  })

  it('stops at a bounded nesting depth', () => {
    let node: ChatNode = text('deep')
    for (let i = 0; i < 200; i++) node = { type: 'quote', children: [node] }
    const wrapper = render([node])

    expect(wrapper.findAll('blockquote').length).toBeLessThanOrEqual(MAX_DEPTH + 1)
    wrapper.unmount()
  })

  it('survives values that are not nodes at all', () => {
    const wrapper = render([null, 42, 'text', { type: 'text' }, { type: 'link', children: 'nope' }] as unknown[])

    expect(wrapper.exists()).toBe(true)
    wrapper.unmount()
  })
})
