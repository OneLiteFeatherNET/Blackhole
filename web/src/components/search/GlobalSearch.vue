<script setup lang="ts">
// Global search of the top bar: a player name opens the player, `#A1B2C3D4` (the id players quote) or a
// full punishment id opens the case, `report:` and a report id opens the report. A short id that matches several punishments lists them to pick from.
// ARIA combobox with a listbox; "/" focuses the field from anywhere outside a text field.
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useApi } from '../../api/inject'
import type { Punishment } from '../../api/types'
import { formatDateTime } from '../../format'
import { useLookupStore } from '../../stores/lookup'
import { parseSearch } from './parseSearch'
import MdTransition from '../../motion/MdTransition.vue'

interface Choice {
  id: string
  label: string
  detail?: string
  run: () => Promise<void> | void
}

const { t, locale } = useI18n()
const api = useApi()
const router = useRouter()
const lookup = useLookupStore()
const uid = useId()
const listId = `${uid}-list`
const optionId = (index: number) => `${uid}-option-${index}`

const input = ref<HTMLInputElement | null>(null)
const text = ref('')
const open = ref(false)
const busy = ref(false)
const active = ref(0)
/** Why nothing was opened (not found, rejected, unreachable). */
const failure = ref('')
/** The punishments an ambiguous short id matched, once looked up. */
const matches = ref<{ shortId: string; items: Punishment[] } | null>(null)
// A slow answer must not act on text the user has since replaced.
let ticket = 0

const intent = computed(() => parseSearch(text.value))

function openCase(reference: string): void {
  void router.push({ name: 'case', params: { reference } })
  finish()
}

function openReport(identifier: string): void {
  void router.push({ name: 'report', params: { id: identifier } })
  finish()
}

const choices = computed<Choice[]>(() => {
  if (matches.value) {
    return matches.value.items.map((punishment) => ({
      id: punishment.identifier,
      label: t('search.case', { id: punishment.shortId }),
      detail: [punishment.section, punishment.createdAt ? formatDateTime(punishment.createdAt, locale.value) : ''].filter(Boolean).join(' · '),
      run: () => openCase(punishment.identifier),
    }))
  }
  const found = intent.value
  if (found.kind === 'player') return [{ id: 'player', label: t('search.player', { name: found.name }), run: () => openPlayer(found.name) }]
  if (found.kind === 'shortId') return [{ id: 'case', label: t('search.case', { id: found.shortId }), run: () => lookUpShortId(found.shortId) }]
  if (found.kind === 'report') return [{ id: 'report', label: t('search.report', { id: found.identifier.slice(0, 8) }), run: () => openReport(found.identifier) }]
  if (found.kind === 'reference') return [{ id: 'case', label: t('search.case', { id: found.reference.slice(0, 8) }), run: () => openCase(found.reference) }]
  return []
})

const hint = computed(() => (matches.value ? t('search.ambiguous', { id: matches.value.shortId, n: matches.value.items.length }) : ''))
const showList = computed(() => open.value && choices.value.length > 0)
const showMessage = computed(() => open.value && (failure.value !== '' || hint.value !== '' || busy.value || choices.value.length === 0))
const message = computed(() => {
  if (busy.value) return t('state.loading')
  if (failure.value) return failure.value
  if (hint.value) return hint.value
  return t('search.hint')
})

/** Clears the field after something was opened. */
function finish(): void {
  ticket++
  text.value = ''
  failure.value = ''
  matches.value = null
  busy.value = false
  open.value = false
  active.value = 0
  input.value?.blur()
}

function onInput(): void {
  ticket++
  failure.value = ''
  matches.value = null
  busy.value = false
  active.value = 0
  open.value = true
}

async function openPlayer(name: string): Promise<void> {
  const mine = ++ticket
  busy.value = true
  failure.value = ''
  const result = await api.resolvePlayer(name)
  if (mine !== ticket) return
  busy.value = false
  if (!result.ok) {
    failure.value = result.error.kind === 'NOT_FOUND' ? t('players.notFound', { name }) : result.error.message
    return
  }
  lookup.remember(result.data.owner, result.data.name)
  void router.push({ name: 'player', params: { owner: result.data.owner } })
  finish()
}

