import { describe, expect, it } from 'vitest'
import { Api, ApiError } from '../api'

const answer = (status: number, body: unknown) => async () =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('erreurs du serveur', () => {
  it('une saisie refusée donne un message en français, pas le détail technique de Spring', async () => {
    const api = new Api('', answer(400, { code: 'VALIDATION_ERROR', detail: 'title: must not be blank' }))
    const error = await api.login('agent@capital-invest.cd', 'secret').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('VALIDATION_ERROR')
    expect((error as ApiError).message).toBe('Certaines informations ne sont pas valides. Vérifiez la saisie et réessayez.')
  })

  it('les autres refus gardent le détail du serveur', async () => {
    const api = new Api('', answer(422, { code: 'PROMOTION_DATES', detail: 'La fin doit suivre le début' }))
    const error = (await api.login('agent@capital-invest.cd', 'secret').catch((e: unknown) => e)) as ApiError
    expect(error.message).toBe('La fin doit suivre le début')
  })
})
