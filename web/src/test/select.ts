import type { DOMWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'

type Combobox = Pick<DOMWrapper<Element>, 'trigger' | 'attributes'>

/**
 * Picks an option of an MdSelect the way a user does: opens the menu by clicking the combobox and
 * clicks the option. The menu is teleported to <body>, so it is found through aria-controls.
 */
export async function chooseOption(combobox: Combobox, value: string): Promise<void> {
  await combobox.trigger('click')
  await nextTick()
  const list = document.getElementById(combobox.attributes('aria-controls')!)
  const option = list?.querySelector<HTMLElement>(`[role="option"][data-value="${value}"]`)
  if (!option) throw new Error(`no option "${value}" in the open menu`)
  option.click()
  await nextTick()
}

/** The options of an MdSelect, read by opening its menu (and closing it again with Escape). */
export async function listOptions(
  combobox: Combobox,
): Promise<Array<{ value: string; label: string; disabled: boolean }>> {
  await combobox.trigger('click')
  await nextTick()
  const list = document.getElementById(combobox.attributes('aria-controls')!)
  const result = Array.from(list?.querySelectorAll<HTMLElement>('[role="option"]') ?? []).map((option) => ({
    value: option.dataset.value ?? '',
    label: option.querySelector('span span')?.textContent?.trim() ?? '',
    disabled: option.getAttribute('aria-disabled') === 'true',
  }))
  await combobox.trigger('keydown', { key: 'Escape' })
  await nextTick()
  return result
}
