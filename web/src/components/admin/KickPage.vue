<script setup lang="ts">
// The mass-kick page (ADMIN only, see AdminView). `/admin/kick/new` opens the mass-kick dialog over this page; `/admin/kick/:id`
// shows one kick. Opening an address sends no kick: only the dialog's own confirmation does.
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import KickDetailPanel from './KickDetailPanel.vue'
import MassKickDialog from './MassKickDialog.vue'

const { t } = useI18n()
const route = useRoute()
const session = useSessionStore()

const kickId = computed(() => (typeof route.params.id === 'string' ? route.params.id : null))
</script>

<template>
  <section v-if="session.administrator" class="flex max-w-2xl flex-col gap-4" data-testid="admin-kick">
    <p class="text-body-large text-on-surface-variant">{{ t('kick.mass.intro') }}</p>

    <KickDetailPanel v-if="kickId !== null" :id="kickId" />
    <div v-else>
      <MdButton variant="tonal" :to="{ name: 'admin-kick-new' }" as="link" data-testid="mass-kick-new">{{ t('kick.mass.newLink') }}</MdButton>
    </div>

    <MassKickDialog v-if="route.name === 'admin-kick-new'" />
  </section>
</template>
