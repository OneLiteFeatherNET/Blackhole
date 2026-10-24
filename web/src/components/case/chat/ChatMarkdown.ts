// Draws the sanitised block tree the backend sends with a message. Every element is made with h():
// text goes in as text nodes and nothing is ever parsed as HTML, so there is no v-html here. Links
// are drawn only for http(s) targets; an external one goes through the interstitial at /leave.
import { defineComponent, h, type PropType, type VNode, type VNodeChild } from 'vue'
import { encodeLeaveTarget, safeHttpUrl } from './safeUrl'

/** The nodes the backend's `blocks` can hold; a field the type does not use is absent. */
export interface ChatNode {
  type: string
  text?: string
  href?: string
  external?: boolean
  ordered?: boolean
  start?: number
  children?: ChatNode[]
}

/** A bound on work: a hostile tree can never make the panel build an unbounded number of elements. */
export const MAX_NODES = 1000
export const MAX_DEPTH = 12

interface Budget {
  nodes: number
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)

function textOf(raw: Record<string, unknown>): string {
  return typeof raw.text === 'string' ? raw.text : ''
}

function childrenOf(raw: Record<string, unknown>, depth: number, budget: Budget): VNodeChild[] {
  if (!Array.isArray(raw.children)) return []
  return raw.children.flatMap((child) => renderNode(child, depth, budget))
}

function linkOf(href: string, external: boolean, children: VNodeChild[]): VNode {
  if (!external) return h('a', { href, target: '_blank', rel: 'noopener noreferrer nofollow ugc' }, children)
  // The fragment carries the target, so the interstitial can show it and no server or Referer sees it.
  return h('a', { href: `/leave#${encodeLeaveTarget(href)}`, target: '_blank', rel: 'noopener noreferrer nofollow ugc' }, [
    ...children,
    ' ',
    h('span', { class: 'text-body-small text-on-surface-variant' }, `(${new URL(href).host})`),
  ])
}

function renderNode(value: unknown, depth: number, budget: Budget): VNodeChild[] {
  if (!isRecord(value) || depth > MAX_DEPTH || budget.nodes <= 0) return []
  budget.nodes -= 1
  const type = typeof value.type === 'string' ? value.type : ''
  switch (type) {
    case 'text':
      return [textOf(value)]
    case 'paragraph':
      return [h('p', childrenOf(value, depth + 1, budget))]
    case 'br':
      return [h('br')]
    case 'strong':
      return [h('strong', childrenOf(value, depth + 1, budget))]
    case 'em':
      return [h('em', childrenOf(value, depth + 1, budget))]
    case 'del':
      return [h('del', childrenOf(value, depth + 1, budget))]
    case 'code':
      return [h('code', textOf(value))]
    case 'code_block':
      return [h('pre', [h('code', textOf(value))])]
    case 'quote':
      return [h('blockquote', childrenOf(value, depth + 1, budget))]
    case 'list': {
      const children = childrenOf(value, depth + 1, budget)
      if (value.ordered !== true) return [h('ul', children)]
      const start = typeof value.start === 'number' && Number.isSafeInteger(value.start) ? value.start : 1
      return [h('ol', { start }, children)]
    }
    case 'item':
      return [h('li', childrenOf(value, depth + 1, budget))]
    case 'link': {
      const children = childrenOf(value, depth + 1, budget)
      const href = typeof value.href === 'string' ? safeHttpUrl(value.href) : null
      // A target that is not http(s) is never a link; its text stays.
      return href === null ? children : [linkOf(href, value.external === true, children)]
    }
    default:
      // Unknown types are not drawn; their readable children are.
      return childrenOf(value, depth + 1, budget)
  }
}

/** Renders a message's block tree; `nodes` null or empty draws nothing. */
export default defineComponent({
  name: 'ChatMarkdown',
  props: {
    nodes: { type: Array as PropType<ChatNode[] | null>, default: null },
  },
  setup(props) {
    return () => {
      const budget: Budget = { nodes: MAX_NODES }
      const children = Array.isArray(props.nodes) ? props.nodes.flatMap((node) => renderNode(node, 0, budget)) : []
      return h('div', { class: 'chat-markdown break-words', 'data-testid': 'chat-markdown' }, children)
    }
  },
})
