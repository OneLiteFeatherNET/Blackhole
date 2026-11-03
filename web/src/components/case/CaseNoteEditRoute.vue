<script setup lang="ts">
// `/cases/:reference/notes/:noteId/edit`: the edit dialog of a note over the case. The text is read when the address
// opens; nothing is sent until the form is saved. A note that is gone or redacted shows not-found, never a form.
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError } from '../../api/types'
import { useRoutedDialog } from '../../router/routedDialog'
import RoutedDialog from '../RoutedDialog.vue'
import ResourceState from '../ResourceState.vue'
import { findNote } from './caseItems'
import { CASE_ITEMS_KEY } from './caseContext'
import NoteEditor from './NoteEditor.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const items = inject(CASE_ITEMS_KEY)!
const dialog = useRoutedDialog({ parent: 'case', child: 'case-note-edit' })

const reference = String(route.params.reference)
const noteId = String(route.params.noteId)
const loading = ref(true)
const missing = ref(false)
const failure = ref<ApiError | null>(null)
const text = ref('')

onMounted(async () => {
  const found = await findNote(api, reference, noteId)
  loading.value = false
  if (found.kind === 'found') text.value = found.value.text
  else if (found.kind === 'missing') missing.value = true
  else failure.value = found.error
})

async function save(value: string) {
  const result = await api.editCaseNote(reference, noteId, value)
  if (result.ok) {
    await items.changed(t('case.notes.saved'))
    await dialog.close()
  }
  return result
}
</script>

<template>
  <RoutedDialog parent="case" child="case-note-edit" :headline="t('case.notes.editTitle')" :not-found="missing">
    <ResourceState :loading="loading" :error="failure" :has-data="!loading && failure === null">
      <NoteEditor :label="t('case.notes.editLabel')" :submit-label="t('case.notes.save')" :initial="text" :save="save" cancellable @cancel="dialog.close" />
    </ResourceState>
  </RoutedDialog>
</template>
