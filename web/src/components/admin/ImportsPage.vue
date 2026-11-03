<script setup lang="ts">
// The imports page (ADMIN only, see AdminView). `?tab=vanilla|punishments` picks the list or the import, and `?group=` marks
// the imported-punishment group the address names. The vanilla import is a dialog at `/admin/imports/vanilla/new`.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useQueryChoice } from '../../router/queryChoice'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import ImportedPunishmentsPanel from './ImportedPunishmentsPanel.vue'
import VanillaImportDialog from './VanillaImportDialog.vue'

const TABS = ['vanilla', 'punishments'] as const

const { t } = useI18n()
const route = useRoute()
const session = useSessionStore()

const tab = useQueryChoice('tab', TABS, 'vanilla')
const group = computed(() => (typeof route.query.group === 'string' ? route.query.group : ''))
</script>

<template>
  <section v-if="session.administrator" class="flex flex-col gap-6" data-testid="admin-imports">
    <nav :aria-label="t('vanillaImport.tabsLabel')" class="flex flex-wrap gap-2">
      <MdButton
        v-for="value in TABS"
        :key="value"
        as="link"
        :variant="tab === value ? 'filled' : 'outlined'"
        :to="{ name: 'admin-imports', query: { tab: value } }"
        :aria-current="tab === value ? 'page' : undefined"
        :data-testid="`imports-tab-${value}`"
      >
        {{ value === 'vanilla' ? t('vanillaImport.title') : t('importedPunishments.title') }}
      </MdButton>
    </nav>

    <template v-if="tab === 'vanilla'">
      <p class="text-body-large text-on-surface-variant">{{ t('vanillaImport.intro') }}</p>
      <div>
        <MdButton variant="tonal" :to="{ name: 'admin-imports-vanilla-new' }" as="link" data-testid="vanilla-new">{{ t('vanillaImport.newLink') }}</MdButton>
      </div>
      <VanillaImportDialog v-if="route.name === 'admin-imports-vanilla-new'" />
    </template>
    <ImportedPunishmentsPanel v-else :group="group" />
  </section>
</template>
