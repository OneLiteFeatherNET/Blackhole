import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { evidenceItem } from '../../test/evidence'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import AddLinkDialog from './AddLinkDialog.vue'
import DeleteEvidenceDialog from './DeleteEvidenceDialog.vue'

const PUNISHMENT = 'ab0e5a9e-1111-4222-8333-444455556666'

describe('AddLinkDialog', () => {
  function mountDialog(addLinkEvidence = vi.fn(async () => ok(evidenceItem()))) {
    return mountWithApp(AddLinkDialog, { api: mockApi({ addLinkEvidence }), props: { open: true, punishment: PUNISHMENT } }).then((mounted) => ({
      ...mounted,
      addLinkEvidence,
    }))
  }

  async function fill(wrapper: Awaited<ReturnType<typeof mountDialog>>['wrapper'], url: string, description = '') {
    await wrapper.get('input[type="url"]').setValue(url)
    if (description) await wrapper.findAll('input')[1]!.setValue(description)
  }

  it('adds a link with its description', async () => {
    const { wrapper, addLinkEvidence } = await mountDialog()

    await fill(wrapper, 'https://example.org/thread/1', 'staff chat')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(addLinkEvidence).toHaveBeenCalledWith(PUNISHMENT, { kind: 'LINK', url: 'https://example.org/thread/1', description: 'staff chat' })
    expect(wrapper.emitted('done')).toEqual([['Link added.']])

    wrapper.unmount()
  })

  it('adds a replay when that kind is chosen', async () => {
    const { wrapper, addLinkEvidence } = await mountDialog()

    await chooseOption(wrapper.get('[role="combobox"]'), 'REPLAY')
    await fill(wrapper, 'https://replay.example/r/1')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(addLinkEvidence).toHaveBeenCalledWith(PUNISHMENT, expect.objectContaining({ kind: 'REPLAY' }))

    wrapper.unmount()
  })

  it.each(['javascript:alert(1)', 'data:text/html,hi', 'ftp://example.org/x', 'example.org/no-scheme', ''])(
    'refuses "%s" without sending a request',
    async (value) => {
      const { wrapper, addLinkEvidence } = await mountDialog()

      await fill(wrapper, value)
      await wrapper.get('[data-testid="confirm"]').trigger('click')
      await flushPromises()

      expect(addLinkEvidence).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('Enter a web address starting with http:// or https://.')
      expect(wrapper.emitted('done')).toBeUndefined()

      wrapper.unmount()
    },
  )

  it('keeps the dialog open with the backend message when adding fails', async () => {
    const { wrapper } = await mountDialog(vi.fn(async () => fail('host is not allowed', 400)))

    await fill(wrapper, 'https://example.org/x')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('host is not allowed')
    expect(wrapper.emitted('done')).toBeUndefined()

    wrapper.unmount()
  })

  it('sends nothing on cancel', async () => {
    const { wrapper, addLinkEvidence } = await mountDialog()

    await fill(wrapper, 'https://example.org/x')
    await wrapper.get('[data-testid="cancel"]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(addLinkEvidence).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('DeleteEvidenceDialog', () => {
  const item = evidenceItem({ kind: 'CHAT_LOG' })

  function mountDialog(deleteEvidence = vi.fn(async () => ok(1))) {
    return mountWithApp(DeleteEvidenceDialog, { api: mockApi({ deleteEvidence }), props: { open: true, item } }).then((mounted) => ({
      ...mounted,
      deleteEvidence,
    }))
  }

  it('names the item it is about to delete', async () => {
    const { wrapper } = await mountDialog()

    expect(wrapper.text()).toContain('The Chat excerpt and its file are deleted permanently.')

    wrapper.unmount()
  })

  it('disables the confirm button until a reason is chosen', async () => {
    const { wrapper } = await mountDialog()

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeDefined()
    await chooseOption(wrapper.get('[role="combobox"]'), 'WRONG_EVIDENCE')
    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('does not send a request while no reason is chosen', async () => {
    const { wrapper, deleteEvidence } = await mountDialog()

    await wrapper.get('[data-testid="confirm"]').trigger('click')

    expect(deleteEvidence).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('deletes with the chosen reason and the note', async () => {
    const { wrapper, deleteEvidence } = await mountDialog()

    await chooseOption(wrapper.get('[role="combobox"]'), 'ERASURE_REQUEST')
    await wrapper.get('input').setValue('ticket 4711')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(deleteEvidence).toHaveBeenCalledWith(item.identifier, { reason: 'ERASURE_REQUEST', note: 'ticket 4711' })
    expect(wrapper.emitted('done')).toEqual([['Evidence deleted.']])

    wrapper.unmount()
  })

  it('sends no request when cancelled', async () => {
    const { wrapper, deleteEvidence } = await mountDialog()

    await chooseOption(wrapper.get('[role="combobox"]'), 'OTHER')
    await wrapper.get('[data-testid="cancel"]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(deleteEvidence).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('keeps the dialog open with the backend message when deleting fails', async () => {
    const { wrapper } = await mountDialog(vi.fn(async () => fail('evidence not found', 404, 'NOT_FOUND')))

    await chooseOption(wrapper.get('[role="combobox"]'), 'OTHER')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('evidence not found')
    expect(wrapper.emitted('done')).toBeUndefined()

    wrapper.unmount()
  })
})
