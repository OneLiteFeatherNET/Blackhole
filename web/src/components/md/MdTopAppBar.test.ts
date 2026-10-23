import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import MdTopAppBar from './MdTopAppBar.vue'

const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] })

function mountBar(slots: Record<string, () => unknown> = {}) {
  return mount(MdTopAppBar, { props: { brand: 'Blackhole' }, slots: slots as never, global: { plugins: [router] } })
}

describe('MdTopAppBar', () => {
  it('shows the product mark as a link to the start page', () => {
    const wrapper = mountBar()

    const mark = wrapper.get('header a')
    expect(mark.text()).toBe('Blackhole')
    expect(mark.attributes('href')).toBe('/')
  })

  it('renders the leading, search and actions slots in that order', () => {
    const wrapper = mountBar({
      leading: () => h('button', { type: 'button' }, 'Menü'),
      search: () => h('input', { 'aria-label': 'Suche' }),
      actions: () => h('button', { type: 'button' }, 'Konto'),
    })

    const order = wrapper.findAll('button, a, input').map((element) => element.element.tagName.toLowerCase() + ':' + (element.text() || element.attributes('aria-label')))
    expect(order).toEqual(['button:Menü', 'a:Blackhole', 'input:Suche', 'button:Konto'])
  })

  it('forwards a click inside the actions slot', async () => {
    const onClick = vi.fn()
    const wrapper = mountBar({ actions: () => h('button', { type: 'button', onClick }, 'Konto') })

    await wrapper.get('button').trigger('click')

    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
