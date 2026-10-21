import { describe, expect, it } from 'vitest'
import { sectionFieldErrors, templateFieldErrors } from './fieldErrors'

describe('templateFieldErrors', () => {
  it('puts a step message on the duration of that step, zero-based', () => {
    const errors = templateFieldErrors('step 2 needs a duration that is a positive ISO-8601 duration')

    expect(errors.fields).toEqual({ 'steps.1.duration': 'step 2 needs a duration that is a positive ISO-8601 duration' })
    expect(errors.general).toEqual([])
  })

  it('splits several violations and keeps the message of each field', () => {
    const errors = templateFieldErrors(
      'key must be kebab-case (lower-case letters, digits and single hyphens, at most 64 characters); ' +
        'reason must be 1 to 255 characters; step 1 eloCost must not be negative',
    )

    expect(Object.keys(errors.fields).sort()).toEqual(['key', 'reason', 'steps.0.eloCost'])
  })

  it('maps a duplicate key and an unknown section to their fields', () => {
    expect(templateFieldErrors("a template with key 'spam' already exists").fields).toHaveProperty('key')
    expect(templateFieldErrors("there is no section with key 'ghost'").fields).toHaveProperty('section')
  })

  it('maps the step count rules to the step list', () => {
    expect(templateFieldErrors('a template needs at least one step; permanent templates do not exist').fields).toHaveProperty('steps')
  })

  it('keeps sentences without a field as general messages', () => {
    expect(templateFieldErrors('something unexpected happened')).toEqual({
      fields: {},
      general: ['something unexpected happened'],
    })
  })
})

describe('sectionFieldErrors', () => {
  it('maps threshold and server pattern rules to their fields', () => {
    const errors = sectionFieldErrors(
      "permanentThreshold must be lower than baseScore; a SERVER section needs at least one server pattern; " +
        "server pattern '*' matches every server; use a BAN section for network-wide punishments",
    )

    expect(Object.keys(errors.fields).sort()).toEqual(['permanentThreshold', 'servers'])
    expect(errors.general).toEqual([])
  })

  it('maps a taken key to the key field', () => {
    expect(sectionFieldErrors("a section with key 'chat' already exists").fields).toHaveProperty('key')
  })
})
