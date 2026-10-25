<script setup lang="ts">
// A failure shown in place of its content (design section 11). Only the message is shown for now; the mapping from
// Problem Details to copy and the retry action follow with the error display.
defineProps<{ message: string }>()
</script>

<template>
  <div role="alert" class="flex flex-wrap items-center gap-3 rounded-md-md bg-error-container p-4 text-on-error-container">
    <p class="min-w-0 flex-1 text-body-medium break-words">{{ message }}</p>
  </div>
</template>
