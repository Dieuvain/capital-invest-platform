import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Api } from '../api'
import { App } from '../App'
import { FakeServer } from './fakeServer'

let server: FakeServer
let api: Api

beforeEach(() => {
  server = new FakeServer()
  api = new Api('', server.fetch)
  URL.createObjectURL = vi.fn(() => 'blob:photo')
  URL.revokeObjectURL = vi.fn()
})

async function signIn(email: string) {
  const user = userEvent.setup()
  render(<App api={api} />)
  await user.type(await screen.findByLabelText('Adresse e-mail'), email)
  await user.type(screen.getByLabelText('Mot de passe'), server.password)
  await user.click(screen.getByRole('button', { name: 'Se connecter' }))
  return user
}

const menu = () => within(screen.getByRole('navigation', { name: 'Menu principal' }))

describe('connexion', () => {
  it('affiche une erreur claire si le mot de passe est faux', async () => {
    const user = userEvent.setup()
    render(<App api={api} />)
    await user.type(await screen.findByLabelText('Adresse e-mail'), 'kyc@capitalinvest.cd')
    await user.type(screen.getByLabelText('Mot de passe'), 'pas-le-bon-mot')
    await user.click(screen.getByRole('button', { name: 'Se connecter' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Adresse ou mot de passe incorrect')
    expect(screen.getByLabelText('Mot de passe')).toHaveValue('')
  })

  it.each([
    ['kyc@capitalinvest.cd', ['Demandes KYC 2']],
    ['marketing@capitalinvest.cd', ['Promotions']],
    ['conformite@capitalinvest.cd', ['Demandes KYC 2', "Journal d'audit"]],
    ['admin@capitalinvest.cd', ['Demandes KYC 2', 'Promotions', 'Agents', "Journal d'audit"]],
  ])('%s ne voit que les menus de son rôle', async (email, expected) => {
    await signIn(email)
    await waitFor(() => expect(menu().getAllByRole('link').map((l) => l.querySelector('.nav-label')?.textContent)).toEqual(expected))
  })

  it('retrouve la session après un rechargement de la page', async () => {
    sessionStorage.setItem('capital-invest-agent', server.login('KYC_AGENT'))
    render(<App api={api} />)
    expect(await screen.findByText('Patrick Ilunga')).toBeInTheDocument()
  })

  it('une session expirée ramène à la connexion avec une explication', async () => {
    const user = await signIn('kyc@capitalinvest.cd')
    await screen.findByText('Amani Kabeya')
    server.sessions.clear()
    await user.click(screen.getByText('Amani Kabeya'))
    expect(await screen.findByText(/session a expiré/)).toBeInTheDocument()
    expect(sessionStorage.getItem('capital-invest-agent')).toBeNull()
  })
})

describe('demandes KYC 2', () => {
  it('liste les demandes en attente, avec le compteur et l\'alerte sanctions', async () => {
    await signIn('kyc@capitalinvest.cd')
    expect(await screen.findByText('Amani Kabeya')).toBeInTheDocument()
    expect(screen.getByText('1 demande signalée par le contrôle sanctions.')).toBeInTheDocument()
    expect(menu().getByLabelText('2 en attente')).toBeInTheDocument()
  })

  it('un agent valide une demande après avoir vu les trois photos', async () => {
    const user = await signIn('kyc@capitalinvest.cd')
    await user.click(await screen.findByText('Amani Kabeya'))
    expect(await screen.findByText('Recto de la pièce')).toBeInTheDocument()
    await waitFor(() => expect(server.documentsViewed).toEqual(['k-1-front', 'k-1-back', 'k-1-selfie']))
    expect(screen.getByText(/36 ans/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Valider le niveau 2/ }))
    expect(await screen.findByRole('status')).toHaveTextContent('Niveau 2 accordé à Amani Kabeya')
    expect(server.kyc[0].status).toBe('APPROVED')
    await waitFor(() => expect(screen.queryByText('Amani Kabeya')).not.toBeInTheDocument())
  })

  it('un refus demande un motif, qui est envoyé au serveur', async () => {
    const user = await signIn('kyc@capitalinvest.cd')
    await user.click(await screen.findByText('Amani Kabeya'))
    await user.click(await screen.findByRole('button', { name: 'Refuser…' }))
    const confirm = screen.getByRole('button', { name: 'Refuser la demande' })
    expect(confirm).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Photo floue ou illisible' }))
    await user.click(confirm)
    await screen.findByRole('status')
    expect(server.kyc[0]).toMatchObject({ status: 'REJECTED', rejectionReason: 'Photo floue ou illisible' })
  })

  it('une correspondance sanctions ne peut pas être validée par un agent KYC', async () => {
    const user = await signIn('kyc@capitalinvest.cd')
    await user.click(await screen.findByText('Mutombo Fictif Sanction'))
    expect(await screen.findByText(/Seule la conformité peut valider cette demande/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Valider le niveau 2/ })).toBeDisabled()
  })

  it('la conformité peut valider une correspondance sanctions', async () => {
    const user = await signIn('conformite@capitalinvest.cd')
    await user.click(await screen.findByText('Mutombo Fictif Sanction'))
    await user.click(await screen.findByRole('button', { name: /Valider le niveau 2/ }))
    await screen.findByRole('status')
    expect(server.kyc[1].status).toBe('APPROVED')
  })
})

describe('promotions', () => {
  it('le marketing publie une promotion en voyant l\'aperçu de l\'app', async () => {
    const user = await signIn('marketing@capitalinvest.cd')
    await user.click(await screen.findByRole('button', { name: /Nouvelle promotion/ }))
    await user.type(screen.getByLabelText(/^Titre/), 'Fête de l\'indépendance')
    await user.type(screen.getByLabelText(/^Texte 140/), 'Envois sans souci tout le week-end.')
    await user.selectOptions(screen.getByLabelText('Au toucher'), 'TRANSFER')
    await user.type(screen.getByLabelText(/^Texte du bouton/), 'Envoyer')
    await user.click(screen.getByRole('radio', { name: 'Vert' }))

    const preview = document.querySelector('.phone .promo')!
    expect(preview).toHaveClass('GREEN')
    expect(preview).toHaveTextContent("Fête de l'indépendance")
    expect(preview).toHaveTextContent('Envoyer →')

    await user.click(screen.getByRole('button', { name: 'Publier' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Promotion publiée')
    expect(server.requests.at(-2)).toMatchObject({
      method: 'POST',
      body: { title: "Fête de l'indépendance", action: 'TRANSFER', ctaLabel: 'Envoyer', style: 'GREEN', audience: 'ALL' },
    })
  })

  it('une action sans texte de bouton est refusée avant l\'envoi', async () => {
    const user = await signIn('marketing@capitalinvest.cd')
    await user.click(await screen.findByRole('button', { name: /Nouvelle promotion/ }))
    await user.type(screen.getByLabelText(/^Titre/), 'Titre')
    await user.type(screen.getByLabelText(/^Texte 140/), 'Texte')
    await user.selectOptions(screen.getByLabelText('Au toucher'), 'TOPUP')
    await user.click(screen.getByRole('button', { name: 'Publier' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Indiquez le texte du bouton')
    expect(server.promotions).toHaveLength(1)
  })

  it('retirer une promotion la garde dans la liste', async () => {
    const user = await signIn('marketing@capitalinvest.cd')
    await user.click(await screen.findByText('Passez au niveau 2'))
    await user.click(await screen.findByRole('button', { name: "Retirer de l'app" }))
    expect(await screen.findByRole('status')).toHaveTextContent("Promotion retirée de l'app")
    expect(await screen.findByText('Retirée')).toBeInTheDocument()
    expect(server.promotions[0].active).toBe(false)
  })
})

describe('agents', () => {
  it("l'administrateur crée un agent puis le désactive", async () => {
    const user = await signIn('admin@capitalinvest.cd')
    await user.click(menu().getByRole('link', { name: 'Agents' }))
    await user.click(await screen.findByRole('button', { name: /Nouvel agent/ }))
    await user.type(screen.getByLabelText('Nom complet'), 'Sarah Kalala')
    await user.type(screen.getByLabelText('Adresse e-mail professionnelle'), 'sarah@capitalinvest.cd')
    await user.type(screen.getByLabelText(/^Mot de passe provisoire/), 'un-mot-de-passe-long')
    await user.selectOptions(screen.getByLabelText(/^Rôle/), 'MARKETING')
    await user.click(screen.getByRole('button', { name: 'Créer le compte' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Compte créé pour Sarah Kalala')

    const row = (await screen.findByText('sarah@capitalinvest.cd')).closest('tr')!
    await user.click(within(row).getByRole('button', { name: 'Désactiver' }))
    await waitFor(() => expect(within(row).getByText('Désactivé')).toBeInTheDocument())
    expect(server.agents.at(-1)).toMatchObject({ role: 'MARKETING', active: false })
  })

  it("l'administrateur ne peut pas se désactiver lui-même", async () => {
    const user = await signIn('admin@capitalinvest.cd')
    await user.click(menu().getByRole('link', { name: 'Agents' }))
    const row = (await screen.findByText('admin@capitalinvest.cd')).closest('tr')!
    expect(within(row).queryByRole('button')).toBeNull()
  })
})
