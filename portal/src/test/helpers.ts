import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { Component } from 'vue'
import App from '../App.vue'
import type { AppealStatus, ConversationEntry, ConversationPage, PunishmentFacts, SessionInfo } from '../api/contract'
import type { ApiResult, PortalApi } from '../api/portal'
import { createPortalI18n } from '../i18n'
import { createCsrfHolder, createPortalSession, SESSION_KEY, type PortalSession } from '../session'

export function ok<T>(data: T): ApiResult<T> {
  return { ok: true, data }
}

export function fail<T = never>(code: import('../api/contract').ErrorCode, status = 400, availableFrom?: string): ApiResult<T> {
  return { ok: false, error: { status, code, ...(availableFrom ? { availableFrom } : {}) } }
}

export const POSTED = { messageId: 'm-posted', repeated: false }

export const SESSION: SessionInfo = { csrf: 'csrf-token-123', expiresAt: '2099-01-01T00:00:00Z', scope: 'FULL' }

export function facts(overrides: Partial<PunishmentFacts> & { appealState?: PunishmentFacts['appeal']['state']; availableFrom?: string | null } = {}): PunishmentFacts {
  const { appealState = 'AVAILABLE', availableFrom = null, ...rest } = overrides
  return {
    reference: '7d1f0c52-3c1e-4c43-9a5e-0b7a1f6a9e11',
    type: 'BAN',
    reason: 'Hacked client',
    section: 'Survival',
    createdAt: '2026-10-01T10:00:00Z',
    permanent: false,
    expiresAt: '2026-12-01T10:00:00Z',
    state: 'ACTIVE',
    evidenceCount: 2,
    appeal: { state: appealState, availableFrom },
    ...rest,
  }
}

export function entry(seq: number, overrides: Partial<ConversationEntry> = {}): ConversationEntry {
  return {
    seq,
    occurredAt: '2026-10-02T10:00:00Z',
    kind: 'MESSAGE',
    author: 'STAFF',
    messageId: `m-${seq}`,
    text: `message ${seq}`,
    removed: false,
    ...overrides,
  }
}

export function page(entries: ConversationEntry[]): ConversationPage {
  return { entries, cursor: entries.length > 0 ? Math.max(...entries.map((e) => e.seq)) : null }
}

export const APPEAL_OPEN: AppealStatus = { status: 'ELIGIBLE_PENDING_REVIEW', decision: null, newExpiresAt: null, filedAt: '2026-10-02T09:00:00Z' }

/**
 * A PortalApi where only the given methods exist. Calling any other method fails the test with its
 * name, so a screen can never silently talk to something the test did not expect.
 */
export function fakeApi(methods: Partial<PortalApi> = {}): PortalApi {
  return new Proxy(methods as PortalApi, {
    get(target, name: string) {
      if (name in target) return (target as unknown as Record<string, unknown>)[name]
      return () => {
        throw new Error(`unexpected API call: ${name}`)
      }
    },
  })
}

export interface Mounted {
  wrapper: VueWrapper
  session: PortalSession
}

/** Mounts a component (the whole app by default) with the English texts and a fresh session store. */
export async function mountPortal(api: PortalApi, options: { component?: Component; signedIn?: boolean } = {}): Promise<Mounted> {
  const session = createPortalSession(api, createCsrfHolder())
  const wrapper = mount(options.component ?? App, {
    global: { plugins: [createPortalI18n()], provide: { [SESSION_KEY as symbol]: session } },
    attachTo: document.body,
  })
  if (options.signedIn !== false && !options.component) await session.bootstrap()
  await flushPromises()
  return { wrapper, session }
}

/** An api for a signed-in player; the given methods override the defaults. */
export function signedInApi(methods: Partial<PortalApi> = {}, punishment: PunishmentFacts = facts()): PortalApi {
  return fakeApi({ session: async () => ok(SESSION), punishment: async () => ok(punishment), ...methods })
}
