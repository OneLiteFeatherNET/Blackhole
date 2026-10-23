<script setup lang="ts">
// Names of CloudNet tasks, groups or services for a SERVER section. The catalogue (what the proxies
// report) only suggests names: any name can be typed, also when no catalogue is known, and a name
// the catalogue does not list is kept and marked, never rejected.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { CloudNetCatalogue, ServerTargetKind } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdTextField from '../md/MdTextField.vue'
import StatusPill from '../StatusPill.vue'

type CloudNetKind = Exclude<ServerTargetKind, 'PATTERN'>

const props = defineProps<{
  kind: CloudNetKind
  /** null while it is unknown: not asked yet, still loading, or the request failed. */
  catalogue: CloudNetCatalogue | null
  loading: boolean
  error?: string
}>()
const names = defineModel<string[]>({ required: true })

const { t, locale } = useI18n()

/** More entries than this are not rendered at once; the filter narrows them. */
const SHOWN = 100

const filter = ref('')
const draft = ref('')

interface Entry {
  name: string
  detail: string
}

const entries = computed<Entry[]>(() => {
  const catalogue = props.catalogue
  if (!catalogue?.available) return []
  if (props.kind === 'CLOUDNET_TASK') {
    return catalogue.tasks.map((task) => ({
      name: task.name,
      detail: task.groups?.length ? t('sections.targets.inGroups', { groups: task.groups.join(', ') }) : '',
    }))
  }
  if (props.kind === 'CLOUDNET_GROUP') return catalogue.groups.map((name) => ({ name, detail: '' }))
  return catalogue.services.map((service) => ({
    name: service.name,
    detail: [
      service.running ? t('sections.targets.running') : t('sections.targets.stopped'),
      service.task ? t('sections.targets.ofTask', { task: service.task }) : '',
    ]
      .filter(Boolean)
      .join(', '),
  }))
})

const known = computed(() => new Set(entries.value.map((entry) => entry.name.toLowerCase())))
const matching = computed(() => {
  const needle = filter.value.trim().toLowerCase()
  return needle ? entries.value.filter((entry) => entry.name.toLowerCase().includes(needle)) : entries.value
})
const shown = computed(() => matching.value.slice(0, SHOWN))

const hasEntries = computed(() => entries.value.length > 0)
const proxies = computed(() => props.catalogue?.proxies ?? [])
const reportedAt = computed(() => {
  const stamp = props.catalogue?.reportedAt
  const millis = stamp ? Date.parse(stamp) : Number.NaN
  return Number.isNaN(millis) ? '' : formatDateTime(millis, locale.value)
})

const isChosen = (name: string) => names.value.some((chosen) => chosen.toLowerCase() === name.toLowerCase())
// A name is only "not in the catalogue" when a catalogue is known; without one nothing can be said.
const missing = (name: string) => props.catalogue?.available === true && !known.value.has(name.toLowerCase())

function toggle(name: string): void {
  names.value = isChosen(name)
    ? names.value.filter((chosen) => chosen.toLowerCase() !== name.toLowerCase())
    : [...names.value, name]
}

function add(): void {
  const name = draft.value.trim()
  if (name && !isChosen(name)) names.value = [...names.value, name]
  draft.value = ''
}

function remove(name: string): void {
  names.value = names.value.filter((chosen) => chosen !== name)
}

// A name typed but not yet added would be lost on save, so the editor commits it first.
defineExpose({ commit: add })
</script>

<template>
  <fieldset class="flex flex-col gap-3 rounded-md-md border border-outline-variant p-4" data-testid="cloudnet-picker">
    <legend class="px-2 text-title-small">{{ t(`sections.targets.legend.${kind}`) }}</legend>

    <p v-if="loading" role="status" class="text-body-small text-on-surface-variant" data-testid="catalogue-loading">
      {{ t('sections.targets.loading') }}
    </p>
    <p v-else-if="!hasEntries" role="status" class="text-body-small text-on-surface-variant" data-testid="catalogue-missing">
      {{ catalogue?.available ? t('sections.targets.noneOfKind') : t('sections.targets.unavailable') }}
    </p>
    <p v-else role="status" class="text-body-small text-on-surface-variant" data-testid="catalogue-source">
      {{ t('sections.targets.source', { proxies: proxies.join(', ') || '-', time: reportedAt || '-' }) }}
    </p>

    <template v-if="hasEntries">
      <MdTextField v-model="filter" :label="t('sections.targets.filter')" />
      <ul class="flex max-h-56 flex-col overflow-y-auto" :aria-label="t(`sections.targets.legend.${kind}`)">
        <li v-for="entry in shown" :key="entry.name">
          <MdCheckbox
            :model-value="isChosen(entry.name)"
            :label="entry.name"
            :supporting-text="entry.detail || undefined"
            @update:model-value="toggle(entry.name)"
          />
        </li>
      </ul>
      <p v-if="matching.length > shown.length" class="text-body-small text-on-surface-variant">
        {{ t('sections.targets.more', { shown: shown.length, total: matching.length }) }}
      </p>
    </template>

    <div class="flex items-start gap-3">
      <div class="min-w-0 flex-1" @keydown.enter.prevent="add">
        <MdTextField v-model="draft" :label="t('sections.targets.freeText')" :supporting-text="t('sections.targets.freeTextHint')" />
      </div>
      <MdButton variant="tonal" data-testid="add-name" class="mt-2" @click="add">{{ t('sections.targets.add') }}</MdButton>
    </div>

    <div>
      <p class="text-label-large">{{ t('sections.targets.chosen') }}</p>
      <p v-if="names.length === 0" class="text-body-small text-on-surface-variant">{{ t('sections.targets.noneChosen') }}</p>
      <ul v-else class="flex flex-col gap-1" data-testid="chosen">
        <li v-for="name in names" :key="name" class="flex flex-wrap items-center gap-2">
          <span class="text-body-large">{{ name }}</span>
          <StatusPill v-if="missing(name)" :label="t('sections.targets.notInCatalogue')" tone="tertiary" />
          <MdButton variant="text" :aria-label="t('sections.targets.remove', { name })" @click="remove(name)">
            {{ t('sections.targets.removeShort') }}
          </MdButton>
        </li>
      </ul>
    </div>

    <p v-if="error" role="alert" class="text-body-small text-error">{{ error }}</p>
  </fieldset>
</template>
