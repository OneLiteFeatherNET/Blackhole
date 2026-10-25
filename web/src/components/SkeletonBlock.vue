<script setup lang="ts">
// A placeholder block for a detail page on its first load (design section 2, "Loading"). Decorative, no text.
const props = withDefaults(defineProps<{ height?: string }>(), { height: '4rem' })
</script>

<template>
  <div aria-hidden="true" class="w-full rounded-md-md bg-surface-container-highest" :style="{ height: props.height }" />
</template>
