<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

/** A full image on the same origin. Escape or the button closes it; focus goes back to where it was. */
const props = defineProps<{ src: string | null; name: string }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

const closeButton = ref<HTMLButtonElement | null>(null)
let returnTo: HTMLElement | null = null

watch(
  () => props.src,
  async (src, previous) => {
    if (src && !previous) {
      returnTo = document.activeElement instanceof HTMLElement ? document.activeElement : null
      await nextTick()
      closeButton.value?.focus()
    } else if (!src && previous) {
      returnTo?.focus()
      returnTo = null
    }
  },
)

function close() {
  emit('close')
}
</script>

<template>
  <div
    v-if="src"
    role="dialog"
    aria-modal="true"
    :aria-label="t('chat.lightbox.title')"
    class="fixed inset-0 z-20 flex flex-col items-center justify-center gap-4 bg-black/80 p-4"
    data-testid="lightbox"
    @keydown.esc="close"
  >
    <button
      ref="closeButton"
      type="button"
      class="md-state self-end rounded-md-full bg-surface px-4 py-2 text-label-large text-on-surface"
      @click="close"
    >
      {{ t('chat.lightbox.close') }}
    </button>
    <img :src="src" :alt="name" class="max-h-[80vh] max-w-full object-contain" />
  </div>
</template>
