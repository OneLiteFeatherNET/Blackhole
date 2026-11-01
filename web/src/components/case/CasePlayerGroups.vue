<script setup lang="ts">
// The cases grouped by player. The groups come from the server, paged over players, so a player's count covers all of
// their matching cases. Expanding a group asks for that player's cases with the owner as a request parameter; the
// owner is held here only, never in the route or in browser storage.
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import type { CaseGroupItem, CaseListItem, CaseStatus } from '../../api/types'
import EmptyState from '../EmptyState.vue'
import MdButton from '../md/MdButton.vue'
import ResourceState from '../ResourceState.vue'
import StatusPill from '../StatusPill.vue'
import { useResource } from '../../composables/useResource'

const props = withDefaults(defineProps<{ status?: CaseStatus; assignee?: string; tag?: string; filtersApplied?: boolean }>(), {
  filtersApplied: false,
})
const emit = defineEmits<{ 'clear-filters': [] }>()

const CASES_PER_GROUP = 20

const { t } = useI18n()
const api = useApi()
const page = ref(0)

interface Expansion {
  open: boolean
  page: number
  items: CaseListItem[]
  totalPages: number
  loading: boolean
  error: boolean
}
const expansions = reactive<Record<string, Expansion>>({})

const groups = useResource(() =>
  api.caseGroups({ status: props.status, assignee: props.assignee, tag: props.tag, page: page.value }),
)

function groupName(group: CaseGroupItem): string {
  return group.playerName ?? t('case.list.unknownPlayer', { hash: group.ownerShort })
}

function expansionOf(owner: string): Expansion {
  let expansion = expansions[owner]
  if (!expansion) {
    expansion = expansions[owner] = { open: false, page: 0, items: [], totalPages: 0, loading: false, error: false }
  }
  return expansion
}

async function loadCases(owner: string): Promise<void> {
  const expansion = expansionOf(owner)
  expansion.loading = true
  expansion.error = false
  const result = await api.cases({
    owner,
    status: props.status,
    assignee: props.assignee,
    tag: props.tag,
    page: expansion.page,
    size: CASES_PER_GROUP,
  })
  expansion.loading = false
  if (result.ok) {
    expansion.items = result.data.items
    expansion.totalPages = result.data.totalPages
  } else {
    expansion.error = true
  }
}

async function toggle(group: CaseGroupItem): Promise<void> {
  const expansion = expansionOf(group.owner)
  expansion.open = !expansion.open
  if (expansion.open && expansion.items.length === 0 && !expansion.loading) await loadCases(group.owner)
}

async function changeCasePage(group: CaseGroupItem, delta: number): Promise<void> {
  const expansion = expansionOf(group.owner)
  expansion.page += delta
  await loadCases(group.owner)
}

// A filter change goes back to the first group page. Each path loads the groups exactly once: from a page other than
// the first, the page watcher does the load; on the first page the filter watcher does it.
watch(
  () => [props.status, props.assignee, props.tag],
  () => {
    if (page.value !== 0) page.value = 0
    else refreshGroups()
  },
)
watch(page, refreshGroups)

function refreshGroups(): void {
  for (const key of Object.keys(expansions)) delete expansions[key]
  void groups.reload()
}
</script>

<template>
  <div>
    <ResourceState :loading="groups.loading.value" :error="groups.error.value" :has-data="groups.data.value !== null" @retry="groups.reload">
      <template v-if="groups.data.value">
        <ul v-if="groups.data.value.items.length > 0" class="flex flex-col gap-3">
          <li
            v-for="(group, index) in groups.data.value.items"
            :key="group.owner"
            class="rounded-md-md border border-outline-variant"
            data-testid="case-group"
          >
            <button
              type="button"
              class="md-state flex w-full flex-wrap items-center justify-between gap-2 px-4 py-3 text-left"
              :aria-expanded="expansionOf(group.owner).open ? 'true' : 'false'"
              :aria-controls="expansionOf(group.owner).open ? `case-group-cases-${index}` : undefined"
              data-testid="case-group-toggle"
              @click="toggle(group)"
            >
              <span class="text-title-small">{{ groupName(group) }}</span>
              <span class="text-body-medium text-on-surface-variant">
                {{ t('case.list.groupSummary', { open: group.openCount, total: group.totalCount }) }}
              </span>
            </button>

            <div v-if="expansionOf(group.owner).open" :id="`case-group-cases-${index}`" class="border-t border-outline-variant px-4 py-3">
              <p v-if="expansionOf(group.owner).loading" aria-live="polite" class="text-body-medium">…</p>
              <p v-else-if="expansionOf(group.owner).error" role="alert" class="text-body-medium text-error">{{ t('case.list.loadFailed') }}</p>
              <ul v-else class="flex flex-col gap-2">
                <li v-for="item in expansionOf(group.owner).items" :key="item.punishmentIdentifier" class="flex flex-wrap items-center gap-3">
                  <RouterLink
                    :to="{ name: 'case', params: { reference: item.punishmentIdentifier } }"
                    class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
                  >
                    {{ item.shortId }}
                  </RouterLink>
                  <StatusPill :label="t(`case.status.${item.status}`)" tone="primary" />
                  <StatusPill :label="t(`case.state.${item.punishmentState}`)" />
                </li>
              </ul>
              <nav
                v-if="expansionOf(group.owner).totalPages > 1"
                class="mt-3 flex items-center gap-2"
                :aria-label="t('case.list.groupCases', { player: groupName(group) })"
              >
                <MdButton variant="text" :disabled="expansionOf(group.owner).page === 0" @click="changeCasePage(group, -1)">
                  {{ t('evidence.paging.previous') }}
                </MdButton>
                <MdButton
                  variant="text"
                  :disabled="expansionOf(group.owner).page + 1 >= expansionOf(group.owner).totalPages"
                  @click="changeCasePage(group, 1)"
                >
                  {{ t('evidence.paging.next') }}
                </MdButton>
              </nav>
            </div>
          </li>
        </ul>

        <EmptyState
          v-else
          data-testid="case-groups-empty"
          :variant="props.filtersApplied ? 'no-matches' : 'no-items'"
          :title="props.filtersApplied ? t('case.list.empty') : t('case.list.noneYet')"
        >
          <MdButton v-if="props.filtersApplied" variant="text" data-testid="groups-clear-filters" @click="emit('clear-filters')">
            {{ t('filters.clearFilters') }}
          </MdButton>
        </EmptyState>

        <nav v-if="groups.data.value.totalPages > 1" class="mt-4 flex items-center gap-2" :aria-label="t('case.list.paging')">
          <MdButton variant="text" :disabled="page === 0" data-testid="prev" @click="page--">{{ t('evidence.paging.previous') }}</MdButton>
          <span class="text-body-medium" aria-live="polite">{{ t('evidence.paging.position', { page: page + 1, total: groups.data.value.totalPages }) }}</span>
          <MdButton variant="text" :disabled="page + 1 >= groups.data.value.totalPages" data-testid="next" @click="page++">{{ t('evidence.paging.next') }}</MdButton>
        </nav>
      </template>
    </ResourceState>
  </div>
</template>
