<script setup lang="ts">
// The protection of a player: linked staff or a permission the proxy reported. Shown only when the player is exempt;
// a failed read shows nothing, because the badge is information, not a gate (the backend refuses the write anyway).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ExemptionStatus } from '../../api/types'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ owner: string }>()

const { t } = useI18n()
const api = useApi()
const status = ref<ExemptionStatus | null>(null)

watch(
  () => props.owner,
  async (owner) => {
    status.value = null
    const result = await api.exemption(owner)
    if (owner !== props.owner) return // the player changed while the answer was on its way
    status.value = result.ok && result.data.exempt ? result.data : null
  },
  { immediate: true },
)

const linked = computed(() => status.value?.sources.includes('LINKED_STAFF') ?? false)
const label = computed(() => (linked.value ? t('exemption.badge.linked') : t('exemption.badge.exempt')))

// The tooltip names the rank that protects the player; the permission note tells the moderator that a removed
// permission keeps protecting an offline player until the next login.
const tooltip = computed(() => {
  if (!status.value) return ''
  const rank = t(`exemption.rank.${status.value.rank ?? 'MODERATOR'}`)
  const parts = [t('exemption.badge.protectedAt', { rank })]
  if (status.value.sources.includes('PERMISSION')) parts.push(t('exemption.badge.permissionNote'))
  return parts.join(' ')
})
</script>

<template>
  <StatusPill v-if="status" :label="label" tone="tertiary" :title="tooltip" data-testid="exemption-badge" />
</template>
