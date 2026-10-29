<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import { useResource } from '../../composables/useResource'

// The dashboard tile: the level in force and how many area bans apply now. It links to the join guard page.
const { t } = useI18n()
const api = useApi()
const guard = useResource(() => api.joinGuard())
const bans = useResource(() => api.areaBans())

const activeBans = computed(() => (bans.data.value ?? []).filter((ban) => ban.active).length)
</script>

<template>
  <RouterLink
    to="/guard"
    data-testid="guard-tile"
    class="flex flex-col gap-2 rounded-md-lg bg-surface-container-low p-4 text-on-surface outline-none focus-visible:outline-2 focus-visible:outline-primary"
  >
    <span class="text-title-medium">{{ t('guard.tile.title') }}</span>
    <p v-if="guard.error.value || bans.error.value" class="text-body-medium text-on-surface-variant">{{ t('state.error.UNKNOWN') }}</p>
    <template v-else-if="guard.data.value">
      <span class="text-headline-small" data-testid="guard-tile-level">{{ t(`guard.level.${guard.data.value.effectiveLevel}`) }}</span>
      <span v-if="bans.data.value" class="text-body-medium text-on-surface-variant" data-testid="guard-tile-bans">
        {{ t('guard.tile.bans', { n: activeBans }, activeBans) }}
      </span>
    </template>
    <p v-else class="text-body-medium text-on-surface-variant">{{ t('state.loading') }}</p>
  </RouterLink>
</template>