async function lookUpShortId(shortId: string): Promise<void> {
  const mine = ++ticket
  busy.value = true
  failure.value = ''
  const result = await api.punishmentsByShortId(shortId)
  if (mine !== ticket) return
  busy.value = false
  if (!result.ok) {
    failure.value = result.error.message
    return
  }
  const [only, ...others] = result.data
  if (!only) failure.value = t('search.caseNotFound', { id: shortId })
  else if (others.length === 0) openCase(only.identifier)
  else {
    matches.value = { shortId, items: result.data }
    active.value = 0
  }
}

async function choose(index: number): Promise<void> {
  await choices.value[index]?.run()
}

function submit(): void {
  if (choices.value.length === 0) {
    failure.value = text.value.trim() === '' ? '' : t('search.hint')
    return
  }
  void choose(Math.min(active.value, choices.value.length - 1))
}

function onKeydown(event: KeyboardEvent): void {
  const count = choices.value.length
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    open.value = true
    if (count > 0) active.value = (active.value + (event.key === 'ArrowDown' ? 1 : -1) + count) % count
  } else if (event.key === 'Enter') {
    event.preventDefault()
    submit()
  } else if (event.key === 'Escape') {
    // First Escape closes the list, a second one empties the field.
    event.preventDefault()
    if (open.value) open.value = false
    else finish()
  }
}

// "/" jumps to the search unless the user is typing somewhere or uses a modifier.
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey || event.defaultPrevented) return
  const target = event.target as HTMLElement | null
  if (target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return
  event.preventDefault()
  input.value?.focus()
  input.value?.select()
}

onMounted(() => document.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onGlobalKeydown))
</script>

<template>
  <div class="relative w-full max-w-xl" role="search">
    <div class="flex h-12 items-center gap-3 rounded-md-full bg-surface-container-high px-4 text-on-surface-variant focus-within:outline-2 focus-within:outline-primary">
      <svg viewBox="0 0 24 24" class="h-6 w-6 shrink-0" aria-hidden="true">
        <path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" fill="currentColor" />
      </svg>
      <input
        ref="input"
        v-model="text"
        data-testid="global-search"
        type="search"
        role="combobox"
        autocomplete="off"
        spellcheck="false"
        aria-autocomplete="list"
        aria-keyshortcuts="/"
        :aria-label="t('search.label')"
        :placeholder="t('search.placeholder')"
        :aria-expanded="showList"
        :aria-controls="listId"
        :aria-activedescendant="showList ? optionId(active) : undefined"
        :aria-busy="busy"
        class="min-w-0 flex-1 bg-transparent text-body-large text-on-surface outline-none placeholder:text-on-surface-variant"
        @input="onInput"
        @focus="open = true"
        @blur="open = false"
        @keydown="onKeydown"
      />
      <kbd class="hidden rounded-md-xs border border-outline px-1.5 text-label-small text-on-surface-variant sm:inline" aria-hidden="true">/</kbd>
    </div>
    <MdTransition name="menu">
      <div v-if="showList || showMessage" style="--md-menu-origin: top" class="absolute top-full right-0 left-0 z-40 mt-1 overflow-hidden rounded-md-md bg-surface-container text-on-surface shadow-md-2">
        <ul v-show="showList" :id="listId" role="listbox" :aria-label="t('search.results')" class="py-2">
          <li
            v-for="(choice, index) in choices"
            :id="optionId(index)"
            :key="choice.id"
            role="option"
            :aria-selected="index === active"
            class="md-colors flex cursor-pointer flex-col px-4 py-2 text-body-large"
            :class="index === active ? 'bg-secondary-container text-on-secondary-container' : ''"
            @mousedown.prevent
            @mousemove="active = index"
            @click="choose(index)"
          >
            {{ choice.label }}
            <span v-if="choice.detail" class="text-body-small">{{ choice.detail }}</span>
          </li>
        </ul>
        <p
          v-if="showMessage"
          data-testid="search-message"
          :role="failure ? 'alert' : 'status'"
          class="px-4 py-3 text-body-medium"
          :class="failure ? 'bg-error-container text-on-error-container' : 'text-on-surface-variant'"
        >
          {{ message }}
        </p>
      </div>
    </MdTransition>
  </div>
</template>
