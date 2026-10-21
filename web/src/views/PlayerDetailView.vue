<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import PlayerDetail from '../components/player/PlayerDetail.vue'

const props = defineProps<{ owner: string }>()

const OWNER_HASH = /^[0-9a-f]{128}$/i

const { t } = useI18n()
const validOwner = computed(() => OWNER_HASH.test(props.owner))
</script>

<template>
  <!-- The detail only mounts (and so only calls the API) for a well-formed owner hash. -->
  <PlayerDetail v-if="validOwner" :key="owner" :owner="owner" />
  <div v-else role="alert" class="flex flex-col items-start gap-3 rounded-md-md bg-error-container p-4 text-on-error-container">
    <p>{{ t('player.invalidOwner') }}</p>
    <RouterLink to="/players" class="text-label-large underline">{{ t('player.backToSearch') }}</RouterLink>
  </div>
</template>
