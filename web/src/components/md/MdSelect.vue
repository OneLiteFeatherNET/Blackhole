<script setup lang="ts">
// MD3 exposed dropdown menu. ARIA "select-only combobox": the field is a button with
// role="combobox", DOM focus stays on it and the highlighted option is announced through
// aria-activedescendant. The menu is teleported to <body> and positioned with fixed coordinates so
// no dialog or scroll container can clip it.
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

export interface SelectOption {
  value: string
  label: string
  /** Second line, e.g. why the option is not available. */
  description?: string
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    label: string
    options: SelectOption[]
    supportingText?: string
    error?: string
    disabled?: boolean
  }>(),
  { disabled: false },
)
const model = defineModel<string>({ default: '' })

const uid = useId()
const labelId = `${uid}-label`
const valueId = `${uid}-value`
const listId = `${uid}-list`
const messageId = `${uid}-message`
const optionId = (index: number) => `${uid}-option-${index}`

const anchor = ref<HTMLElement | null>(null)
const list = ref<HTMLElement | null>(null)
const open = ref(false)
const active = ref(-1)
const placement = ref<{ left: number; width: number; top?: number; bottom?: number; maxHeight: number }>({
  left: 0,
  width: 0,
  maxHeight: 320,
})

const selectedIndex = computed(() => props.options.findIndex((option) => option.value === model.value))
const selectedLabel = computed(() => props.options[selectedIndex.value]?.label ?? '')
const message = computed(() => props.error || props.supportingText)
const enabled = (index: number) => props.options[index] !== undefined && !props.options[index]!.disabled

function firstEnabled(from: number, step: 1 | -1): number {
  for (let i = from; i >= 0 && i < props.options.length; i += step) if (enabled(i)) return i
  return -1
}

function place(): void {
  if (!anchor.value) return
  const rect = anchor.value.getBoundingClientRect()
  const gap = 4
  const margin = 8
  const below = window.innerHeight - rect.bottom - margin
  const above = rect.top - margin
  // Open upwards only when the menu would be cut off below and there is more room above.
  const wanted = Math.min(320, props.options.length * 48 + 16)
  const up = below < wanted && above > below
  placement.value = {
    left: rect.left,
    width: rect.width,
    maxHeight: Math.max(120, Math.min(320, (up ? above : below) - gap)),
    ...(up ? { bottom: window.innerHeight - rect.top + gap } : { top: rect.bottom + gap }),
  }
}

function scrollActiveIntoView(): void {
  document.getElementById(optionId(active.value))?.scrollIntoView?.({ block: 'nearest' })
}

async function openList(at: number = selectedIndex.value): Promise<void> {
  if (props.disabled || open.value) return
  place()
  active.value = enabled(at) ? at : firstEnabled(0, 1)
  open.value = true
  await nextTick()
  scrollActiveIntoView()
}

function close(): void {
  open.value = false
  active.value = -1
  typed = ''
}

function choose(index: number): void {
  if (!enabled(index)) return
  model.value = props.options[index]!.value
  close()
  anchor.value?.focus()
}

function move(to: number): void {
  if (to < 0) return
  active.value = to
  void nextTick(scrollActiveIntoView)
}

let typed = ''
let typedTimer: ReturnType<typeof setTimeout> | undefined

/** Jump to the next enabled option whose label starts with what was typed. */
function typeahead(character: string): number {
  clearTimeout(typedTimer)
  typed += character.toLowerCase()
  typedTimer = setTimeout(() => (typed = ''), 600)
  const count = props.options.length
  const repeated = typed.split('').every((c) => c === typed[0])
  const needle = repeated ? typed[0]! : typed
  // Typing the same letter again cycles through the options with that letter.
  const start = (active.value >= 0 ? active.value : selectedIndex.value) + (repeated ? 1 : 0)
  for (let offset = 0; offset < count; offset++) {
    const index = (Math.max(start, 0) + offset) % count
    if (enabled(index) && props.options[index]!.label.toLowerCase().startsWith(needle)) return index
  }
  return -1
}

function onKeydown(event: KeyboardEvent): void {
  if (props.disabled) return
  const { key } = event
  if (event.ctrlKey || event.metaKey) return

  if (key === 'Escape') {
    // Closes the menu only; an Escape on a closed field still reaches the dialog.
    if (!open.value) return
    event.preventDefault()
    event.stopPropagation()
    close()
    return
  }
  if (key === 'Tab') {
    if (open.value) {
      if (active.value >= 0 && active.value !== selectedIndex.value) model.value = props.options[active.value]!.value
      close()
    }
    return
  }

  if (key === 'ArrowDown' || key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) {
      void openList()
      return
    }
    const step = key === 'ArrowDown' ? 1 : -1
    // Skips disabled options and stops at the ends.
    move(firstEnabled(active.value + step, step) >= 0 ? firstEnabled(active.value + step, step) : active.value)
    return
  }
  if (key === 'Home' || key === 'End') {
    event.preventDefault()
    const target = key === 'Home' ? firstEnabled(0, 1) : firstEnabled(props.options.length - 1, -1)
    if (!open.value) void openList(target)
    else move(target)
    return
  }
  if (key === 'Enter' || key === ' ') {
    // Space continues a typeahead word instead of choosing.
    if (key === ' ' && typed !== '') {
      event.preventDefault()
      const match = typeahead(' ')
      if (match >= 0) move(match)
      return
    }
    event.preventDefault()
    if (!open.value) void openList()
    else if (active.value >= 0) choose(active.value)
    else close()
    return
  }
  if (key.length === 1 && !event.altKey) {
    event.preventDefault()
    const match = typeahead(key)
    if (match < 0) return
    if (!open.value) void openList(match)
    else move(match)
  }
}

