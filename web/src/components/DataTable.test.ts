import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../test/mount'
import DataTable, { type DataColumn } from './DataTable.vue'

interface Row extends Record<string, unknown> {
  name: string
  status: string
  note: string
}

const COLUMNS: DataColumn<Row>[] = [
  { key: 'name', label: 'Case', priority: 1, sortable: true },
  { key: 'status', label: 'Status', priority: 2 },
  { key: 'note', label: 'Note', priority: 3 },
]
const ROWS: Row[] = [
  { name: '#AB12CD', status: 'Open', note: 'spam' },
  { name: '#ZZ99XX', status: 'Closed', note: 'chat' },
]

function setup(props: Record<string, unknown> = {}, slots: Record<string, () => unknown> = {}) {
  return mountWithApp(DataTable, { props: { caption: 'Cases', columns: COLUMNS, rows: ROWS, ...props }, slots })
}

describe('DataTable: structure', () => {
  it('names the table with its caption', async () => {
    const { wrapper } = await setup()

    expect(wrapper.get('table caption').text()).toBe('Cases')

    wrapper.unmount()
  })

  it('gives every header scope col and every cell its column label as data-label', async () => {
    const { wrapper } = await setup()

    const headers = wrapper.findAll('thead th')
    expect(headers.every((header) => header.attributes('scope') === 'col')).toBe(true)
    const firstCells = wrapper.findAll('tbody tr')[0]!.findAll('td')
    expect(firstCells.map((cell) => cell.attributes('data-label'))).toEqual(['Case', 'Status', 'Note'])

    wrapper.unmount()
  })

  it('marks priority 3 cells so that they are hidden below 840 px', async () => {
    const { wrapper } = await setup()

    const noteCell = wrapper.findAll('tbody tr')[0]!.findAll('td')[2]!
    expect(noteCell.classes()).toContain('max-[840px]:hidden')
    expect(wrapper.findAll('tbody tr')[0]!.findAll('td')[0]!.classes()).not.toContain('max-[840px]:hidden')

    wrapper.unmount()
  })

  it('renders the cell slot for each cell', async () => {
    const { wrapper } = await setup({}, { cell: ({ row }: { row: Row }) => h('span', `${row.name}!`) })

    expect(wrapper.findAll('tbody td').map((cell) => cell.text())).toContain('#AB12CD!')

    wrapper.unmount()
  })
})

describe('DataTable: sorting', () => {
  it('marks the active sortable column with aria-sort and the others as none', async () => {
    const columns: DataColumn<Row>[] = [
      { key: 'name', label: 'Case', priority: 1, sortable: true },
      { key: 'status', label: 'Status', priority: 2, sortable: true },
    ]
    const { wrapper } = await setup({ columns, sort: { key: 'name', direction: 'ascending' } })

    const [name, status] = wrapper.findAll('thead th')
    expect(name!.attributes('aria-sort')).toBe('ascending')
    expect(status!.attributes('aria-sort')).toBe('none')

    wrapper.unmount()
  })

  it('leaves aria-sort off a column that cannot be sorted', async () => {
    const { wrapper } = await setup()

    expect(wrapper.findAll('thead th')[1]!.attributes('aria-sort')).toBeUndefined()

    wrapper.unmount()
  })

  it('emits the key when a sortable header button is pressed', async () => {
    const { wrapper } = await setup()

    await wrapper.get('thead th button').trigger('click')

    expect(wrapper.emitted('sort')).toEqual([['name']])

    wrapper.unmount()
  })
})

describe('DataTable: states', () => {
  it('shows the empty slot when there are no rows', async () => {
    const { wrapper } = await setup({ rows: [] }, { empty: () => h('p', { 'data-testid': 'nothing' }, 'Nothing here.') })

    expect(wrapper.find('[data-testid="nothing"]').exists()).toBe(true)
    expect(wrapper.find('tbody tr').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows skeleton rows and marks the table busy while loading the first page', async () => {
    const { wrapper } = await setup({ rows: [], loading: true }, { empty: () => h('p', { 'data-testid': 'nothing' }, 'Nothing here.') })

    expect(wrapper.get('[aria-busy="true"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-skeleton="row"]').length).toBeGreaterThan(0)
    expect(wrapper.find('[data-testid="nothing"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the error as a ProblemBanner instead of rows', async () => {
    const { wrapper } = await setup({ error: 'The backend could not be reached' })

    expect(wrapper.get('[role="alert"]').text()).toContain('The backend could not be reached')
    expect(wrapper.find('tbody tr').exists()).toBe(false)

    wrapper.unmount()
  })
})
