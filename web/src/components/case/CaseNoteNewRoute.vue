<script setup lang="ts">
// `/cases/:reference/notes/new`: the new-note dialog over the case. Opening the address sends nothing; the note is
// sent by the form only, and a saved note closes the dialog and reloads the case.
import { inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import { useRoutedDialog } from '../../router/routedDialog'
import RoutedDialog from '../RoutedDialog.vue'
import { CASE_ITEMS_KEY } from './caseContext'
import NoteEditor from './NoteEditor.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const items = inject(CASE_ITEMS_KEY)!
const dialog = useRoutedDialog({ parent: 'case', child: 'case-note-new' })

async function save(text: string) {
  const result = await api.addCaseNote(String(route.params.reference), text)
  if (result.ok) {
    await items.changed(t('case.notes.saved'))
    await dialog.close()
  }
  return result
}
</script>

<template>
  <RoutedDialog parent="case" child="case-note-new" :headline="t('case.notes.newTitle')">
    <NoteEditor :label="t('case.notes.newLabel')" :submit-label="t('case.notes.add')" :save="save" cancellable @cancel="dialog.close" />
  </RoutedDialog>
</template>
