<script lang="ts">
// The option of a select or a toggle group. `testid` names the control in tests; it defaults to `<filter>-<value>`.
export interface FilterOption {
  value: string
  label: string
  testid?: string
}

interface BaseFilter {
  id: string
  label: string
}

// The four kinds of the one filter idiom (design section 2, "One filter idiom"). A value of '' means not applied.
export type FilterDef =
  | (BaseFilter & { kind: 'search' })
  | (BaseFilter & { kind: 'select'; options: FilterOption[] })
  | (BaseFilter & { kind: 'toggle-group'; options: FilterOption[] })
  | (BaseFilter & { kind: 'tag'; pattern: RegExp; invalid: string })
</script>

<script setup lang="ts">
// One row on desktop, wrapping on phones, always in the order search, select, toggle group, tag. The page's primary
// action goes in the `actions` slot at the end. Every applied filter shows as an input chip below the toolbar, and
// "Clear all" appears when more than one is applied. A tag becomes a value on submit; there is no submit button.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import MdButton from './md/MdButton.vue'
import MdChip from './md/MdChip.vue'
import MdSelect from './md/MdSelect.vue'
import MdTextField from './md/MdTextField.vue'

const props = defineProps<{ filters: FilterDef[]; modelValue: Record<string, string> }>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, string>] }>()

const { t } = useI18n()

const ORDER: FilterDef['kind'][] = ['search', 'select', 'toggle-group', 'tag']
const ordered = computed(() => [...props.filters].sort((a, b) => ORDER.indexOf(a.kind) - ORDER.indexOf(b.kind)))

// What the tag field shows before it is submitted, and the error of a tag that cannot exist.
const drafts = ref<Record<string, string>>({})
const tagErrors = ref<Record<string, string>>({})

const valueOf = (id: string): string => props.modelValue[id] ?? ''

function set(id: string, value: string): void {
  emit('update:modelValue', { ...props.modelValue, [id]: value })
}

// A selected toggle pressed again is cleared: "All" and "no filter" are the same choice.
function toggle(filter: FilterDef, option: FilterOption): void {
  set(filter.id, valueOf(filter.id) === option.value ? '' : option.value)
}

function submitTag(filter: Extract<FilterDef, { kind: 'tag' }>): void {
  const value = (drafts.value[filter.id] ?? '').trim().toLowerCase()
  if (value && !filter.pattern.test(value)) {
    tagErrors.value = { ...tagErrors.value, [filter.id]: filter.invalid }
    return
  }
  tagErrors.value = { ...tagErrors.value, [filter.id]: '' }
  set(filter.id, value)
}

function optionLabel(filter: FilterDef, value: string): string {
  if (filter.kind !== 'select' && filter.kind !== 'toggle-group') return value
  return filter.options.find((option) => option.value === value)?.label ?? value
}

// Each applied filter as a chip. A toggle group shows its option ("Assigned to me"); the others show "label: value".
const applied = computed(() =>
  ordered.value.flatMap((filter) => {
    const value = valueOf(filter.id)
    if (!value) return []
    const shown = optionLabel(filter, value)
    const text = filter.kind === 'toggle-group' ? shown : `${filter.label}: ${shown}`
    return [{ id: filter.id, text }]
  }),
)

function clearAll(): void {
  const cleared = Object.fromEntries(props.filters.map((filter) => [filter.id, '']))
  emit('update:modelValue', { ...props.modelValue, ...cleared })
}

const testidOf = (filter: FilterDef, option: FilterOption) => option.testid ?? `${filter.id}-${option.value || 'all'}`
</script>

<template>
  <div class="flex flex-col gap-3">
    <div data-testid="filter-toolbar" class="flex flex-wrap items-center gap-3">
      <div
        v-for="filter in ordered"
        :key="filter.id"
        :data-filter-kind="filter.kind"
        class="min-w-0"
      >
        <MdTextField
          v-if="filter.kind === 'search'"
          :model-value="valueOf(filter.id)"
          :label="filter.label"
          :data-testid="filter.id"
          type="search"
          dense
          class="md:w-64"
          @update:model-value="set(filter.id, $event)"
        />
        <div v-else-if="filter.kind === 'select'" :data-testid="filter.id" class="md:w-56">
          <MdSelect
            :model-value="valueOf(filter.id)"
            :label="filter.label"
            :options="filter.options"
            dense
            @update:model-value="set(filter.id, $event)"
          />
        </div>
        <div
          v-else-if="filter.kind === 'toggle-group'"
          role="group"
          :aria-label="filter.label"
          :data-testid="filter.id"
          class="flex flex-wrap items-center gap-2"
        >
          <MdChip
            v-for="option in filter.options"
            :key="option.value"
            :data-testid="testidOf(filter, option)"
            :label="option.label"
            :selected="valueOf(filter.id) === option.value"
            @click="toggle(filter, option)"
          />
        </div>
        <form
          v-else
          :data-testid="`${filter.id}-filter`"
          class="flex items-start gap-2"
          @submit.prevent="submitTag(filter)"
        >
          <MdTextField
            v-model="drafts[filter.id]"
            :label="filter.label"
            :error="tagErrors[filter.id]"
            dense
            class="md:w-64"
          />
        </form>
      </div>
      <div v-if="$slots.actions" class="ml-auto flex flex-wrap gap-2">
        <slot name="actions" />
      </div>
    </div>

    <div
      v-if="applied.length > 0"
      data-testid="applied-filters"
      role="group"
      :aria-label="t('filters.applied')"
      class="flex flex-wrap items-center gap-2"
    >
      <MdChip v-for="chip in applied" :key="chip.id" variant="input" :label="chip.text" @remove="set(chip.id, '')" />
      <MdButton v-if="applied.length > 1" variant="text" data-testid="clear-all" @click="clearAll">
        {{ t('filters.clearAll') }}
      </MdButton>
    </div>
  </div>
</template>
