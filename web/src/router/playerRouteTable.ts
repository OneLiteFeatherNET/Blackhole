import type { RouteLocationNormalized, RouteRecordRaw } from 'vue-router'
import PunishedRevokeRoute from '../components/punished/PunishedRevokeRoute.vue'
import PlayerAdjustRoute from '../components/player/routes/PlayerAdjustRoute.vue'
import PlayerKickRoute from '../components/player/routes/PlayerKickRoute.vue'
import PlayerPunishRoute from '../components/player/routes/PlayerPunishRoute.vue'
import PlayerWarnRoute from '../components/player/routes/PlayerWarnRoute.vue'
import PlayerDetailView from '../views/PlayerDetailView.vue'
import PlayersView from '../views/PlayersView.vue'
import PunishedDetailView from '../views/PunishedDetailView.vue'
import PunishedPlayersView from '../views/PunishedPlayersView.vue'

/*
 * The player and punishment addresses (`restructure-staff-panel`, decision 7 rows 10 to 14). Every action is a child route
 * with `meta.moderator`: the guard sends anyone else to the no-access page before a request is made. A child route never
 * sends anything on its own; its dialog sends the change after the confirmation. No address holds a name, a raw UUID, an
 * IP address or a Ban ID (the owner hash and the short id are the only player and punishment identifiers in an address).
 */

const ownerOf = (route: RouteLocationNormalized) => ({ owner: String(route.params.owner) })
const queryText = (value: unknown): string => (typeof value === 'string' ? value : '')

export const playerRoutes: RouteRecordRaw[] = [
  { path: '/players', name: 'players', component: PlayersView, meta: { titleKey: 'nav.playerLookup', pageShell: true, workspace: 'moderation' } },
  {
    path: '/players/:owner',
    name: 'player',
    component: PlayerDetailView,
    props: true,
    meta: { titleKey: 'nav.playerLookup', nav: '/players', pageShell: true, workspace: 'moderation' },
    children: [
      { path: 'punish', name: 'player-punish', component: PlayerPunishRoute, props: ownerOf, meta: { moderator: true } },
      { path: 'warn', name: 'player-warn', component: PlayerWarnRoute, props: ownerOf, meta: { moderator: true } },
      {
        path: 'elo/adjust',
        name: 'player-adjust',
        component: PlayerAdjustRoute,
        props: (route) => ({ owner: String(route.params.owner), section: queryText(route.query.section) }),
        meta: { moderator: true },
      },
      { path: 'kick', name: 'player-kick', component: PlayerKickRoute, props: ownerOf, meta: { moderator: true } },
    ],
  },
  { path: '/punished', name: 'punished', component: PunishedPlayersView, meta: { titleKey: 'nav.activePunishments', pageShell: true, workspace: 'moderation' } },
  {
    path: '/punished/:shortId',
    name: 'punished-detail',
    component: PunishedDetailView,
    props: true,
    meta: { titleKey: 'punished.detail.title', nav: '/punished', pageShell: true, workspace: 'moderation' },
    children: [
      {
        path: 'revoke',
        name: 'punished-revoke',
        component: PunishedRevokeRoute,
        props: (route) => ({ shortId: String(route.params.shortId), owner: queryText(route.query.owner) }),
        meta: { moderator: true },
      },
    ],
  },
]
