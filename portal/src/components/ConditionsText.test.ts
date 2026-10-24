import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ConditionsText from './ConditionsText.vue'

describe('ConditionsText', () => {
  it('renders paragraphs and bullets', () => {
    const wrapper = mount(ConditionsText, { props: { text: 'Read this.\n\n- one\n- two' } })

    expect(wrapper.findAll('p').map((p) => p.text())).toEqual(['Read this.'])
    expect(wrapper.findAll('li').map((li) => li.text())).toEqual(['one', 'two'])
  })

  it('shows markup as literal characters and creates no element from it', () => {
    const wrapper = mount(ConditionsText, { props: { text: '<script>alert(1)</script> <b>bold</b>\n\n- <img src=x onerror=alert(1)>' } })

    expect(wrapper.text()).toContain('<script>alert(1)</script> <b>bold</b>')
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    for (const tag of ['script', 'b', 'img']) expect(wrapper.element.querySelector(tag), `<${tag}> element`).toBeNull()
  })
})
