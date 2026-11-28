<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, type RouteLocationRaw } from 'vue-router'

// Shown in place of an item that does not exist (or was removed): the address is kept, but nothing is requested or shown.
const props = defineProps<{ backTo: RouteLocationRaw; backLabel: string }>()
const { t } = useI18n()
const heading = ref<HTMLElement | null>(null)

onMounted(() => heading.value?.focus())
</script>

<template>
  <section data-testid="item-missing" class="flex flex-col gap-3">
    <h2 ref="heading" tabindex="-1" class="text-headline-small outline-none">{{ t('lists.missing') }}</h2>
    <RouterLink :to="props.backTo" data-testid="item-missing-back" class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring">
      {{ props.backLabel }}
    </RouterLink>
  </section>
</template>
