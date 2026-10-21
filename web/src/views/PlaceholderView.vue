<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import MdCard from '../components/md/MdCard.vue'

const { t } = useI18n()
</script>

<template>
  <MdCard>
    <p class="text-body-large text-on-surface-variant">{{ t('placeholder.body') }}</p>
  </MdCard>
</template>
