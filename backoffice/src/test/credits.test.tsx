import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { Api } from '../api'
import { App } from '../App'
import { FakeServer } from './fakeServer'

let server: FakeServer
let api: Api

beforeEach(() => {
  server = new FakeServer()
  api = new Api('', server.fetch)
  window.location.hash = ''
})

const menu = () => within(screen.getByRole('navigation', { name: 'Menu principal' }))

async function signIn(email: string) {
  const user = userEvent.setup()
  render(<App api={api} />)
  await user.type(await screen.findByLabelText('Adresse e-mail'), email)
  await user.type(screen.getByLabelText('Mot de passe'), server.password)
  await user.click(screen.getByRole('button', { name: 'Se connecter' }))
  await screen.findByRole('navigation', { name: 'Menu principal' })
  return user
}

describe('crédits', () => {
  it("l'agent de crédit arrive sur les crédits, voit le score et ses raisons, puis approuve", async () => {
    const user = await signIn('credit@capitalinvest.cd')
    // Son seul menu : il y arrive directement.
    expect(menu().getAllByRole('link').map((l) => l.textContent)).toEqual(['Crédits'])
    const card = within(await screen.findByRole('region', { name: /Amani Kabeya/ }))
    expect(card.getByText('62 sur 100 · plafond 600,00 $')).toBeInTheDocument()
    expect(card.getByText('+ 1 200,00 $ reçus par mois en moyenne sur 3 mois')).toBeInTheDocument()
    expect(card.getByText('2,5 % par mois · TEG 34,49 %')).toBeInTheDocument()

    await user.click(card.getByRole('button', { name: 'Approuver et verser' }))
    await user.type(screen.getByLabelText('Note interne (facultative)'), 'Paie de la mine vérifiée')
    await user.click(within(screen.getByRole('form', { name: 'Approuver' })).getByRole('button', { name: 'Approuver et verser' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Crédit de Amani Kabeya approuvé et versé')
    expect(server.requests.at(-2)).toMatchObject({ method: 'POST', path: '/v1/backoffice/credits/cr-1/approve', body: { note: 'Paie de la mine vérifiée' } })
    await waitFor(() => expect(screen.queryByRole('region', { name: /Amani Kabeya/ })).toBeNull())
  })

  it('un refus demande une explication pour le client', async () => {
    const user = await signIn('credit@capitalinvest.cd')
    const card = within(await screen.findByRole('region', { name: /Amani Kabeya/ }))
    await user.click(card.getByRole('button', { name: 'Refuser' }))
    const send = screen.getByRole('button', { name: 'Envoyer le refus' })
    expect(send).toBeDisabled()
    await user.type(screen.getByLabelText(/Explication au client/), 'Revenus à confirmer à l’agence.')
    await user.click(send)
    expect(await screen.findByRole('status')).toHaveTextContent('Refus envoyé à Amani Kabeya')
    expect(server.credits[0].credit.status).toBe('REJECTED')
  })

  it("un crédit en retard montre l'impayé et l'échéancier, et se rééchelonne", async () => {
    const user = await signIn('credit@capitalinvest.cd')
    await user.click(await screen.findByRole('tab', { name: 'En retard' }))
    const card = within(await screen.findByRole('region', { name: /Joseph Mwamba/ }))
    expect(card.getByText('52,52 $ · 6 jours de retard')).toBeInTheDocument()
    await user.click(card.getByRole('button', { name: /Voir l’échéancier/ }))
    const rows = within(card.getByRole('table', { name: 'Échéancier de Joseph Mwamba' })).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('En retard')).toBeInTheDocument()
    // Seul un administrateur passe un crédit en perte.
    expect(card.queryByRole('button', { name: 'Passer en perte' })).toBeNull()

    await user.click(card.getByRole('button', { name: 'Rééchelonner' }))
    await user.selectOptions(screen.getByLabelText(/^Nouvelle durée/), '9')
    await user.type(screen.getByLabelText('Accord trouvé avec le client'), 'Reprise du travail en décembre')
    await user.click(screen.getByRole('button', { name: 'Envoyer le nouvel échéancier' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Nouvel échéancier envoyé à Joseph Mwamba')
    expect(server.requests.at(-2)).toMatchObject({ path: '/v1/backoffice/credits/cr-2/reschedule', body: { durationMonths: 9, note: 'Reprise du travail en décembre' } })
  })

  it("les réglages : l'agent de crédit les lit, l'administrateur les change", async () => {
    const user = await signIn('credit@capitalinvest.cd')
    await user.click(await screen.findByRole('tab', { name: 'Réglages' }))
    expect(await screen.findByText('Seul un administrateur peut changer ces réglages.')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Seuil de revue/)).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Enregistrer' })).toBeNull()
  })

  it("l'administrateur change le seuil de revue et le taux", async () => {
    const user = await signIn('admin@capitalinvest.cd')
    await user.click(menu().getByRole('link', { name: /Crédits/ }))
    await user.click(await screen.findByRole('tab', { name: 'Réglages' }))
    const threshold = await screen.findByLabelText(/^Seuil de revue/)
    expect(threshold).toHaveValue('300')
    await user.clear(threshold)
    await user.type(threshold, '250')
    await user.clear(screen.getByLabelText(/^Taux d’intérêt/))
    await user.type(screen.getByLabelText(/^Taux d’intérêt/), '2,8')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Réglages du crédit enregistrés')
    expect(server.creditSettings).toMatchObject({ reviewThresholdMinor: 25_000, monthlyRateBp: 280, durationsMonths: [1, 2, 3, 6] })
  })

  it("un agent KYC n'a pas accès aux crédits", async () => {
    await signIn('kyc@capitalinvest.cd')
    expect(menu().queryByRole('link', { name: /Crédits/ })).toBeNull()
  })
})
