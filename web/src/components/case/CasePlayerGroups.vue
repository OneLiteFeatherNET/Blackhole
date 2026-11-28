<script setup lang="ts">
// The cases grouped by player, as an accordion table. Each player is a header row that expands in place to that
// player's cases, with the same columns as the flat table; the player column is left out inside a group. The groups
// come from the server, paged over players, so a player's count covers all of their matching cases. Expanding a group
// asks for that player's cases with the owner as a request parameter; the owner is held here only, never in the route
// or in browser storage. Several groups may be open at once.
import { computed, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { CaseGroupItem, CaseListItem, CaseStatus } from '../../api/types'
import type { CaseSelection } from '../../composables/useCaseSelection'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import { DROP_DOWN_ICON } from '../../icons'
import MdTransition from '../../motion/MdTransition.vue'
import type { DataColumn } from '../DataTable.vue'
import EmptyState from '../EmptyState.vue'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import ResourceState from '../ResourceState.vue'
import CaseListCell from './CaseListCell.vue'

const props = withDefaults(
  defineProps<{
    status?: CaseStatus
    assignee?: string
    tag?: string
    /** The group page, held by the page in the address (`page=`); 0 is the first page. */
    page?: number
    filtersApplied?: boolean
    columns: DataColumn<CaseListItem>[]
    selection: CaseSelection
    /** Bumped by the page when cases changed elsewhere (a bulk edit): the groups and the open players reload. */
    revision?: number
  }>(),
  { page: 0, filtersApplied: false, revision: 0 },
)
const emit = defineEmits<{ 'clear-filters': []; 'update:page': [page: number] }>()

const CASES_PER_GROUP = 20
// The checkbox column: the player header and its case rows share it, so their boxes sit on one vertical line.
const LEADING_CELL = 'px-2 py-1 align-middle'

const { t, locale } = useI18n()
const api = useApi()
const page = computed(() => props.page)

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

// The player's column is the group itself; the other columns are the flat table's, in the same order.
const caseColumns = computed(() => props.columns.filter((column) => column.key !== 'playerName'))

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

// The player's checkbox selects the cases loaded for that player. A player that was never opened has none loaded, so
// selecting it loads the first page first; the group stays as it was.
async function selectPlayer(group: CaseGroupItem, select: boolean): Promise<void> {
  const expansion = expansionOf(group.owner)
  if (select && expansion.items.length === 0 && !expansion.loading) await loadCases(group.owner)
  props.selection.toggleMany(expansionOf(group.owner).items, select)
}

function playerSelection(group: CaseGroupItem) {
  return props.selection.summary(expansionOf(group.owner).items)
}

// A filter change comes with the first group page (the parent resets the page in the same address change), so one
// watcher on the filters and the page reads the groups once per change. The selection is ended by the parent.
watch(() => JSON.stringify([props.status, props.assignee, props.tag, props.page]), refreshGroups)

// Cases changed elsewhere: the groups are read again and every open player's cases with them, so nothing shows the old state.
watch(
  () => props.revision,
  () => {
    void groups.reload()
    for (const [owner, expansion] of Object.entries(expansions)) if (expansion.open) void loadCases(owner)
  },
)

function refreshGroups(): void {
  for (const key of Object.keys(expansions)) delete expansions[key]
  props.selection.clear()
  void groups.reload()
}
</script>

<template>
  <div>
    <ResourceState :loading="groups.loading.value" :error="groups.error.value" :has-data="groups.data.value !== null" @retry="groups.reload">
      <template v-if="groups.data.value">
        <div v-if="groups.data.value.items.length > 0" class="overflow-x-auto rounded-md-md border border-outline-variant bg-surface-container-low">
          <table class="w-full border-collapse text-left text-body-medium text-on-surface">
            <caption class="sr-only">{{ t('case.list.title') }}</caption>
            <thead class="bg-surface-container-high text-label-medium text-on-surface-variant">
              <tr>
                <th scope="col" :class="LEADING_CELL"><span class="sr-only">{{ t('case.list.select.column') }}</span></th>
                <th v-for="column in caseColumns" :key="column.key" scope="col" class="px-4 py-3 font-medium">{{ column.label }}</th>
              </tr>
            </thead>

            <template v-for="(group, index) in groups.data.value.items" :key="group.owner">
              <tbody data-testid="case-group" class="border-t border-outline-variant">
                <tr>
                  <td :class="LEADING_CELL">
                    <MdCheckbox
                      data-testid="case-group-select"
                      hide-label
                      :label="t('case.list.select.player', { player: groupName(group) })"
                      :model-value="playerSelection(group).checked"
                      :indeterminate="playerSelection(group).indeterminate"
                      :disabled="!playerSelection(group).checked && props.selection.atCap.value"
                      @update:model-value="(value: boolean) => selectPlayer(group, value)"
                    />
                  </td>
                  <td :colspan="Math.max(caseColumns.length - 1, 1)" class="px-4 py-3 align-middle">
                    <button
                      type="button"
                      class="md-state inline-flex items-center gap-2 text-left"
                      :aria-expanded="expansionOf(group.owner).open ? 'true' : 'false'"
                      :aria-controls="expansionOf(group.owner).open ? `case-group-cases-${index}` : undefined"
                      data-testid="case-group-toggle"
                      @click="toggle(group)"
                    >
                      <svg
                        viewBox="0 0 24 24"
                        aria-hidden="true"
                        class="h-5 w-5 shrink-0 fill-current motion-safe:transition-transform motion-safe:duration-(--md-sys-motion-duration-short3) motion-safe:ease-(--md-sys-motion-easing-standard)"
                        :class="expansionOf(group.owner).open ? 'rotate-180' : ''"
                      >
                        <path :d="DROP_DOWN_ICON" />
                      </svg>
                      <span class="text-title-small text-on-surface">{{ groupName(group) }}</span>
                      <span class="text-body-medium text-on-surface-variant">
                        {{ t('case.list.groupSummary', { open: group.openCount, total: group.totalCount }) }}
                      </span>
                    </button>
                    <span
                      v-if="expansionOf(group.owner).items.length > 0 && expansionOf(group.owner).items.length < group.totalCount"
                      data-testid="case-group-loaded"
                      class="ml-8 text-body-small text-on-surface-variant"
                    >
                      {{ t('case.list.select.loaded', { loaded: expansionOf(group.owner).items.length, total: group.totalCount }) }}
                    </span>
                  </td>
                  <td class="px-4 py-3 align-middle">{{ formatDateTime(group.lastActivityAt, locale) }}</td>
                </tr>
              </tbody>

              <MdTransition name="fade">
                <tbody v-if="expansionOf(group.owner).open" :id="`case-group-cases-${index}`" data-testid="case-group-rows">
                  <tr v-if="expansionOf(group.owner).loading">
                    <td :colspan="caseColumns.length + 1" class="px-4 py-3 text-body-medium" aria-live="polite">…</td>
                  </tr>
                  <tr v-else-if="expansionOf(group.owner).error">
                    <td :colspan="caseColumns.length + 1" class="px-4 py-3 text-body-medium text-error" role="alert">{{ t('case.list.loadFailed') }}</td>
                  </tr>
                  <template v-else>
                    <tr v-for="item in expansionOf(group.owner).items" :key="item.punishmentIdentifier" class="border-t border-outline-variant hover:bg-on-surface/(--md-sys-state-hover-opacity)">
                      <td :class="LEADING_CELL">
                        <MdCheckbox
                          data-testid="case-select"
                          hide-label
                          :label="t('case.list.select.case', { id: item.shortId })"
                          :model-value="props.selection.isSelected(item.punishmentIdentifier)"
                          :disabled="!props.selection.canSelect(item.punishmentIdentifier)"
                          @update:model-value="(value: boolean) => props.selection.toggle(item, value)"
                        />
                      </td>
                      <td v-for="column in caseColumns" :key="column.key" class="px-4 py-3 align-top">
                        <CaseListCell :row="item" :column="column" />
                      </td>
                    </tr>
                    <tr v-if="expansionOf(group.owner).totalPages > 1">
                      <td :colspan="caseColumns.length + 1" class="px-4 py-3">
                        <nav class="flex items-center gap-2" :aria-label="t('case.list.groupCases', { player: groupName(group) })">
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
                      </td>
                    </tr>
                  </template>
                </tbody>
              </MdTransition>
            </template>
          </table>
        </div>

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
          <MdButton variant="text" :disabled="page === 0" data-testid="prev" @click="emit('update:page', page - 1)">{{ t('evidence.paging.previous') }}</MdButton>
          <span class="text-body-medium" aria-live="polite">{{ t('evidence.paging.position', { page: page + 1, total: groups.data.value.totalPages }) }}</span>
          <MdButton variant="text" :disabled="page + 1 >= groups.data.value.totalPages" data-testid="next" @click="emit('update:page', page + 1)">{{ t('evidence.paging.next') }}</MdButton>
        </nav>
      </template>
    </ResourceState>
  </div>
</template>
