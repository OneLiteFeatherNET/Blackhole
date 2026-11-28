<script setup lang="ts">
// The mass-kick form as a routed dialog over `/admin/kick` (`/admin/kick/new`). The form is mounted only while the dialog
// is open, so its reads of the sections and the catalogue happen on opening and nothing is sent before the typed confirmation.
import { useI18n } from 'vue-i18n'
import RoutedDialog from '../RoutedDialog.vue'
import MassKickForm from './MassKickForm.vue'

const { t } = useI18n()
</script>

<template>
  <RoutedDialog parent="admin-kick" child="admin-kick-new" :headline="t('kick.mass.title')">
    <MassKickForm embedded />
  </RoutedDialog>
</template>