function onPointerOutside(event: Event): void {
  const target = event.target as Node
  if (anchor.value?.contains(target) || list.value?.contains(target)) return
  close()
}

watch(open, (isOpen) => {
  if (isOpen) {
    document.addEventListener('pointerdown', onPointerOutside, true)
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
  } else {
    document.removeEventListener('pointerdown', onPointerOutside, true)
    window.removeEventListener('resize', place)
    window.removeEventListener('scroll', place, true)
  }
})
onBeforeUnmount(() => {
  clearTimeout(typedTimer)
  document.removeEventListener('pointerdown', onPointerOutside, true)
  window.removeEventListener('resize', place)
  window.removeEventListener('scroll', place, true)
})

function toggle(): void {
  if (open.value) close()
  else void openList()
}
</script>

<template>
  <div class="flex flex-col gap-1">
    <div class="relative">
      <button
        :id="valueId"
        ref="anchor"
        type="button"
        role="combobox"
        aria-haspopup="listbox"
        :aria-expanded="open ? 'true' : 'false'"
        :aria-controls="listId"
        :aria-labelledby="`${labelId} ${valueId}`"
        :aria-activedescendant="open && active >= 0 ? optionId(active) : undefined"
        :aria-invalid="props.error ? 'true' : undefined"
        :aria-describedby="message ? messageId : undefined"
        :disabled="props.disabled"
        class="flex h-14 w-full cursor-pointer items-center rounded-md-xs border bg-transparent pl-4 pr-12 text-left text-body-large text-on-surface outline-none disabled:cursor-default disabled:opacity-[0.38] focus-visible:border-2 focus-visible:-outline-offset-2 focus-visible:outline-2 focus-visible:outline-primary"
        :class="[
          props.error ? 'border-error border-2' : open ? 'border-2 border-primary' : 'border-outline hover:border-on-surface',
        ]"
        @click="toggle"
        @keydown="onKeydown"
      >
        <span class="truncate">{{ selectedLabel }}</span>
      </button>
      <span
        :id="labelId"
        class="pointer-events-none absolute top-0 left-3 -translate-y-1/2 bg-(--md-field-bg,var(--color-surface)) px-1 text-body-small"
        :class="props.error ? 'text-error' : open ? 'text-primary' : 'text-on-surface-variant'"
      >
        {{ props.label }}
      </span>
      <svg
        viewBox="0 0 24 24"
        class="pointer-events-none absolute top-1/2 right-3 h-6 w-6 -translate-y-1/2 text-on-surface-variant transition-transform duration-150"
        :class="{ 'rotate-180': open }"
        data-testid="arrow"
        aria-hidden="true"
      >
        <path d="M7 10l5 5 5-5z" fill="currentColor" />
      </svg>
    </div>
    <p
      v-if="message"
      :id="messageId"
      class="px-4 text-body-small"
      :class="props.error ? 'text-error' : 'text-on-surface-variant'"
    >
      {{ message }}
    </p>

    <Teleport to="body">
      <ul
        v-if="open"
        :id="listId"
        ref="list"
        role="listbox"
        tabindex="-1"
        :aria-labelledby="labelId"
        class="fixed z-[60] overflow-y-auto rounded-md-xs bg-surface-container py-2 text-on-surface shadow-md-2"
        :style="{
          left: `${placement.left}px`,
          width: `${placement.width}px`,
          top: placement.top === undefined ? undefined : `${placement.top}px`,
          bottom: placement.bottom === undefined ? undefined : `${placement.bottom}px`,
          maxHeight: `${placement.maxHeight}px`,
        }"
      >
        <li
          v-for="(option, index) in props.options"
          :id="optionId(index)"
          :key="option.value"
          role="option"
          :aria-selected="index === selectedIndex ? 'true' : 'false'"
          :aria-disabled="option.disabled ? 'true' : undefined"
          :data-value="option.value"
          class="relative flex min-h-12 items-center gap-3 px-4 py-2 text-body-large"
          :class="[
            index === selectedIndex ? 'bg-secondary-container text-on-secondary-container' : '',
            option.disabled ? 'cursor-default opacity-[0.38]' : 'cursor-pointer',
            !option.disabled && index === active ? 'bg-on-surface/12' : '',
            !option.disabled && index !== active ? 'hover:bg-on-surface/8' : '',
          ]"
          @pointerdown.prevent
          @mousemove="!option.disabled && (active = index)"
          @click="choose(index)"
        >
          <span class="flex min-w-0 flex-1 flex-col">
            <span class="truncate">{{ option.label }}</span>
            <span v-if="option.description" class="truncate text-body-small text-on-surface-variant">{{ option.description }}</span>
          </span>
          <svg v-if="index === selectedIndex" viewBox="0 0 24 24" class="h-5 w-5 shrink-0" aria-hidden="true">
            <path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z" fill="currentColor" />
          </svg>
        </li>
      </ul>
    </Teleport>
  </div>
</template>
