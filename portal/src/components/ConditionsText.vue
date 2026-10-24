<script setup lang="ts">
import { computed } from 'vue'
import { parseConditions } from '../conditions'

const props = defineProps<{ text: string }>()
const blocks = computed(() => parseConditions(props.text))
</script>

<template>
  <!-- Every piece is a text node: the text is never interpreted as markup. -->
  <div class="flex flex-col gap-3 text-body-large text-on-surface" data-testid="conditions-text">
    <template v-for="(block, index) in blocks" :key="index">
      <p v-if="block.kind === 'paragraph'" class="plain-text">{{ block.text }}</p>
      <ul v-else class="list-disc pl-6">
        <li v-for="(item, at) in block.items" :key="at" class="plain-text">{{ item }}</li>
      </ul>
    </template>
  </div>
</template>
