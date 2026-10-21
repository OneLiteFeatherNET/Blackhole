<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const { t } = useI18n()
const route = useRoute()
</script>

<template>
  <div>
    <h1 class="text-headline-small text-on-surface">{{ t(String(route.meta.titleKey)) }}</h1>
    <p class="mt-2 text-body-large text-on-surface-variant">{{ t('placeholder.body') }}</p>
  </div>
</template>
