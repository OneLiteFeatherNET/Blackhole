import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import MdDataTable from './MdDataTable.vue'

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'cost', label: 'Kosten' },
]
const rows = [{ name: 'Steve', cost: 50 }]

describe('MdDataTable', () => {
  it('renders headers and one body row per entry', () => {
    const wrapper = mount(MdDataTable, { props: { label: 'Spieler', columns, rows } })

    expect(wrapper.get('table').attributes('aria-label')).toBe('Spieler')
    expect(wrapper.findAll('th').map((th) => th.text())).toEqual(['Name', 'Kosten'])
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(wrapper.findAll('tbody td').map((td) => td.text())).toEqual(['Steve', '50'])
  })

  it('lets a cell slot render interactive content and forwards its clicks', async () => {
    const onSelect = vi.fn()
    const wrapper = mount(MdDataTable, {
      props: { label: 'Spieler', columns, rows },
      slots: {
        cell: ({ row, column }: { row: Record<string, unknown>; column: { key: string } }) =>
          column.key === 'name'
            ? h('button', { type: 'button', onClick: () => onSelect(row.name) }, String(row.name))
            : String(row[column.key]),
      },
    })

    await wrapper.get('tbody button').trigger('click')

    expect(onSelect).toHaveBeenCalledWith('Steve')
  })
})
