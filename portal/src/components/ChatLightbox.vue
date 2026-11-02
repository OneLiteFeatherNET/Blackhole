<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * A full image on the same origin, in a native modal dialog: showModal() makes the page behind it inert, Escape
 * closes it (the browser's own cancel), and focus goes back to the element that opened it.
 */
const props = defineProps<{ src: string | null; name: string }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

const dialog = ref<HTMLDialogElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
let returnTo: HTMLElement | null = null

function open() {
  const element = dialog.value
  if (!element || element.open) return
  returnTo = document.activeElement instanceof HTMLElement ? document.activeElement : null
  element.showModal()
  closeButton.value?.focus()
}

/** Every close (Escape, the button, or the page) ends here: focus goes back, and the page learns of it. */
function onClosed() {
  returnTo?.focus()
  returnTo = null
  emit('close')
}

watch(
  () => props.src,
  async (src) => {
    await nextTick()
    if (src) open()
    else if (dialog.value?.open) dialog.value.close()
  },
)

onMounted(() => {
  if (props.src) open()
})
</script>

<template>
  <dialog
    ref="dialog"
    :aria-label="t('chat.lightbox.title')"
    class="m-0 h-dvh max-h-none w-dvw max-w-none flex-col items-center justify-center gap-4 bg-black/80 p-4 open:flex"
    data-testid="lightbox"
    @close="onClosed"
  >
    <button
      ref="closeButton"
      type="button"
      class="md-state self-end rounded-md-full bg-surface px-4 py-2 text-label-large text-on-surface"
      @click="dialog?.close()"
    >
      {{ t('chat.lightbox.close') }}
    </button>
    <img v-if="src" :src="src" :alt="name" class="max-h-[80vh] max-w-full object-contain" />
  </dialog>
</template>
