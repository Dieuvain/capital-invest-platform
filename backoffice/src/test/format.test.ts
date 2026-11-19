import { describe, expect, it } from 'vitest'
import { ageOn, fromLocalInput, initials, toLocalInput, waitingFor } from '../format'

describe('formats', () => {
  it('âge en années révolues', () => {
    expect(ageOn('2008-11-20', new Date(2026, 10, 19))).toBe(17)
    expect(ageOn('2008-11-19', new Date(2026, 10, 19))).toBe(18)
  })
  it("durée d'attente lisible", () => {
    const now = new Date('2026-11-19T12:00:00Z')
    expect(waitingFor('2026-11-19T11:35:00Z', now)).toBe('25 min')
    expect(waitingFor('2026-11-18T12:00:00Z', now)).toBe('24 h')
    expect(waitingFor('2026-11-15T12:00:00Z', now)).toBe('4 jours')
  })
  it('aller-retour des dates des champs', () => {
    const iso = fromLocalInput('2026-12-24T18:30')!
    expect(toLocalInput(iso)).toBe('2026-12-24T18:30')
    expect(fromLocalInput('')).toBeNull()
  })
  it('initiales', () => expect(initials('Amani  Kabeya Mwamba')).toBe('AK'))
})
