import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { i18n } from '../i18n'
import Breadcrumbs from './Breadcrumbs.vue'

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { render: () => null } },
      { path: '/cases', component: { render: () => null } },
    ],
  })
}

function mounting(items: { label: string; to?: string }[]) {
  i18n.global.locale.value = 'en'
  return mount(Breadcrumbs, {
    props: { items },
    global: { plugins: [i18n, router()] },
    attachTo: document.body,
  })
}

describe('Breadcrumbs', () => {
  it('links every item but the last', () => {
    const wrapper = mounting([{ label: 'Cases', to: '/cases' }, { label: 'ABCD0001', to: '/cases/ABCD0001' }, { label: 'Report' }])

    const links = wrapper.findAll('a')
    expect(links.map((link) => link.attributes('href'))).toEqual(['/cases', '/cases/ABCD0001'])

    wrapper.unmount()
  })

  it('marks the last item as the current page, without a link', () => {
    const wrapper = mounting([{ label: 'Cases', to: '/cases' }, { label: 'ABCD0001' }])

    const current = wrapper.get('[aria-current="page"]')
    expect(current.text()).toBe('ABCD0001')
    expect(current.element.tagName, 'the current page is not a link').not.toBe('A')
    expect(wrapper.findAll('[aria-current]'), 'only one current page').toHaveLength(1)

    wrapper.unmount()
  })

  it('names the trail as a navigation landmark', () => {
    const wrapper = mounting([{ label: 'Cases', to: '/cases' }, { label: 'ABCD0001' }])

    expect(wrapper.get('nav').attributes('aria-label')).toBe('Breadcrumbs')

    wrapper.unmount()
  })
})
