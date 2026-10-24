import { defineComponent, h, type PropType, type VNodeChild } from 'vue'
import type { MarkdownNode } from '../api/contract'
import { isTrustedHost, leavePath, normaliseHost, safeHttpUrl } from './links'

/**
 * Draws the sanitised block tree of a message with `h()` only: text goes in as text nodes, elements
 * are chosen from a fixed table, and an unknown node is shown as its text. No markup string is ever
 * built and nothing is injected as HTML, so a hostile tree can at most produce text.
 */

/** Deeper than the backend's own nesting limit (3); deeper still is flattened to its text. */
const MAX_DEPTH = 12

function isNode(value: unknown): value is MarkdownNode {
  return typeof value === 'object' && value !== null && typeof (value as { type?: unknown }).type === 'string'
}

function childrenOf(node: MarkdownNode): unknown[] {
  return Array.isArray(node.children) ? node.children : []
}

/** Every text under a node, in order: the fallback for a node the table does not know, or one too deep. */
function textOf(value: unknown): string {
  if (typeof value === 'string') return value
  if (!isNode(value)) return ''
  if (typeof value.text === 'string') return value.text
  return childrenOf(value).map(textOf).join('')
}

function renderChildren(nodes: unknown[], depth: number): VNodeChild[] {
  return nodes.flatMap((child) => renderNode(child, depth + 1))
}

function renderLink(node: MarkdownNode, depth: number): VNodeChild[] {
  const children = renderChildren(childrenOf(node), depth)
  const url = safeHttpUrl(node.href)
  // A link the rules refuse is shown as its text: no anchor, no target.
  if (url === null) return children
  const host = normaliseHost(new URL(url).hostname)
  // The server flags external links; this side checks the host again, and a link goes straight out only when both agree.
  const direct = node.external === false && isTrustedHost(host)
  const anchor = h(
    'a',
    direct
      ? { href: url, target: '_blank', rel: 'noopener noreferrer', class: 'md-link' }
      : { href: leavePath(url), class: 'md-link', 'data-external': 'true' },
    children,
  )
  if (direct) return [anchor]
  // The target host is always shown next to the text, so deceptive link text is visible.
  return [anchor, h('span', { class: 'md-host' }, ` (${host})`)]
}

function renderList(node: MarkdownNode, depth: number): VNodeChild {
  const items = childrenOf(node).filter(isNode)
  const listItems = items.map((item) => h('li', renderChildren(childrenOf(item), depth)))
  if (node.ordered === true) {
    const start = typeof node.start === 'number' && Number.isSafeInteger(node.start) ? node.start : 1
    return h('ol', { start: start === 1 ? undefined : start, class: 'md-list' }, listItems)
  }
  return h('ul', { class: 'md-list' }, listItems)
}

/** One node as VNodes: a known type becomes its element, anything else falls back to its text. */
export function renderNode(value: unknown, depth = 0): VNodeChild[] {
  if (typeof value === 'string') return [value]
  if (!isNode(value)) return []
  if (depth > MAX_DEPTH) return [textOf(value)]
  switch (value.type) {
    case 'paragraph':
      return [h('p', { class: 'md-paragraph' }, renderChildren(childrenOf(value), depth))]
    case 'text':
      return typeof value.text === 'string' ? [value.text] : []
    case 'br':
      return [h('br')]
    case 'strong':
      return [h('strong', renderChildren(childrenOf(value), depth))]
    case 'em':
      return [h('em', renderChildren(childrenOf(value), depth))]
    case 'del':
      return [h('del', renderChildren(childrenOf(value), depth))]
    case 'code':
      return [h('code', typeof value.text === 'string' ? value.text : '')]
    case 'code_block':
      return [h('pre', { class: 'md-code-block' }, [h('code', typeof value.text === 'string' ? value.text : '')])]
    case 'list':
      return [renderList(value, depth)]
    case 'quote':
      return [h('blockquote', { class: 'md-quote' }, renderChildren(childrenOf(value), depth))]
    case 'link':
      return renderLink(value, depth)
    default:
      return childrenOf(value).length > 0 ? renderChildren(childrenOf(value), depth) : [textOf(value)]
  }
}

/** The VNodes of a whole tree. A value that is not an array draws nothing. */
export function renderTree(nodes: unknown): VNodeChild[] {
  return Array.isArray(nodes) ? nodes.flatMap((node) => renderNode(node, 0)) : []
}

/** The Markdown body of a message; paragraphs and lists keep their structure, text keeps its line breaks. */
export const MarkdownTree = defineComponent({
  name: 'MarkdownTree',
  props: {
    nodes: { type: Array as PropType<unknown[]>, required: true },
  },
  setup(props) {
    return () => h('div', { class: 'md-tree' }, renderTree(props.nodes))
  },
})
