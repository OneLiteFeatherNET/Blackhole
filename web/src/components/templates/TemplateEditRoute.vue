<script setup lang="ts">
// The template editor as a route (`restructure-staff-panel`, decision 3): `/admin/templates/new` creates,
// `/admin/templates/:id/edit` edits. The editor opens only once the template is read, so a missing one shows the
// not-found text and never an empty form. Delete is offered on the edit route only; it asks first and sends
// nothing before the confirmation, and it goes back to the list.
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../../api/inject'
import type { Template } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { useRoutedDialog } from '../../router/routedDialog'
import { CONFIG_PAGE, NO_CONFIG_PAGE } from '../config/configPage'
import RoutedDialog from '../RoutedDialog.vue'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import TemplateEditor from './TemplateEditor.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const page = inject(CONFIG_PAGE, NO_CONFIG_PAGE)

const editing = route.name === 'admin-template-edit'
const parent = editing ? 'admin-template' : 'admin-templates'
const child = editing ? 'admin-template-edit' : 'admin-template-new'
const dialog = useRoutedDialog({ parent, child })
const sections = useResource(() => api.sections())

const template = ref<Template | null>(null)
const ready = ref(!editing)
const missing = ref(false)
const confirming = ref(false)
const deleting = ref(false)
const deleteError = ref('')

if (editing) void load()

async function load(): Promise<void> {
  const result = await api.template(String(route.params.id))
  if (result.ok) template.value = result.data
  else missing.value = true
  ready.value = true
}

async function saved(message: string): Promise<void> {
  await page.changed(message)
  await dialog.close()
}

async function remove(): Promise<void> {
  if (!template.value || deleting.value) return
  deleting.value = true
  const result = await api.deleteTemplate(template.value.identifier)
  deleting.value = false
  if (!result.ok) {
    deleteError.value = result.error.kind === 'CONFLICT' ? `${t('templates.delete.inUse')} ${result.error.message}` : result.error.message
    return
  }
  confirming.value = false
  await router.replace({ name: 'admin-templates' })
}
</script>

<template>
  <RoutedDialog v-if="missing" :parent="parent" :child="child" :headline="t('routedDialog.notFoundTitle')" notFound />
  <template v-else-if="ready">
    <TemplateEditor :open="dialog.open.value" :template="template" :sections="sections.data.value ?? []" @close="dialog.close" @saved="saved">
      <template v-if="editing" #extra-actions>
        <MdButton variant="error" data-testid="delete-template" @click="confirming = true">{{ t('templates.delete.open') }}</MdButton>
      </template>
    </TemplateEditor>
    <MdDialog v-if="template" :open="confirming" :headline="t('templates.delete.title', { key: template.key })" @close="confirming = false">
      <p>{{ t('templates.delete.body') }}</p>
      <p v-if="deleteError" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ deleteError }}
      </p>
      <template #actions>
        <MdButton variant="text" data-testid="cancel-delete" @click="confirming = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton variant="error" data-testid="confirm-delete" :disabled="deleting" @click="remove">{{ t('templates.delete.confirm') }}</MdButton>
      </template>
    </MdDialog>
  </template>
</template>
