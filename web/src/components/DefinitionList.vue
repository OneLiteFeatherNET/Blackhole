<script setup lang="ts">
// Label and value pairs (design section 2, "Type ramp": the label is `label-medium`). It replaces the two-column grids
// of the facts blocks, which used arbitrary grid widths. The caller leaves out an item it does not show.
defineProps<{ items: { term: string; value: string; testid?: string }[] }>()
</script>

<template>
  <dl class="flex flex-wrap gap-x-6 gap-y-3">
    <div v-for="(item, index) in items" :key="`${index}-${item.term}`" class="flex min-w-32 flex-col gap-1">
      <dt class="text-label-medium text-on-surface-variant">{{ item.term }}</dt>
      <dd :data-testid="item.testid" class="text-body-medium text-on-surface">{{ item.value }}</dd>
    </div>
  </dl>
</template>
