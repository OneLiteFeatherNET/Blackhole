<script setup lang="ts">
// The recent client rule hits of one player, for the player page. Hashes and rule keys only: no brand,
// channel, mod or command text is stored, so none can be shown.
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import { useResource } from '../../composables/useResource'
import MdProgress from '../md/MdProgress.vue'
import ClientRuleHitTable from './ClientRuleHitTable.vue'

const props = defineProps<{ owner: string }>()

const { t } = useI18n()
const api = useApi()

const hits = useResource(() => api.clientRuleHitsOfOwner(props.owner))
</script>

<template>
  <div class="flex flex-col gap-3" data-testid="client-rule-hits">
    <p class="text-body-medium text-on-surface-variant">{{ t('clientRules.player.hint') }}</p>
    <div aria-live="polite" class="flex flex-col gap-3">
      <MdProgress v-if="hits.loading.value && !hits.data.value" :label="t('state.loading')" />
      <p v-else-if="hits.error.value" data-testid="hits-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ hits.error.value.message }}
      </p>
      <p v-else-if="hits.data.value && hits.data.value.length === 0" data-testid="hits-empty" class="text-body-large text-on-surface-variant">
        {{ t('clientRules.player.empty') }}
      </p>
      <ClientRuleHitTable v-else-if="hits.data.value" :hits="hits.data.value" by="rule" :label="t('clientRules.player.title')" />
    </div>
  </div>
</template>
