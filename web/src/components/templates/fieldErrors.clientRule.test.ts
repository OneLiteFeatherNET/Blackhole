import { describe, expect, it } from 'vitest'
import { clientRuleFieldErrors } from './fieldErrors'

describe('clientRuleFieldErrors', () => {
  it('sorts each sentence under the field it starts with', () => {
    const errors = clientRuleFieldErrors(
      "key must be 1 to 64 characters of a-z, 0-9 and '-'; patterns must hold 1 to 32 entries; denyMessage is only allowed for the DENY action; templateKey is required for the TEMPLATE action",
    )

    expect(Object.keys(errors.fields).sort()).toEqual(['denyMessage', 'key', 'patterns', 'templateKey'])
    expect(errors.general).toEqual([])
  })

  it('maps the protocol sentences to the field they name', () => {
    expect(Object.keys(clientRuleFieldErrors('protocolMin must not be above protocolMax').fields)).toEqual(['protocolMin'])
    expect(Object.keys(clientRuleFieldErrors('protocolMax must not be negative').fields)).toEqual(['protocolMax'])
  })

  it('maps scope, section and server sentences', () => {
    const errors = clientRuleFieldErrors("scope SERVERS needs at least one section key or server; sectionKeys: there is no SERVER section 'x'; servers: server pattern 'a b' may only contain letters")

    expect(Object.keys(errors.fields).sort()).toEqual(['scope', 'sectionKeys', 'servers'])
  })

  it('maps the acknowledgement and the duplicate key', () => {
    expect(Object.keys(clientRuleFieldErrors('acknowledgedSpoofable must be true: the client controls this signal and can fake it, so a hit is evidence, not proof').fields)).toEqual(['acknowledgedSpoofable'])
    expect(Object.keys(clientRuleFieldErrors("a rule with key 'x' already exists").fields)).toEqual(['key'])
  })

  it('keeps a sentence that names no field as a general message', () => {
    expect(clientRuleFieldErrors('the backend is busy').general).toEqual(['the backend is busy'])
  })
})
