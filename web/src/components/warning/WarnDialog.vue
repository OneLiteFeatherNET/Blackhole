<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApplyResult, PunishmentKind, Template } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import StatusPill from '../StatusPill.vue'

// A warning is an apply of a template: the template's current step decides whether the player gets a warning, a kick
// or a punishment. The dialog shows which one the server applied.
const props = defineProps<{ open: boolean; owner: string }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const templates = ref<Template[]>([])
const selected = ref('')
const applying = ref(false)
const error = ref('')
const applied = ref<{ kind: PunishmentKind | null | undefined; enforcement: ApplyResult['enforcement']; template: string } | null>(null)

const options = computed(() =>
  templates.value.map((template) => ({
    value: template.key,
    label: template.key,
    description: `${template.reason} · ${template.section}`,
  })),
)

// What the server applied, in words: a warning, a kick, or the enforcement of a punishment.
const chipLabel = computed(() => {
  if (!applied.value) return ''
  if (applied.value.kind === 'WARN') return t('warn.chip.WARN')
  if (applied.value.kind === 'KICK') return t('kick.badge')
  return t(`enforcement.${applied.value.enforcement}`)
})

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    selected.value = ''
    applied.value = null
    error.value = ''
    const result = await api.templates()
    if (result.ok) templates.value = result.data
    else error.value = result.error.message
  },
  { immediate: true },
)

async function confirm(): Promise<void> {
  if (!selected.value || applying.value) return
  applying.value = true
  error.value = ''
  const result = await api.applyTemplate(props.owner, selected.value)
  applying.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  applied.value = { kind: result.data.punishment.kind, enforcement: result.data.enforcement, template: selected.value }
  emit('done', t('warn.done', { template: selected.value, step: chipLabel.value }))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('warn.title')" @close="emit('close')">
    <div class="flex min-h-64 flex-col gap-4">
      <p class="text-body-medium text-on-surface-variant">{{ t('warn.intro') }}</p>
      <MdSelect
        v-model="selected"
        required
        :disabled="applied !== null"
        :placeholder="t('actions.apply.choose')"
        :label="t('actions.apply.template')"
        :options="options"
      />
      <div v-if="applied" class="flex flex-col gap-2">
        <p class="text-body-medium text-on-surface">{{ t('warn.applied') }}</p>
        <StatusPill :label="chipLabel" :tone="applied.kind === 'WARN' ? 'tertiary' : 'neutral'" data-testid="applied-step" />
      </div>
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton v-if="!applied" variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton v-if="!applied" data-testid="confirm" :disabled="!selected || applying" @click="confirm">{{ t('warn.submit') }}</MdButton>
      <MdButton v-else variant="text" data-testid="close" @click="emit('close')">{{ t('warn.close') }}</MdButton>
    </template>
  </MdDialog>
</template>
