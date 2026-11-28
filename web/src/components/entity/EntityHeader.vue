<script setup lang="ts">
// The header of every entity page (`restructure-staff-panel`, decision 4): the breadcrumb, the type, the short id,
// the state in words, the owner and the assignee, ONE primary action, the other actions in an overflow menu and
// copy link. A disabled action shows its reason as text under the header and is linked to the button with
// aria-describedby. The header decides nothing: the page owns each action (see entityHeader.ts for routes).
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRouter } from 'vue-router'
import Breadcrumbs, { type Crumb } from '../Breadcrumbs.vue'
import CopyLink from '../CopyLink.vue'
import MdButton from '../md/MdButton.vue'
import RowMenu, { type RowMenuItem } from '../RowMenu.vue'
import StatusPill from '../StatusPill.vue'
import type { EntityAction, EntityAssignee, EntityOwner, EntityStateView } from './entityHeader'

const props = withDefaults(
  defineProps<{
    typeLabel: string
    heading: string
    shortId?: string
    subtitle?: string
    states: EntityStateView[]
    owner?: EntityOwner
    assignee?: EntityAssignee
    primary?: EntityAction | null
    overflow?: EntityAction[]
    notice?: string
    crumbs: Crumb[]
  }>(),
  { shortId: undefined, subtitle: undefined, owner: undefined, assignee: undefined, primary: null, notice: undefined },
)
const emit = defineEmits<{ action: [key: string] }>()

const { t } = useI18n()
const router = useRouter()
const reasonId = useId()

const reason = computed(() => (props.primary?.disabled ? props.primary.reason : undefined))
const menuItems = computed<RowMenuItem[]>(() => (props.overflow ?? []).map((item) => ({ key: item.key, label: item.label, destructive: item.destructive })))

async function run(action: EntityAction): Promise<void> {
  if (action.disabled) return
  if (action.to !== undefined) {
    await router.push(action.to)
    return
  }
  emit('action', action.key)
}

function chooseFromMenu(key: string): void {
  const action = (props.overflow ?? []).find((item) => item.key === key)
  if (action) void run(action)
}
</script>

<template>
  <section data-testid="entity-header" :aria-label="typeLabel" class="flex flex-col gap-3 rounded-md-lg bg-surface-container p-4">
    <Breadcrumbs :items="crumbs" />

    <div class="flex flex-wrap items-start gap-3">
      <div class="flex min-w-0 flex-1 flex-col gap-2">
        <p class="flex flex-wrap items-baseline gap-2 text-label-large text-on-surface-variant">
          <span data-testid="entity-type">{{ typeLabel }}</span>
          <span v-if="shortId" data-testid="entity-short-id" class="text-on-surface">{{ shortId }}</span>
        </p>
        <h1 class="text-headline-small text-on-surface">{{ heading }}</h1>
        <p v-if="subtitle" data-testid="page-description" class="text-body-medium text-on-surface-variant">{{ subtitle }}</p>

        <div class="flex flex-wrap items-center gap-2">
          <StatusPill v-for="state in states" :key="state.label + state.testid" :label="state.label" :tone="state.tone" :data-testid="state.testid" />
          <span v-if="owner" class="text-body-medium text-on-surface-variant">
            {{ t('entity.owner') }}:
            <RouterLink v-if="owner.to" :to="owner.to" :data-testid="owner.testid ?? 'entity-owner'" class="text-primary underline underline-offset-2 focus-ring">{{ owner.label }}</RouterLink>
            <span v-else :data-testid="owner.testid ?? 'entity-owner'" class="text-on-surface">{{ owner.label }}</span>
          </span>
          <span v-if="assignee" data-testid="entity-assignee" class="text-body-medium text-on-surface-variant">
            {{ t('entity.assignee') }}:
            <span :class="assignee.unassigned ? 'italic' : 'text-on-surface'">{{ assignee.label }}</span>
          </span>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <MdButton
          v-if="primary"
          class="min-h-12"
          :data-testid="primary.testid"
          :disabled="primary.disabled"
          :aria-describedby="reason ? reasonId : undefined"
          @click="run(primary)"
        >
          {{ primary.label }}
        </MdButton>
        <!-- The overflow and its items are 48 pixel targets. -->
        <RowMenu v-if="menuItems.length > 0" class="[&_button]:min-h-12 [&_button]:min-w-12" :label="heading" :items="menuItems" @select="chooseFromMenu" />
        <CopyLink />
      </div>
    </div>

    <p v-if="reason" :id="reasonId" data-testid="entity-reason" class="text-body-medium text-on-surface-variant">{{ reason }}</p>
    <p v-if="notice" data-testid="entity-notice" class="text-body-medium text-on-surface-variant">{{ notice }}</p>
  </section>
</template>
