<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import StaffActorsPanel from '../components/admin/StaffActorsPanel.vue'
import VanillaImportPanel from '../components/admin/VanillaImportPanel.vue'

// ADMIN only: the router guard keeps everyone else away, the backend enforces it per call.
const { t } = useI18n()
</script>

<template>
  <div class="flex flex-col gap-8">
    <p class="text-body-large text-on-surface-variant">{{ t('admin.intro') }}</p>
    <StaffActorsPanel />
    <VanillaImportPanel />
  </div>
</template>
