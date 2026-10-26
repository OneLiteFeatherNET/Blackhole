<script setup lang="ts">
// The outcome of a kick as a plain text label; the text always says what happened, the tone only supports it.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { KickStatus } from '../../api/types'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ status: KickStatus }>()
const { t } = useI18n()

const tone = computed(() => {
  switch (props.status) {
    case 'DELIVERED':
      return 'primary'
    case 'PENDING':
      return 'neutral'
    default:
      return 'tertiary'
  }
})
</script>

<template>
  <StatusPill :label="t(`kick.status.${status}`)" :tone="tone" data-testid="kick-status" />
</template>
