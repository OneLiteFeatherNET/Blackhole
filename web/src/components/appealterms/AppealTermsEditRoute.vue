<script setup lang="ts">
// The appeal terms editor as a route (`/admin/appeal-terms/new`). It starts from the current text and saves a new
// version; nothing is overwritten. Opening the route sends nothing; only the save button does.
import { computed, inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import { useRoutedDialog } from '../../router/routedDialog'
import { CONFIG_PAGE, NO_CONFIG_PAGE } from '../config/configPage'
import RoutedDialog from '../RoutedDialog.vue'
import MdButton from '../md/MdButton.vue'
import MdTextField from '../md/MdTextField.vue'

/** The longest text the backend accepts. */
const MAX_LENGTH = 4000

const { t } = useI18n()
const api = useApi()
const page = inject(CONFIG_PAGE, NO_CONFIG_PAGE)
const dialog = useRoutedDialog({ parent: 'admin-appeal-terms', child: 'admin-appeal-terms-new' })

const draft = ref('')
const loaded = ref(false)
const saving = ref(false)
const error = ref('')
const conflict = ref(false)

const length = computed(() => draft.value.length)
const tooLong = computed(() => length.value > MAX_LENGTH)
const valid = computed(() => draft.value.trim().length > 0 && !tooLong.value)
const counter = computed(() => `${length.value} / ${MAX_LENGTH}`)

void loadCurrent()

async function loadCurrent(): Promise<void> {
  const result = await api.appealConditions()
  if (result.ok) {
    draft.value = result.data.text
    loaded.value = true
  } else {
    error.value = result.error.message
  }
}

async function save(): Promise<void> {
  if (saving.value || !valid.value) return
  saving.value = true
  error.value = ''
  conflict.value = false
  const result = await api.saveAppealConditions(draft.value)
  saving.value = false
  if (!result.ok) {
    if (result.error.kind === 'CONFLICT') {
      // Keep what the admin wrote; the conflict text asks for a review of the current version.
      conflict.value = true
      await page.reload()
      return
    }
    error.value = result.error.message
    return
  }
  await page.changed(t('appealConditions.saved', { version: result.data.version }))
  await dialog.close()
}
</script>

<template>
  <RoutedDialog parent="admin-appeal-terms" child="admin-appeal-terms-new" :headline="t('appealConditions.newVersion')">
    <form class="flex flex-col gap-4" data-testid="conditions-form" @submit.prevent="save">
      <MdTextField
        v-model="draft"
        multiline
        :rows="12"
        :label="t('appealConditions.editorLabel')"
        :disabled="saving || !loaded"
        :error="tooLong ? t('appealConditions.tooLong', { count: counter }) : ''"
        :supporting-text="`${counter} · ${t('appealConditions.format')}`"
      />
      <p v-if="conflict" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="conditions-conflict">
        {{ t('appealConditions.conflict') }}
      </p>
      <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="conditions-error">{{ error }}</p>
    </form>
    <template #actions>
      <MdButton type="submit" data-testid="conditions-save" :disabled="saving || !valid || !loaded" @click="save">{{ t('appealConditions.save') }}</MdButton>
    </template>
  </RoutedDialog>
</template>
