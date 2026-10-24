<script setup lang="ts">
// The appeal conditions a player confirms before filing. Every team role reads them; only an admin
// edits. A save always creates a new version, nothing is overwritten. The backend stays authoritative.
import { computed, nextTick, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import MdButton from '../components/md/MdButton.vue'
import MdCard from '../components/md/MdCard.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useSessionStore } from '../stores/session'

/** The longest text the backend accepts. */
const MAX_LENGTH = 4000

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const id = useId()

const current = useResource(() => api.appealConditions())
const versions = useResource(() => api.appealConditionVersions())

const draft = ref('')
const saving = ref(false)
const error = ref('')
const conflict = ref(false)
const message = ref('')

// The editor starts from the current text, and follows it when it is reloaded after a save.
watch(
  () => current.data.value?.text,
  (text) => {
    if (text !== undefined) draft.value = text
  },
  { immediate: true },
)

const length = computed(() => draft.value.length)
const valid = computed(() => draft.value.trim().length > 0 && length.value <= MAX_LENGTH)

function when(iso: string): string {
  return formatDateTime(Date.parse(iso), locale.value)
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
      conflict.value = true
      const typed = draft.value
      await Promise.all([current.reload(), versions.reload()])
      // Keep what the admin wrote; the fresh version is shown above for comparison.
      await nextTick()
      draft.value = typed
      return
    }
    error.value = result.error.message
    return
  }
  message.value = t('appealConditions.saved', { version: result.data.version })
  await Promise.all([current.reload(), versions.reload()])
}
</script>

<template>
  <ResourceState
    :loading="current.loading.value && current.data.value === null"
    :error="current.error.value"
    :has-data="current.data.value !== null"
    @retry="current.reload"
  >
    <div v-if="current.data.value" class="flex flex-col gap-4">
      <p class="text-body-large text-on-surface-variant">{{ t('appealConditions.intro') }}</p>

      <MdCard variant="outlined">
        <h2 class="text-title-medium" data-testid="current-version">
          {{ t('appealConditions.current', { version: current.data.value.version }) }}
        </h2>
        <p class="text-body-small text-on-surface-variant">{{ when(current.data.value.createdAt) }}</p>
        <!-- Plain text only: rendered as text and kept on its line breaks, never as markup. -->
        <p class="mt-3 whitespace-pre-line text-body-large" data-testid="current-text">{{ current.data.value.text }}</p>
      </MdCard>

      <form v-if="session.administrator" class="flex flex-col gap-2" data-testid="conditions-form" @submit.prevent="save">
        <label :for="id" class="text-label-large text-on-surface-variant">{{ t('appealConditions.editorLabel') }}</label>
        <textarea
          :id="id"
          v-model="draft"
          rows="12"
          :disabled="saving"
          :aria-invalid="error || length > MAX_LENGTH ? 'true' : undefined"
          :aria-describedby="`${id}-meta`"
          class="w-full rounded-md-xs border border-outline bg-transparent p-3 text-body-large text-on-surface outline-none hover:border-on-surface focus:border-2 focus:-outline-offset-2 focus:outline-2 focus:outline-primary"
          data-testid="conditions-editor"
        />
        <div :id="`${id}-meta`" class="flex flex-wrap items-center gap-2 text-body-small text-on-surface-variant">
          <span data-testid="conditions-counter" :class="length > MAX_LENGTH ? 'text-error' : ''">{{ length }} / {{ MAX_LENGTH }}</span>
          <span>{{ t('appealConditions.format') }}</span>
        </div>
        <p v-if="conflict" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="conditions-conflict">
          {{ t('appealConditions.conflict') }}
        </p>
        <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="conditions-error">{{ error }}</p>
        <div>
          <MdButton type="submit" :disabled="saving || !valid" data-testid="conditions-save">{{ t('appealConditions.save') }}</MdButton>
        </div>
      </form>

      <section v-if="versions.data.value" :aria-label="t('appealConditions.history')">
        <h2 class="mb-2 text-title-medium">{{ t('appealConditions.history') }}</h2>
        <ul class="flex flex-col gap-2">
          <li v-for="item in versions.data.value" :key="item.version" data-testid="version-item" :data-version="item.version">
            <details class="rounded-md-md border border-outline-variant p-3">
              <summary class="cursor-pointer text-label-large">
                {{ t('appealConditions.versionLine', { version: item.version, when: when(item.createdAt) }) }}
              </summary>
              <p class="mt-2 whitespace-pre-line text-body-medium">{{ item.text }}</p>
            </details>
          </li>
        </ul>
      </section>
    </div>
  </ResourceState>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
