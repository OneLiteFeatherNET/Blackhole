<script setup lang="ts">
// The vanilla ban-list import as a routed dialog over `/admin/imports` (`/admin/imports/vanilla/new`). Its preview is a
// dry run; the import itself needs the confirmation the form asks for. Opening the dialog changes nothing.
import { useI18n } from 'vue-i18n'
import RoutedDialog from '../RoutedDialog.vue'
import VanillaImportPanel from './VanillaImportPanel.vue'

const { t } = useI18n()
</script>

<template>
  <RoutedDialog parent="admin-imports" child="admin-imports-vanilla-new" :headline="t('vanillaImport.title')">
    <VanillaImportPanel embedded />
  </RoutedDialog>
</template>
