<script lang="ts">
export interface DataColumn<T> {
  key: keyof T & string
  label: string
  /** 1 is always shown; 3 is hidden below 840 px, where each row becomes a card with the label next to the value. */
  priority?: 1 | 2 | 3
  sortable?: boolean
  align?: 'start' | 'end'
}

export interface SortState {
  key: string
  direction: 'ascending' | 'descending'
}
</script>

<script setup lang="ts" generic="T extends Record<string, unknown>">
// The table of the panel (design section 2, "Table component"). A caption is required (visually hidden unless
// `captionVisible`). Loading the first rows shows skeleton rows; a failure shows a ProblemBanner in place of the rows;
// no rows shows the `empty` slot. Cells hold one primary line; details go to the detail page.
import ProblemBanner from './ProblemBanner.vue'
import SkeletonRows from './SkeletonRows.vue'

const props = withDefaults(
  defineProps<{
    caption: string
    captionVisible?: boolean
    columns: DataColumn<T>[]
    rows: T[]
    sort?: SortState | null
    loading?: boolean
    error?: string | null
  }>(),
  { captionVisible: false, sort: null, loading: false, error: null },
)
const emit = defineEmits<{ sort: [key: string] }>()

defineSlots<{
  cell?(props: { row: T; column: DataColumn<T> }): unknown
  empty?(): unknown
}>()

const priorityOf = (column: DataColumn<T>) => column.priority ?? 1

function ariaSort(column: DataColumn<T>): 'none' | 'ascending' | 'descending' | undefined {
  if (!column.sortable) return undefined
  return props.sort?.key === column.key ? props.sort.direction : 'none'
}

function cellClasses(column: DataColumn<T>): string[] {
  return [priorityOf(column) === 3 ? 'max-[840px]:hidden' : '', column.align === 'end' ? 'text-right' : '']
}
</script>

<template>
  <ProblemBanner v-if="props.error" :message="props.error" />

  <div
    v-else-if="props.rows.length === 0 && props.loading"
    aria-busy="true"
    class="overflow-hidden rounded-md-md border border-outline-variant"
  >
    <SkeletonRows :rows="3" :columns="props.columns.length" />
  </div>

  <div v-else-if="props.rows.length === 0">
    <slot name="empty" />
  </div>

  <div
    v-else
    :aria-busy="props.loading ? 'true' : undefined"
    class="overflow-hidden rounded-md-md border border-outline-variant"
  >
    <table class="w-full border-collapse text-left text-body-medium text-on-surface">
      <caption :class="props.captionVisible ? 'p-3 text-left text-title-small' : 'sr-only'">{{ props.caption }}</caption>
      <thead class="bg-surface-container-high text-label-medium text-on-surface-variant max-[840px]:sr-only">
        <tr>
          <th
            v-for="column in props.columns"
            :key="column.key"
            scope="col"
            :aria-sort="ariaSort(column)"
            :class="cellClasses(column)"
            class="px-4 py-3 font-medium"
          >
            <button
              v-if="column.sortable"
              type="button"
              class="md-state inline-flex items-center gap-1"
              @click="emit('sort', column.key)"
            >
              {{ column.label }}
            </button>
            <template v-else>{{ column.label }}</template>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(row, index) in props.rows"
          :key="index"
          class="border-t border-outline-variant hover:bg-on-surface/(--md-sys-state-hover-opacity) max-[840px]:flex max-[840px]:flex-col"
        >
          <td
            v-for="column in props.columns"
            :key="column.key"
            :data-label="column.label"
            :data-priority="priorityOf(column)"
            :class="cellClasses(column)"
            class="px-4 py-3 align-top max-[840px]:flex max-[840px]:items-center max-[840px]:justify-between max-[840px]:gap-4 max-[840px]:before:text-label-medium max-[840px]:before:text-on-surface-variant max-[840px]:before:content-[attr(data-label)]"
          >
            <slot name="cell" :row="row" :column="column">{{ row[column.key] }}</slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
