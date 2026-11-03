<script setup lang="ts">
// `/cases/:reference/notes/:noteId/redact`: removes the text of a note, ADMIN only. The address only opens the
// confirmation (reason and button); nothing is removed on navigation, back or reload.
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import { useRoutedDialog } from '../../router/routedDialog'
import RoutedDialog from '../RoutedDialog.vue'
import { findNote } from './caseItems'
import { CASE_ITEMS_KEY } from './caseContext'
import RedactNoteDialog from './RedactNoteDialog.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const items = inject(CASE_ITEMS_KEY)!
const dialog = useRoutedDialog({ parent: 'case', child: 'case-note-redact' })

const reference = String(route.params.reference)
const noteId = String(route.params.noteId)
const checked = ref(false)
const missing = ref(false)

onMounted(async () => {
  const found = await findNote(api, reference, noteId)
  checked.value = true
  missing.value = found.kind !== 'found'
})

async function done(message: string) {
  await items.changed(message)
  await dialog.close()
}
</script>

<template>
  <RoutedDialog v-if="checked && missing" parent="case" child="case-note-redact" :headline="t('case.redact.title')" :not-found="true" />
  <RedactNoteDialog v-else-if="checked" :open="dialog.open.value" :reference="reference" :note-id="noteId" @close="dialog.close" @done="done" />
</template>
