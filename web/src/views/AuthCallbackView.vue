<script setup lang="ts">
import { useI18n } from 'vue-i18n'

// The router guard completes the sign-in and redirects before this is ever shown; it only exists so
// the route has a component.
const { t } = useI18n()
</script>

<template>
  <p class="text-body-large">{{ t('auth.signingIn') }}</p>
</template>
