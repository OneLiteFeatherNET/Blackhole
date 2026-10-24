import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { MarkdownNode } from '../api/contract'
import { MarkdownTree, renderTree } from './markdownTree'

const text = (value: string): MarkdownNode => ({ type: 'text', text: value })
const paragraph = (...children: MarkdownNode[]): MarkdownNode => ({ type: 'paragraph', children })
const link = (href: string, external: boolean, ...children: MarkdownNode[]): MarkdownNode => ({ type: 'link', href, external, children })

function render(nodes: unknown) {
  return mount(MarkdownTree, { props: { nodes: nodes as MarkdownNode[] } })
}

/** Every element of the rendered tree, and every attribute value on it. */
function elementsOf(root: Element) {
  return [...root.querySelectorAll('*')]
}

describe('supported constructs', () => {
  it('draws paragraphs, emphasis and code with their elements', () => {
    const wrapper = render([
      paragraph(text('plain '), { type: 'strong', children: [text('bold')] }, text(' and '), { type: 'em', children: [text('italic')] }, text(' '), { type: 'del', children: [text('gone')] }),
      { type: 'code_block', text: 'let x = 1' },
    ])

    expect(wrapper.find('p.md-paragraph').text()).toBe('plain bold and italic gone')
    expect(wrapper.find('strong').text()).toBe('bold')
    expect(wrapper.find('em').text()).toBe('italic')
    expect(wrapper.find('del').text()).toBe('gone')
    expect(wrapper.find('pre code').text()).toBe('let x = 1')
  })

  it('draws a line break inside a paragraph', () => {
    const wrapper = render([paragraph(text('one'), { type: 'br' }, text('two'))])

    expect(wrapper.find('p').element.querySelectorAll('br')).toHaveLength(1)
  })

  it('draws unordered and ordered lists, keeping the start number', () => {
    const wrapper = render([
      { type: 'list', ordered: false, children: [{ type: 'item', children: [paragraph(text('a'))] }] },
      { type: 'list', ordered: true, start: 3, children: [{ type: 'item', children: [paragraph(text('c'))] }] },
    ])

    expect(wrapper.find('ul li').text()).toBe('a')
    expect(wrapper.find('ol').attributes('start')).toBe('3')
    expect(wrapper.find('ol li').text()).toBe('c')
  })

  it('draws a quote as a blockquote', () => {
    const wrapper = render([{ type: 'quote', children: [paragraph(text('quoted'))] }])

    expect(wrapper.find('blockquote').text()).toBe('quoted')
  })
})

describe('links', () => {
  it('sends an external link through the interstitial and shows its host', () => {
    const wrapper = render([paragraph(link('https://example.org/page', true, text('the page')))])

    const anchor = wrapper.find('a')
    expect(anchor.attributes('href')).toMatch(/^\/leave#/)
    expect(anchor.attributes('target')).toBeUndefined()
    expect(anchor.text()).toBe('the page')
    expect(wrapper.find('.md-host').text()).toContain('example.org')
  })

  it('lets a trusted link go straight out, without an opener', () => {
    const wrapper = render([paragraph(link('https://appeal.onelitefeather.net/x', false, text('here')))])

    const anchor = wrapper.find('a')
    expect(anchor.attributes('href')).toBe('https://appeal.onelitefeather.net/x')
    expect(anchor.attributes('target')).toBe('_blank')
    expect(anchor.attributes('rel')).toBe('noopener noreferrer')
    expect(wrapper.find('.md-host').exists()).toBe(false)
  })

  it('sends a link through the interstitial when the server flag and the host disagree', () => {
    const wrapper = render([paragraph(link('https://onelitefeather.net.evil.example/', false, text('trust me')))])

    expect(wrapper.find('a').attributes('href')).toMatch(/^\/leave#/)
  })

  it('shows the text of a refused link without any anchor', () => {
    const wrapper = render([paragraph(link('javascript:alert(1)', true, text('click')))])

    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.text()).toContain('click')
  })
})

describe('hostile trees', () => {
  const hostile: Array<[string, unknown]> = [
    ['a script element as text', paragraph(text('<script>alert(1)</script>'))],
    ['an image with an error handler as text', paragraph(text('<img src=x onerror=alert(1)>'))],
    ['an unknown node type', [{ type: 'iframe', src: 'https://evil.example' }]],
    ['an unknown node with children', [{ type: 'script', children: [text('alert(1)')] }]],
    ['a link with a javascript target', paragraph(link('javascript:alert(1)', true, text('x')))],
    ['a link with a data target', paragraph(link('data:text/html,<script>alert(1)</script>', true, text('x')))],
    ['a link without a target', paragraph({ type: 'link', children: [text('x')] } as MarkdownNode)],
    ['a link with a number target', paragraph({ type: 'link', href: 7 as unknown as string, external: true, children: [text('x')] })],
    ['a text node without text', [{ type: 'text' }]],
    ['a null entry', [null, 'bare string', 42]],
    ['a tree that is not an array', { type: 'paragraph' }],
  ]

  it.each(hostile)('draws %s without an element that can run code', (_name, tree) => {
    const wrapper = render(tree)

    for (const element of elementsOf(wrapper.element)) {
      expect(['SCRIPT', 'IFRAME', 'OBJECT', 'EMBED', 'STYLE', 'IMG']).not.toContain(element.tagName)
      for (const attribute of element.getAttributeNames()) {
        expect(attribute.startsWith('on'), `${element.tagName} ${attribute}`).toBe(false)
      }
    }
  })

  it('never renders a script as an element, even when its text is in a text node', () => {
    const wrapper = render([paragraph(text('<script>alert(1)</script>'))])

    expect(wrapper.element.querySelector('script')).toBeNull()
    expect(wrapper.text()).toContain('<script>alert(1)</script>')
  })

  it.each(hostile)('gives an href only for an http or https link or a /leave path (%s)', (_name, tree) => {
    const wrapper = render(tree)

    for (const anchor of wrapper.findAll('a')) {
      const href = anchor.attributes('href') ?? ''
      expect(href === '' || /^https?:\/\//.test(href) || href.startsWith('/leave#'), href).toBe(true)
    }
  })

  it('stops a tree nested fifty levels deep without failing', () => {
    let node: MarkdownNode = text('deepest')
    for (let depth = 0; depth < 50; depth++) node = { type: 'quote', children: [node] }

    const wrapper = render([node])

    expect(wrapper.text()).toContain('deepest')
  })

  it('draws an empty tree as nothing', () => {
    expect(renderTree([])).toEqual([])
    expect(render([]).text()).toBe('')
  })
})
