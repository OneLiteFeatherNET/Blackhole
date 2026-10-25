<script setup lang="ts">
// The page template of the panel: breadcrumbs, the title, an optional description, the primary action, the
// filter toolbar, the content and an optional aside. The title is the page's only level-one heading. The
// aside sits to the right of the content from 1024 px; below that it follows the content.
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="mx-auto flex w-full max-w-6xl flex-col gap-4">
    <header class="flex flex-col gap-2">
      <slot name="breadcrumbs" />
      <div class="flex flex-wrap items-start gap-3">
        <div class="min-w-0 flex-1">
          <h1 class="text-headline-small text-on-surface">{{ title }}</h1>
          <p v-if="description" data-testid="page-description" class="mt-1 text-body-large text-on-surface-variant">
            {{ description }}
          </p>
        </div>
        <div v-if="$slots.actions" class="flex flex-wrap gap-2">
          <slot name="actions" />
        </div>
      </div>
    </header>
    <div v-if="$slots.toolbar" data-testid="page-toolbar">
      <slot name="toolbar" />
    </div>
    <div class="grid gap-6" :class="{ 'lg:grid-cols-[minmax(0,1fr)_320px]': $slots.aside }">
      <div class="flex min-w-0 flex-col gap-6">
        <slot />
      </div>
      <aside v-if="$slots.aside" class="min-w-0">
        <slot name="aside" />
      </aside>
    </div>
  </div>
</template>
