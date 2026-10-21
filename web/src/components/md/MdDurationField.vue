<script setup lang="ts">
// A duration the way people say it ("30m", "1h30m", "3d", "1w 2d"; raw ISO-8601 works too). The
// model is what the backend takes: an ISO-8601 duration such as "P21D" once the text is valid, and
// the text as typed while it is not, so the form can tell (parseDuration) and the backend refuses it.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { describeDuration, formatDuration, parseDuration } from '../../duration'
import MdChip from './MdChip.vue'
import MdTextField from './MdTextField.vue'

const props = withDefaults(
  defineProps<{
    label: string
    required?: boolean
    disabled?: boolean
    /** An error from outside (e.g. the backend); shown instead of the input's own. */
    error?: string
    /** Shown while the field is empty. */
    supportingText?: string
    /** Quick picks in the short form, e.g. ['5m', '1h', '1d']. */
    chips?: string[]
  }>(),
  { required: false, disabled: false, chips: () => [] },
)
const model = defineModel<string>({ default: '' })

const { t, locale } = useI18n()

const text = ref(formatDuration(model.value))
const touched = ref(false)
let lastEmitted = model.value

// The parent replaced the value (another template, a reset): show it in the short form.
watch(model, (value) => {
  if (value === lastEmitted) return
  lastEmitted = value
  text.value = formatDuration(value)
  touched.value = false
})

function emitValue(value: string): void {
  lastEmitted = value
  model.value = value
}

watch(text, (typed) => {
  const parsed = parseDuration(typed)
  emitValue(parsed.ok ? parsed.iso : typed.trim())
})

const parsed = computed(() => parseDuration(text.value))

const ownError = computed(() => {
  if (parsed.value.ok) return ''
  if (parsed.value.reason === 'empty') return props.required && touched.value ? t('duration.errors.empty') : ''
  // Unfinished input ("3") is not an error until the field has been left.
  return touched.value ? t(`duration.errors.${parsed.value.reason}`) : ''
})

const preview = computed(() =>
  parsed.value.ok ? t('duration.preview', { text: describeDuration(parsed.value.seconds, locale.value) }) : '',
)
const supporting = computed(() => preview.value || props.supportingText || t('duration.hint'))

function onFocusOut(): void {
  touched.value = true
  // Tidy what was typed into the short form: "90m" becomes "1h 30m".
  if (parsed.value.ok) {
    const tidy = formatDuration(parsed.value.iso)
    if (tidy !== text.value) text.value = tidy
  }
}

const chipIso = (chip: string) => {
  const result = parseDuration(chip)
  return result.ok ? result.iso : chip
}
const isChosen = (chip: string) => parsed.value.ok && parsed.value.iso === chipIso(chip)

function pick(chip: string): void {
  text.value = formatDuration(chipIso(chip))
  touched.value = true
}
</script>

<template>
  <div class="flex flex-col gap-2" @focusout="onFocusOut">
    <MdTextField
      v-model="text"
      :label="props.label"
      :required="props.required"
      :disabled="props.disabled"
      :error="props.error || ownError"
      :supporting-text="supporting"
    />
    <div v-if="props.chips.length" role="group" :aria-label="t('duration.quickPicks')" class="flex flex-wrap gap-2 px-1">
      <MdChip
        v-for="chip in props.chips"
        :key="chip"
        :label="chip"
        :selected="isChosen(chip)"
        :disabled="props.disabled"
        :data-testid="`chip-${chip}`"
        @click="pick(chip)"
      />
    </div>
  </div>
</template>
