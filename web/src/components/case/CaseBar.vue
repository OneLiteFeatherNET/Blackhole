<script setup lang="ts">
// The case's state above the tabs, on every tab: its status and the punishment state, and for a moderator
// one action that closes an open case or reopens a closed one. It sends the same versioned update as the
// status list in the header, so a change someone else made first is reported (409) and never overwritten.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Case, CaseStatus } from '../../api/types'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ kase: Case }>()
const emit = defineEmits<{ updated: [kase: Case]; reload: []; message: [text: string] }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const busy = ref(false)
const conflict = ref(false)
const error = ref('')

const closed = computed(() => props.kase.status === 'CLOSED')
const stateTone = computed(() => (props.kase.punishmentState === 'ACTIVE' ? 'error' : 'neutral'))

// A fresh version means the user is looking at current data again.
watch(
  () => props.kase.version,
  () => {
    conflict.value = false
    error.value = ''
  },
)

async function toggle(): Promise<void> {
  if (busy.value) return
  const status: CaseStatus = closed.value ? 'OPEN' : 'CLOSED'
  const saved = t(status === 'CLOSED' ? 'case.bar.closed' : 'case.bar.reopened')
  busy.value = true
  error.value = ''
  const result = await api.updateCase(props.kase.punishmentIdentifier, { expectedVersion: props.kase.version, status })
  busy.value = false
  if (result.ok) {
    conflict.value = false
    emit('updated', result.data)
    emit('message', saved)
    return
  }
  if (result.error.status === 409) conflict.value = true
  else error.value = result.error.message
}
</script>

<template>
  <section :aria-label="t('case.bar.title')" class="flex flex-wrap items-center gap-3 rounded-md-lg bg-surface-container p-3" data-testid="case-bar">
    <StatusPill :label="t(`case.status.${kase.status}`)" data-testid="case-status" />
    <StatusPill :label="t(`case.state.${kase.punishmentState}`)" :tone="stateTone" data-testid="punishment-state" />
    <StatusPill v-if="kase.permanent" :label="t('player.permanent')" tone="error" />

    <MdButton
      v-if="session.moderator"
      :variant="closed ? 'outlined' : 'tonal'"
      :disabled="busy"
      :data-testid="closed ? 'reopen-case' : 'close-case'"
      class="ml-auto"
      @click="toggle"
    >
      {{ closed ? t('case.bar.reopen') : t('case.bar.close') }}
    </MdButton>

    <div v-if="conflict" role="alert" class="flex w-full flex-wrap items-center gap-3 rounded-md-md bg-error-container p-4 text-on-error-container" data-testid="bar-conflict">
      <p class="min-w-0 flex-1 text-body-medium">{{ t('case.header.conflict') }}</p>
      <MdButton variant="outlined" data-testid="bar-reload" @click="emit('reload')">{{ t('case.header.reload') }}</MdButton>
    </div>
    <p v-if="error" role="alert" class="w-full rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="bar-error">{{ error }}</p>
  </section>
</template>
