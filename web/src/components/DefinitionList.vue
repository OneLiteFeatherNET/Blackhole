<script setup lang="ts">
// Label and value pairs (design section 2, "Type ramp": the label is `label-medium`). It replaces the two-column grids
// of the facts blocks, which used arbitrary grid widths. A value that is more than text (a link, a note) comes from
// the `value` slot, which receives its item. The caller leaves out an item it does not show.
interface DefinitionItem {
  key?: string
  term: string
  value?: string
  testid?: string
}

defineProps<{ items: DefinitionItem[] }>()
defineSlots<{ value?(props: { item: DefinitionItem }): unknown }>()
</script>

<template>
  <dl class="flex flex-wrap gap-x-6 gap-y-3">
    <div v-for="(item, index) in items" :key="`${index}-${item.term}`" class="flex min-w-32 flex-col gap-1">
      <dt class="text-label-medium text-on-surface-variant">{{ item.term }}</dt>
      <dd :data-testid="item.testid" class="text-body-medium text-on-surface">
        <slot name="value" :item="item">{{ item.value }}</slot>
      </dd>
    </div>
  </dl>
</template>
