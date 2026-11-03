<script setup lang="ts">
// Close or reopen a case, behind its child route (`/cases/:ref/close` or `/cases/:ref/reopen`, decision 8 rule 4).
// Opening the route sends nothing; the confirmed change carries the version that was read. A viewer sees the reason
// and no confirm action. The case page shows the result and offers the undo of a close (caseContext.ts).
import { computed, inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { CaseStatus } from '../../api/types'
import { useRoutedAction } from '../../router/routedDialog'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import RoutedDialog from '../RoutedDialog.vue'
import { CASE_CONTEXT_KEY } from './caseContext'
import { reopenTarget, type ReopenTarget } from './reopenStatus'

const props = defineProps<{ reference: string; action: 'close' | 'reopen' }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()
const context = inject(CASE_CONTEXT_KEY)
if (!context) throw new Error('CaseStatusDialog must be rendered by the case page')

const child = props.action === 'close' ? 'case-close' : 'case-reopen'
const headline = computed(() => t(props.action === 'close' ? 'entity.caseDialog.closeTitle' : 'entity.caseDialog.reopenTitle'))

// Closing always goes to CLOSED. Reopening goes to the status read from the timeline, which is only looked up here.
const target = ref<ReopenTarget | null>(props.action === 'close' ? { status: 'CLOSED', found: true } : null)
const conflict = ref(false)

if (props.action === 'reopen') {
  void reopenTarget(api, context.kase.value?.punishmentIdentifier ?? props.reference).then((found) => {
    target.value = found
  })
}

const version = computed(() => context.kase.value?.version ?? null)

const routed = useRoutedAction({
  parent: 'case',
  child,
  version,
  send: async (expectedVersion: number) => {
    const kase = context.kase.value
    const status: CaseStatus | undefined = target.value?.status
    if (!kase || !status || !session.moderator) throw new Error('the change is not ready')
    conflict.value = false
    const result = await api.updateCase(kase.punishmentIdentifier, { expectedVersion, status })
    if (!result.ok) {
      conflict.value = result.error.status === 409
      throw result.error
    }
    context.applied({ kase: result.data, previous: kase.status, action: props.action })
  },
})

const canConfirm = computed(() => session.moderator && target.value !== null && !routed.pending.value)
</script>

<template>
  <RoutedDialog parent="case" :child="child" :headline="headline" :failed="routed.failed.value">
    <p v-if="!session.moderator" data-testid="case-status-body" class="text-body-large text-on-surface-variant">{{ t('entity.caseDialog.viewer') }}</p>
    <p v-else-if="props.action === 'close'" data-testid="case-status-body" class="text-body-large text-on-surface-variant">{{ t('entity.caseDialog.closeBody') }}</p>
    <p v-else-if="target === null" data-testid="case-status-body" class="text-body-large text-on-surface-variant">{{ t('entity.caseDialog.reopenLoading') }}</p>
    <p v-else data-testid="case-status-body" class="text-body-large text-on-surface-variant">
      {{ target.found ? t('entity.caseDialog.reopenBody', { status: t(`case.status.${target.status}`) }) : t('entity.caseDialog.reopenFallback') }}
    </p>
    <p v-if="conflict" role="alert" data-testid="case-status-conflict" class="mt-4 text-error">{{ t('entity.caseDialog.conflict') }}</p>

    <template #actions>
      <MdButton
        v-if="session.moderator"
        data-testid="case-status-confirm"
        :variant="props.action === 'close' ? 'tonal' : 'filled'"
        :disabled="!canConfirm"
        @click="routed.confirm"
      >
        {{ t(props.action === 'close' ? 'entity.caseDialog.confirmClose' : 'entity.caseDialog.confirmReopen') }}
      </MdButton>
    </template>
  </RoutedDialog>
</template>
