<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import { useResource } from '../../composables/useResource'

// Warns staff while a proxy has no SignedVelocity (or has not reported its state). Shown nothing when every proxy
// reports it, and nothing when the state cannot be read: a failed read must never raise a false warning.
const { t } = useI18n()
const api = useApi()
const capabilities = useResource(() => api.proxyCapabilities())

const warnings = computed(() => (capabilities.data.value?.proxies ?? []).filter((proxy) => proxy.state !== 'PRESENT'))
</script>

<template>
  <div v-if="warnings.length > 0" role="alert" data-testid="relay-banner" class="flex flex-col gap-2 rounded-md-md bg-error-container p-4 text-on-error-container">
    <p v-for="proxy in warnings" :key="proxy.proxyId" class="text-body-medium break-words">
      {{ proxy.state === 'ABSENT' ? t('relayBanner.absent', { proxy: proxy.proxyId }) : t('relayBanner.unknown', { proxy: proxy.proxyId }) }}
    </p>
  </div>
</template>
