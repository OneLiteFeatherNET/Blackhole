<script setup lang="ts" generic="T extends Record<string, unknown>">
defineProps<{
  label: string
  columns: { key: keyof T & string; label: string }[]
  rows: T[]
}>()
</script>

<template>
  <div class="overflow-x-auto rounded-md-md border border-outline-variant">
    <table :aria-label="label" class="w-full border-collapse text-left text-body-medium text-on-surface">
      <thead class="bg-surface-container-high text-label-large text-on-surface-variant">
        <tr>
          <th v-for="column in columns" :key="column.key" scope="col" class="px-4 py-3 font-medium">
            {{ column.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="index" class="md-enter md-colors border-t border-outline-variant hover:bg-on-surface/[0.08]" :style="{ '--md-i': index }">
          <td v-for="column in columns" :key="column.key" class="px-4 py-3">
            <slot name="cell" :row="row" :column="column">{{ row[column.key] }}</slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
