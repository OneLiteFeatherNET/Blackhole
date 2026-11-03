<script setup lang="ts">
// The section editor as a route: `/admin/sections/new` creates, `/admin/sections/:key/edit` edits. The editor opens
// only once the section is read; a missing one shows the not-found text and no empty form.
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import type { Section } from '../../api/types'
import { useRoutedDialog } from '../../router/routedDialog'
import { CONFIG_PAGE, NO_CONFIG_PAGE } from '../config/configPage'
import RoutedDialog from '../RoutedDialog.vue'
import SectionEditor from './SectionEditor.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const page = inject(CONFIG_PAGE, NO_CONFIG_PAGE)

const editing = route.name === 'admin-section-edit'
const parent = editing ? 'admin-section' : 'admin-sections'
const child = editing ? 'admin-section-edit' : 'admin-section-new'
const dialog = useRoutedDialog({ parent, child })

const section = ref<Section | null>(null)
const ready = ref(!editing)
const missing = ref(false)

if (editing) void load()

async function load(): Promise<void> {
  const result = await api.section(String(route.params.key))
  if (result.ok) section.value = result.data
  else missing.value = true
  ready.value = true
}

async function saved(message: string): Promise<void> {
  await page.changed(message)
  await dialog.close()
}
</script>

<template>
  <RoutedDialog v-if="missing" :parent="parent" :child="child" :headline="t('routedDialog.notFoundTitle')" notFound />
  <SectionEditor v-else-if="ready" :open="dialog.open.value" :section="section" @close="dialog.close" @saved="saved" />
</template>
