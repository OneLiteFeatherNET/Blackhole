<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { DiscordLink } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import DataTable, { type DataColumn } from '../DataTable.vue'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import ResourceState from '../ResourceState.vue'
import RowMenu, { type RowMenuItem } from '../RowMenu.vue'

const { t, locale } = useI18n()
const api = useApi()

const links = useResource(() => api.discordLinks())
const removing = ref<DiscordLink | null>(null)
const removingNow = ref(false)
const error = ref('')
const message = ref('')

const columns = computed<DataColumn<DiscordLink>[]>(() => [
  { key: 'kind', label: t('discordLinks.columns.kind'), priority: 2 },
  { key: 'subject', label: t('discordLinks.columns.who'), priority: 1 },
  { key: 'linkedAt', label: t('discordLinks.columns.linkedAt'), priority: 3 },
  { key: 'id', label: t('discordLinks.columns.actions'), priority: 1, align: 'end' },
])

// Removing a link is destructive, so it lives in the row menu and is confirmed before anything is sent.
const rowMenu = computed<RowMenuItem[]>(() => [{ key: 'unlink', label: t('discordLinks.unlink'), destructive: true }])

/** A staff link shows the person's name; a player link only the first 8 characters of the hashed owner. */
function who(link: DiscordLink): string {
  return link.kind === 'STAFF' ? link.displayName || t('discordLinks.unnamed') : link.subject.slice(0, 8)
}

function ask(link: DiscordLink): void {
  removing.value = link
  error.value = ''
}

function onRowMenu(link: DiscordLink, key: string): void {
  if (key === 'unlink') ask(link)
}

async function confirm(): Promise<void> {
  if (!removing.value || removingNow.value) return
  removingNow.value = true
  const result = await api.removeDiscordLink(removing.value.id)
  removingNow.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  removing.value = null
  message.value = t('discordLinks.removed')
  await links.reload()
}
</script>

<template>
  <section aria-labelledby="discord-links-title" class="flex flex-col gap-3" data-testid="discord-links">
    <h2 id="discord-links-title" class="text-title-medium">{{ t('discordLinks.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('discordLinks.intro') }}</p>
    <ResourceState :loading="links.loading.value" :error="links.error.value" :has-data="links.data.value !== null" @retry="links.reload">
      <p v-if="links.data.value?.length === 0" data-testid="discord-links-empty">{{ t('discordLinks.empty') }}</p>
      <DataTable v-else-if="links.data.value" :caption="t('discordLinks.title')" :columns="columns" :rows="links.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'kind'">{{ t(`discordLinks.kind.${row.kind}`) }}</template>
          <code v-else-if="column.key === 'subject' && row.kind === 'PLAYER'" class="font-mono text-body-small">{{ who(row) }}</code>
          <template v-else-if="column.key === 'subject'">{{ who(row) }}</template>
          <template v-else-if="column.key === 'linkedAt'">{{ formatDateTime(row.linkedAt, locale) }}</template>
          <div v-else class="flex justify-end">
            <RowMenu :label="who(row)" :items="rowMenu" @select="(key) => onRowMenu(row, key)" />
          </div>
        </template>
      </DataTable>
    </ResourceState>
  </section>

  <MdDialog :open="removing !== null" :headline="t('discordLinks.unlinkTitle', { name: removing ? who(removing) : '' })" @close="removing = null">
    <p>{{ t('discordLinks.unlinkBody') }}</p>
    <p v-if="error" data-testid="discord-error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template #actions>
      <MdButton variant="text" data-testid="discord-cancel" @click="removing = null">{{ t('actions.cancel') }}</MdButton>
      <MdButton variant="error" data-testid="discord-confirm" :disabled="removingNow" @click="confirm">{{ t('discordLinks.unlinkConfirm') }}</MdButton>
    </template>
  </MdDialog>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
