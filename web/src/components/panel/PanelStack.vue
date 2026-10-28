<script setup lang="ts">
// Renders the open side sheets, parent first, each from the registry by its kind. A registry entry is a sheet
// component that wraps itself in SidePanel. Only the top sheet reports close; Escape outside the sheets closes
// the top sheet (Escape inside a sheet is handled by that sheet and never reaches this listener).
import { onBeforeUnmount, onMounted, type Component } from 'vue'
import type { PanelToken } from './panelState'
import type { WindowClass } from './useWindowClass'

const props = withDefaults(
  defineProps<{ tokens: PanelToken[]; registry: Record<string, Component>; sizeClass?: WindowClass }>(),
  { sizeClass: 'expanded' },
)
const emit = defineEmits<{ 'close-top': []; 'request-close': [] }>()

function onWindowKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.tokens.length > 0) emit('close-top')
}

onMounted(() => window.addEventListener('keydown', onWindowKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onWindowKeydown))

function isTop(index: number): boolean {
  return index === props.tokens.length - 1
}
</script>

<template>
  <div class="flex h-full">
    <template v-for="(token, index) in props.tokens" :key="`${index}-${token.kind}:${token.target}`">
      <component
        :is="props.registry[token.kind]"
        v-if="props.registry[token.kind]"
        :target="token.target"
        :size-class="props.sizeClass"
        :covered="!isTop(index)"
        @close="isTop(index) && emit('close-top')"
        @back="isTop(index) && emit('close-top')"
        @request-close="isTop(index) && emit('request-close')"
        @reveal="emit('close-top')"
      />
    </template>
  </div>
</template>
