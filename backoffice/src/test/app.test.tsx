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

const menu = () => within(screen.getByRole('navigation', { name: 'Menu principal' }))

/** Connexion, puis ouverture d'un menu (par défaut, l'agent arrive sur le tableau de bord). */
async function signIn(email: string, open?: string) {
  const user = userEvent.setup()
  render(<App api={api} />)
  await user.type(await screen.findByLabelText('Adresse e-mail'), email)
  await user.type(screen.getByLabelText('Mot de passe'), server.password)
  await user.click(screen.getByRole('button', { name: 'Se connecter' }))
  await screen.findByRole('navigation', { name: 'Menu principal' })
  if (open) await user.click(menu().getByRole('link', { name: new RegExp(open) }))
  return user
}

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
    ['kyc@capitalinvest.cd', ['Tableau de bord', 'Demandes KYC 2']],
    ['marketing@capitalinvest.cd', ['Tableau de bord', 'Promotions']],
    ['conformite@capitalinvest.cd', ['Tableau de bord', 'Demandes KYC 2', 'Clients', 'Marchands', 'Surveillance', 'Rapprochement', "Journal d'audit"]],
    ['admin@capitalinvest.cd', ['Tableau de bord', 'Demandes KYC 2', 'Promotions', 'Clients', 'Marchands', 'Surveillance', 'Rapprochement', 'Agents', "Journal d'audit"]],
  ])('%s ne voit que les menus de son rôle', async (email, expected) => {
    await signIn(email)
    await waitFor(() => expect(menu().getAllByRole('link').map((l) => l.querySelector('.nav-label')?.textContent)).toEqual(expected))
  })

  it('retrouve la session après un rechargement de la page', async () => {
    sessionStorage.setItem('capital-invest-agent', server.login('KYC_AGENT'))
    render(<App api={api} />)
    expect(await screen.findByText('Bonjour, Patrick !')).toBeInTheDocument()
  })

  it('une session expirée ramène à la connexion avec une explication', async () => {
    const user = await signIn('kyc@capitalinvest.cd', 'Demandes KYC')
    await screen.findByText('Amani Kabeya')
    server.sessions.clear()
    await user.click(screen.getByText('Amani Kabeya'))
    expect(await screen.findByText(/session a expiré/)).toBeInTheDocument()
    expect(sessionStorage.getItem('capital-invest-agent')).toBeNull()
  })
})

describe('tableau de bord et notifications', () => {
  it("l'agent arrive sur le tableau de bord avec les chiffres clés", async () => {
    server.kyc.push({ ...server.kyc[0], id: 'k-9', customerName: 'Esther Mbuyi', status: 'APPROVED',
      createdAt: new Date(Date.now() - 3 * 3600e3).toISOString(), decidedAt: new Date(Date.now() - 3600e3).toISOString() })
    await signIn('kyc@capitalinvest.cd')
    expect(await screen.findByText('Bonjour, Patrick !')).toBeInTheDocument()
    expect(screen.getByText('Demandes en attente')).toBeInTheDocument()
    expect(await screen.findByText('Esther Mbuyi')).toBeInTheDocument() // décisions récentes
    expect(screen.getByText('2 h')).toBeInTheDocument() // délai médian
    expect(screen.getByRole('img', { name: /Demandes reçues/ })).toBeInTheDocument()
  })

  it("les notifications signalent les nouvelles demandes et l'alerte sanctions", async () => {
    const user = await signIn('kyc@capitalinvest.cd')
    const bell = screen.getByRole('button', { name: 'Notifications' })
    await waitFor(() => expect(bell.querySelector('.dot')).not.toBeNull())
    await user.click(bell)
    const panel = within(screen.getByRole('region', { name: 'Notifications' }))
    expect(panel.getByText(/correspondance au contrôle sanctions/)).toBeInTheDocument()
    expect(panel.getByText(/Nouvelle demande de niveau 2/)).toBeInTheDocument()
    await user.click(panel.getByText('Mutombo Fictif Sanction'))
    expect(await screen.findByText(/Seule la conformité peut valider/)).toBeInTheDocument()
    expect(bell.querySelector('.dot')).toBeNull()
  })

  it('le marketing voit les promotions en ligne sur son tableau de bord', async () => {
    await signIn('marketing@capitalinvest.cd')
    expect(await screen.findByText("En ligne dans l'app")).toBeInTheDocument()
    expect(screen.queryByText('Demandes en attente')).toBeNull()
  })
})

describe('demandes KYC 2', () => {
  it('liste les demandes en attente, avec le compteur et l\'alerte sanctions', async () => {
    await signIn('kyc@capitalinvest.cd', 'Demandes KYC')
    expect(await screen.findByText('Amani Kabeya')).toBeInTheDocument()
    expect(screen.getByText('1 demande signalée par le contrôle sanctions.')).toBeInTheDocument()
    expect(menu().getByLabelText('2 en attente')).toBeInTheDocument()
  })

  it('un agent valide une demande après avoir vu les trois photos', async () => {
    const user = await signIn('kyc@capitalinvest.cd', 'Demandes KYC')
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
    const user = await signIn('kyc@capitalinvest.cd', 'Demandes KYC')
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
    const user = await signIn('kyc@capitalinvest.cd', 'Demandes KYC')
    await user.click(await screen.findByText('Mutombo Fictif Sanction'))
    expect(await screen.findByText(/Seule la conformité peut valider cette demande/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Valider le niveau 2/ })).toBeDisabled()
  })

  it('la conformité peut valider une correspondance sanctions', async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Demandes KYC')
    await user.click(await screen.findByText('Mutombo Fictif Sanction'))
    await user.click(await screen.findByRole('button', { name: /Valider le niveau 2/ }))
    await screen.findByRole('status')
    expect(server.kyc[1].status).toBe('APPROVED')
  })
})

describe('promotions', () => {
  it('le marketing publie une promotion en voyant l\'aperçu de l\'app', async () => {
    const user = await signIn('marketing@capitalinvest.cd', 'Promotions')
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
    expect(server.requests.find((r) => r.method === 'POST' && r.path === '/v1/backoffice/promotions')).toMatchObject({
      method: 'POST',
      body: { title: "Fête de l'indépendance", action: 'TRANSFER', ctaLabel: 'Envoyer', style: 'GREEN', audience: 'ALL' },
    })
  })

  it('une action sans texte de bouton est refusée avant l\'envoi', async () => {
    const user = await signIn('marketing@capitalinvest.cd', 'Promotions')
    await user.click(await screen.findByRole('button', { name: /Nouvelle promotion/ }))
    await user.type(screen.getByLabelText(/^Titre/), 'Titre')
    await user.type(screen.getByLabelText(/^Texte 140/), 'Texte')
    await user.selectOptions(screen.getByLabelText('Au toucher'), 'TOPUP')
    await user.click(screen.getByRole('button', { name: 'Publier' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Indiquez le texte du bouton')
    expect(server.promotions).toHaveLength(1)
  })

  it('le graphiste fournit le visuel : aperçu tout de suite, envoi à la publication, sans texte ni bouton', async () => {
    const user = await signIn('marketing@capitalinvest.cd', 'Promotions')
    await user.click(await screen.findByRole('button', { name: /Nouvelle promotion/ }))
    await user.type(screen.getByLabelText(/^Titre/), 'Recharge offerte')
    await user.selectOptions(screen.getByLabelText('Au toucher'), 'TOPUP')
    const visuel = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'noel.png', { type: 'image/png' })
    await user.upload(screen.getByLabelText('Visuel'), visuel)

    expect(screen.getByRole('img', { name: 'Recharge offerte' })).toHaveAttribute('src', 'blob:photo')
    expect(screen.getByText(/noel.png · envoyé à l'enregistrement/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Publier' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Promotion publiée')
    expect(server.requests.find((r) => r.method === 'POST' && r.path === '/v1/backoffice/promotions')?.body)
      .toMatchObject({ title: 'Recharge offerte', body: null, ctaLabel: null, action: 'TOPUP' })
    expect(server.images.get('p-2')).toBe(visuel)
    // De retour dans la liste, la promotion s'affiche avec son visuel.
    expect(await screen.findByRole('img', { name: 'Recharge offerte' })).toBeInTheDocument()
  })

  it('un fichier qui n\'est pas une image, ou trop lourd, est refusé avant l\'envoi', async () => {
    const user = await signIn('marketing@capitalinvest.cd', 'Promotions')
    await user.click(await screen.findByRole('button', { name: /Nouvelle promotion/ }))
    const input = screen.getByLabelText('Visuel')
    await userEvent.setup({ applyAccept: false }).upload(input, new File(['%PDF'], 'brief.pdf', { type: 'application/pdf' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Visuel JPEG, PNG ou WebP attendu')
    await user.upload(input, new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'lourd.jpg', { type: 'image/jpeg' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('2 Mo au plus')
    // Sans visuel ni texte, la carte de secours serait vide.
    await user.type(screen.getByLabelText(/^Titre/), 'Titre seul')
    await user.click(screen.getByRole('button', { name: 'Publier' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Ajoutez un visuel, ou écrivez un texte')
    expect(server.promotions).toHaveLength(1)
  })

  it('retirer une promotion la garde dans la liste', async () => {
    const user = await signIn('marketing@capitalinvest.cd', 'Promotions')
    await user.click(await screen.findByText('Passez au niveau 2'))
    await user.click(await screen.findByRole('button', { name: "Retirer de l'app" }))
    expect(await screen.findByRole('status')).toHaveTextContent("Promotion retirée de l'app")
    expect(await screen.findByText('Retirée')).toBeInTheDocument()
    expect(server.promotions[0].active).toBe(false)
  })
})

describe('clients', () => {
  it('la conformité retrouve un client, filtre ses opérations et ouvre un reçu', async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Clients')
    await user.type(screen.getByLabelText('Numéro du client'), '+243 970 000 999')
    await user.click(screen.getByRole('button', { name: 'Rechercher' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Aucun client avec ce numéro')

    await user.clear(screen.getByLabelText('Numéro du client'))
    await user.type(screen.getByLabelText('Numéro du client'), '+243970000001')
    await user.click(screen.getByRole('button', { name: 'Rechercher' }))
    expect(await screen.findByText('Amani Kabeya')).toBeInTheDocument()
    expect(screen.getByText('47,50 $')).toBeInTheDocument()
    expect(await screen.findByText('Envoi à Béni Mukendi')).toBeInTheDocument()
    expect(screen.getByText('En cours')).toBeInTheDocument()
    expect(screen.getByText('-20,20 $')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Voir plus' }))
    expect(await screen.findByText('Recharge M-Pesa')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Voir plus' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Envois' }))
    await waitFor(() => expect(screen.queryByText('Retrait vers Airtel Money')).not.toBeInTheDocument())
    await user.click(screen.getByText('Envoi à Béni Mukendi'))
    const receipt = await screen.findByRole('complementary', { name: 'Reçu' })
    expect(await within(receipt).findByText('CI-20261118-0000S1')).toBeInTheDocument()
    expect(within(receipt).getByText('+243970000402')).toBeInTheDocument()
    expect(within(receipt).getByText('Loyer')).toBeInTheDocument()
    expect(window.location.hash).toBe('#/clients/c-1')
  })
})

describe('surveillance', () => {
  it("la conformité suspend le compte d'une alerte avec une note, puis le retrouve sur la fiche client", async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Surveillance')
    const open = await screen.findByRole('region', { name: 'Alertes à traiter' })
    expect(await within(open).findByText('Argent reçu de nombreux clients')).toBeInTheDocument()
    expect(within(open).getByText('7 expéditeurs différents en 24 h, pour 640,00 $')).toBeInTheDocument()
    expect(within(open).queryByText("Rafale d'opérations")).not.toBeInTheDocument()

    await user.click(within(open).getByRole('button', { name: 'Suspendre' }))
    const confirm = within(open).getByRole('button', { name: 'Suspendre le compte' })
    expect(confirm).toBeDisabled()
    await user.type(within(open).getByLabelText(/Pourquoi suspendre/), 'Collecte pour des tiers, déclaration à la CENAREF')
    await user.click(confirm)
    expect(await screen.findByRole('status')).toHaveTextContent('Compte de Amani Kabeya suspendu')
    expect(await within(open).findByText('Aucune alerte à traiter.')).toBeInTheDocument()
    expect(server.requests.find((r) => r.path === '/v1/backoffice/monitoring/alerts/al-1/resolve')?.body)
      .toEqual({ action: 'SUSPEND', note: 'Collecte pour des tiers, déclaration à la CENAREF' })

    await user.click(screen.getByRole('button', { name: 'Traitées' }))
    const closed = await screen.findByRole('region', { name: 'Alertes traitées' })
    expect(await within(closed).findByText('Compte suspendu')).toBeInTheDocument()
    expect(within(closed).getByText('Commerçant connu, paie ses fournisseurs')).toBeInTheDocument()

    await user.click(within(closed).getAllByRole('button', { name: 'Amani Kabeya' })[0])
    expect(window.location.hash).toBe('#/clients/c-1')
    const account = await screen.findByRole('region', { name: 'Compte' })
    expect(within(account).getByText('Compte suspendu')).toBeInTheDocument()
    expect(await within(account).findByText(/CENAREF/)).toBeInTheDocument()
  })

  it('la conformité rédige une déclaration de soupçon depuis une alerte, puis note sa transmission à la CENAREF', async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Surveillance')
    const open = await screen.findByRole('region', { name: 'Alertes à traiter' })
    await user.click(await within(open).findByRole('button', { name: 'Déclarer' }))
    expect(window.location.hash).toBe('#/surveillance/ds-1')
    expect(server.requests.find((r) => r.path === '/v1/backoffice/suspicion-reports')?.body).toEqual({ customerId: 'c-1', alertId: 'al-1' })

    const subject = await screen.findByRole('region', { name: 'Personne déclarée' })
    expect(within(subject).getByText('Amani Kabeya')).toBeInTheDocument()
    const facts = screen.getByLabelText('Faits constatés')
    expect(facts).toHaveValue('- Argent reçu de nombreux clients (19/11/2026) : 7 expéditeurs différents en 24 h, pour 640,00 $')
    const ops = screen.getByRole('region', { name: 'Opérations de la période' })
    expect(within(ops).getByText(/3 opérations/)).toBeInTheDocument()

    const transmit = screen.getByRole('button', { name: 'Noter la transmission' })
    await user.type(screen.getByLabelText('Référence CENAREF'), 'DS-2026-0142')
    expect(transmit).toBeDisabled()
    await user.type(screen.getByLabelText(/Analyse/), 'Revenus déclarés sans rapport avec la collecte')
    expect(screen.getByRole('button', { name: 'Imprimer' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Enregistrer le brouillon' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Brouillon enregistré')
    expect(screen.getByRole('button', { name: 'Imprimer' })).toBeEnabled()

    await user.click(transmit)
    expect(await screen.findByText('Transmise à la CENAREF')).toBeInTheDocument()
    expect(screen.getByText('DS-2026-0142')).toBeInTheDocument()
    expect(screen.getByLabelText(/Analyse/)).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Noter la transmission' })).not.toBeInTheDocument()
    expect(server.requests.find((r) => r.path === '/v1/backoffice/suspicion-reports/ds-1/submit')?.body)
      .toMatchObject({ cenarefReference: 'DS-2026-0142' })

    await user.click(screen.getByRole('button', { name: /Retour/ }))
    await user.click(await screen.findByRole('button', { name: 'Déclarations' }))
    const list = await screen.findByRole('region', { name: 'Déclarations de soupçon' })
    expect(await within(list).findByText('Transmise')).toBeInTheDocument()
    expect(within(list).getByText(/DS-2026-0142/)).toBeInTheDocument()
  })

  it('la conformité réactive un compte suspendu depuis la fiche client', async () => {
    server.customer.status = 'SUSPENDED'
    const user = await signIn('conformite@capitalinvest.cd', 'Clients')
    await user.type(screen.getByLabelText('Numéro du client'), '+243970000001')
    await user.click(screen.getByRole('button', { name: 'Rechercher' }))
    const account = await screen.findByRole('region', { name: 'Compte' })
    await user.click(within(account).getByRole('button', { name: 'Réactiver' }))
    await user.type(within(account).getByLabelText('Pourquoi réactiver ?'), 'Justificatifs reçus')
    await user.click(within(account).getByRole('button', { name: 'Réactiver le compte' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Compte de Amani Kabeya réactivé')
    expect(await within(account).findByText('Compte actif')).toBeInTheDocument()
    expect(server.requests.find((r) => r.path === '/v1/backoffice/customers/c-1/status')?.body)
      .toEqual({ status: 'ACTIVE', reason: 'Justificatifs reçus' })
  })
})

describe('marchands', () => {
  it("l'administrateur crée un marchand, voit son QR à imprimer, puis le suspend avec un motif", async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Marchands')
    expect(await screen.findByText('Alimentation Mama Nzuzi')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Nouveau marchand/ }))
    await user.type(screen.getByLabelText(/^Nom du commerce/), 'Pharmacie du Centre')
    await user.type(screen.getByLabelText(/^Numéro qui reçoit les SMS/), '0990000202')
    await user.type(screen.getByLabelText('Ville'), 'Kolwezi')
    await user.clear(screen.getByLabelText(/^Commission/))
    await user.type(screen.getByLabelText(/^Commission/), '1,5')
    await user.click(screen.getByRole('button', { name: 'Créer le marchand' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Pharmacie du Centre peut maintenant être payé')
    expect(server.requests.find((r) => r.method === 'POST' && r.path === '/v1/backoffice/merchants')?.body)
      .toMatchObject({ name: 'Pharmacie du Centre', city: 'Kolwezi', phone: '0990000202', commissionBps: 150, category: null })

    const panel = await screen.findByRole('complementary', { name: 'Fiche marchand' })
    expect(window.location.hash).toBe('#/marchands/m-2')
    await waitFor(() => expect(within(panel).getByRole('img', { name: 'QR de paiement de Pharmacie du Centre' }).innerHTML).toContain('<svg'))
    expect(within(panel).getByLabelText('Code marchand')).toHaveTextContent('NEW 2AB')
    expect(within(panel).getByText('1,5 %')).toBeInTheDocument()
    window.print = vi.fn()
    await user.click(within(panel).getByRole('button', { name: /Imprimer l'affiche/ }))
    expect(window.print).toHaveBeenCalled()

    await user.click(within(panel).getByRole('button', { name: 'Suspendre le marchand' }))
    await user.type(within(panel).getByLabelText('Motif de la suspension'), 'Plainte client')
    await user.click(within(panel).getByRole('button', { name: 'Suspendre' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Pharmacie du Centre est suspendu')
    expect(server.merchants[1].status).toBe('SUSPENDED')
  })

  it('la conformité consulte les paiements reçus sans pouvoir créer ni suspendre', async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Marchands')
    expect(screen.queryByRole('button', { name: /Nouveau marchand/ })).not.toBeInTheDocument()
    await user.click(await screen.findByText('Alimentation Mama Nzuzi'))
    const panel = await screen.findByRole('complementary', { name: 'Fiche marchand' })
    expect(await within(panel).findByText('Amani Kabeya')).toBeInTheDocument()
    expect(within(panel).getByText(/CI-20261119-0000M1/)).toBeInTheDocument()
    expect(within(panel).getByText('commission 0,13 $')).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: /Suspendre/ })).not.toBeInTheDocument()
  })
})

describe('reversements aux marchands', () => {
  it("l'administrateur reverse ce qui est dû vers le mobile money du marchand, une seule fois", async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Marchands')
    await user.click(await screen.findByText('Alimentation Mama Nzuzi'))
    const panel = await screen.findByRole('complementary', { name: 'Fiche marchand' })
    const section = within(await within(panel).findByRole('region', { name: 'Reversements' }))
    expect(section.getByText('Vers').closest('.row')).toHaveTextContent('Airtel Money +243990000101')
    expect(section.getByText('Versé')).toBeInTheDocument()

    await user.click(section.getByRole('button', { name: 'Reverser 12,37 $' }))
    expect(section.getByLabelText('Montant à reverser ($)')).toHaveValue('12,37')
    await user.clear(section.getByLabelText('Montant à reverser ($)'))
    await user.type(section.getByLabelText('Montant à reverser ($)'), '20')
    await user.click(section.getByRole('button', { name: 'Confirmer le reversement' }))
    expect(await section.findByRole('alert')).toHaveTextContent("Le marchand n'a que 12,37 $ à reverser")

    await user.clear(section.getByLabelText('Montant à reverser ($)'))
    await user.type(section.getByLabelText('Montant à reverser ($)'), '10')
    await user.click(section.getByRole('button', { name: 'Confirmer le reversement' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Reversement de 10,00 $ envoyé vers Airtel Money +243990000101')
    const calls = server.requests.filter((r) => r.path === '/v1/backoffice/merchants/m-1/payouts')
    expect(calls).toHaveLength(1)
    expect(calls[0].body).toMatchObject({ amountMinor: 1000 })
    expect((calls[0].body as { requestId: string }).requestId).toMatch(/^[0-9a-f-]{36}$/)
    expect(await section.findByText('En cours')).toBeInTheDocument()
  })

  it('changer le compte de reversement prévient le marchand et bloque les reversements 24 h', async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Marchands')
    await user.click(await screen.findByText('Alimentation Mama Nzuzi'))
    const panel = await screen.findByRole('complementary', { name: 'Fiche marchand' })
    const section = within(await within(panel).findByRole('region', { name: 'Reversements' }))
    await user.click(section.getByRole('button', { name: 'Modifier' }))
    expect(section.getByText(/prévenu par SMS/)).toBeInTheDocument()
    await user.clear(section.getByLabelText('Numéro qui reçoit les reversements'))
    await user.type(section.getByLabelText('Numéro qui reçoit les reversements'), '0820000666')
    expect(section.getByLabelText('Opérateur')).toHaveValue('MPESA')
    await user.click(section.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByRole('status')).toHaveTextContent('reversements possibles dans 24 h')
    expect(await section.findByText('M-Pesa +243820000666')).toBeInTheDocument()
    expect(section.getByRole('note')).toHaveTextContent('reversements possibles à partir du')
    expect(section.getByRole('button', { name: /^Reverser/ })).toBeDisabled()
  })

  it("l'administrateur coupe puis réactive le reversement automatique de nuit", async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Marchands')
    await user.click(await screen.findByText('Alimentation Mama Nzuzi'))
    const panel = await screen.findByRole('complementary', { name: 'Fiche marchand' })
    const section = within(await within(panel).findByRole('region', { name: 'Reversements' }))
    expect(section.getByText('Chaque nuit').closest('.row')).toHaveTextContent('reversement automatique')
    expect(section.getByText(/Vers 1 h 30/)).toBeInTheDocument()
    expect(section.getByText(/· automatique/)).toBeInTheDocument()

    await user.click(section.getByRole('button', { name: 'Couper' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Reversement automatique coupé')
    expect(await section.findByText('pas de reversement automatique')).toBeInTheDocument()
    expect(section.queryByText(/Vers 1 h 30/)).not.toBeInTheDocument()
    await user.click(section.getByRole('button', { name: 'Activer' }))
    expect(await section.findByText('reversement automatique')).toBeInTheDocument()
    expect(server.requests.filter((r) => r.path === '/v1/backoffice/merchants/m-1/auto-payout').map((r) => r.body))
      .toEqual([{ enabled: false }, { enabled: true }])
  })

  it('la conformité voit le réglage de nuit sans pouvoir le changer', async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Marchands')
    await user.click(await screen.findByText('Alimentation Mama Nzuzi'))
    const panel = await screen.findByRole('complementary', { name: 'Fiche marchand' })
    const section = within(await within(panel).findByRole('region', { name: 'Reversements' }))
    expect(section.getByText('reversement automatique')).toBeInTheDocument()
    expect(section.queryByRole('button', { name: 'Couper' })).not.toBeInTheDocument()
  })
})

describe('agents', () => {
  it("l'administrateur crée un agent puis le désactive", async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Agents')
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
    await signIn('admin@capitalinvest.cd', 'Agents')
    const row = (await screen.findByText('admin@capitalinvest.cd')).closest('tr')!
    expect(within(row).queryByRole('button')).toBeNull()
  })
})

describe('rapprochement', () => {
  it("la conformité voit les écarts et les recharges à vérifier, avec ce qu'il faut faire", async () => {
    await signIn('conformite@capitalinvest.cd', 'Rapprochement')
    const items = await screen.findByRole('region', { name: 'Écarts à traiter' })
    expect(await within(items).findByText('Encaissé, non crédité')).toBeInTheDocument()
    expect(within(items).getByText('Toujours en attente')).toBeInTheDocument()
    // Seul un paiement encaissé et non crédité peut être crédité depuis l'écran.
    const pendingRow = within(items).getByText('Josué Kalala').closest('tr')!
    expect(within(pendingRow).queryByRole('button', { name: 'Créditer' })).toBeNull()
    expect(within(pendingRow).queryByText(/reçu/)).toBeNull()

    const review = screen.getByRole('region', { name: 'Recharges et retraits à vérifier' })
    expect(within(review).getByText("Montant reçu chez l'opérateur : 40,00 $, attendu 50,00 $")).toBeInTheDocument()
    expect(within(review).getByText('50,00 $')).toBeInTheDocument()
    expect(within(review).getByText('M-Pesa · pawaPay')).toBeInTheDocument()
  })

  it('créditer une recharge à vérifier demande un motif, envoyé au serveur', async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Rapprochement')
    const review = await screen.findByRole('region', { name: 'Recharges et retraits à vérifier' })
    await user.click(await within(review).findByRole('button', { name: 'Créditer' }))
    const confirm = within(review).getByRole('button', { name: 'Créditer le client' })
    expect(confirm).toBeDisabled()
    await user.type(within(review).getByRole('textbox'), 'Le client a envoyé 40 $, confirmé par M-Pesa')
    await user.click(confirm)
    expect(await screen.findByRole('status')).toHaveTextContent('Amani Kabeya a été crédité')
    expect(server.requests.find((r) => r.path === '/v1/backoffice/topups/t-review/resolve')?.body).toEqual({
      action: 'CREDIT',
      note: 'Le client a envoyé 40 $, confirmé par M-Pesa',
    })
    await waitFor(() => expect(within(review).queryByText('Amani Kabeya')).toBeNull())
  })

  it('un retrait sans réponse ne peut être remboursé que si l\'opérateur confirme l\'échec', async () => {
    const user = await signIn('conformite@capitalinvest.cd', 'Rapprochement')
    const review = await screen.findByRole('region', { name: 'Recharges et retraits à vérifier' })
    const row = (await within(review).findByText('Esther Mbuyi')).closest('tr')!
    expect(within(row).getByText('Retrait')).toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: 'Créditer' })).toBeNull()
    await user.click(within(row).getByRole('button', { name: 'Rembourser' }))
    await user.type(within(review).getByRole('textbox'), 'Le client dit ne rien avoir reçu')
    await user.click(within(review).getByRole('button', { name: 'Rembourser le client' }))
    expect(await within(review).findByRole('alert')).toHaveTextContent('payer le client deux fois')
    expect(within(review).getByText('Esther Mbuyi')).toBeInTheDocument()
  })

  it('un retrait noté versé mais non payé se rembourse depuis les écarts', async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Rapprochement')
    const items = await screen.findByRole('region', { name: 'Écarts à traiter' })
    const row = (await within(items).findByText('Grâce Ngalula')).closest('tr')!
    expect(within(row).getByText('Noté versé, non payé')).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Rembourser' }))
    await user.type(within(items).getByRole('textbox'), 'Échec confirmé par M-Pesa')
    await user.click(within(items).getByRole('button', { name: 'Rembourser le client' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Grâce Ngalula a été remboursé')
    expect(server.requests.find((r) => r.path === '/v1/backoffice/reconciliation/items/i-3/resolve')?.body).toEqual({
      action: 'CREDIT',
      note: 'Échec confirmé par M-Pesa',
    })
  })

  it("clore un écart l'enlève de la liste", async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Rapprochement')
    const items = await screen.findByRole('region', { name: 'Écarts à traiter' })
    const row = (await within(items).findByText('Josué Kalala')).closest('tr')!
    await user.click(within(row).getByRole('button', { name: 'Clore' }))
    await user.type(within(items).getByRole('textbox'), 'Remboursé par Orange')
    await user.click(within(items).getByRole('button', { name: 'Clore le dossier' }))
    await waitFor(() => expect(within(items).queryByText('Josué Kalala')).toBeNull())
    expect(server.items.find((i) => i.id === 'i-2')).toMatchObject({ resolvedByName: 'Grace Admin', resolution: 'Remboursé par Orange' })
  })

  it('lancer le rapprochement d\'une journée affiche le résultat', async () => {
    const user = await signIn('admin@capitalinvest.cd', 'Rapprochement')
    const day = await screen.findByLabelText('Journée à rapprocher')
    await user.clear(day)
    await user.type(day, '2026-11-18')
    await user.click(screen.getByRole('button', { name: /Lancer/ }))
    expect(await screen.findByRole('status')).toHaveTextContent('Rapprochement du 18 novembre 2026 : tout correspond')
    expect(server.requests.find((r) => r.method === 'POST' && r.path === '/v1/backoffice/reconciliation/runs')?.body).toEqual({
      businessDate: '2026-11-18',
    })
  })

  it("un agent KYC n'a pas accès au rapprochement", async () => {
    await signIn('kyc@capitalinvest.cd')
    window.location.hash = '/rapprochement'
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Écarts à traiter' })).toBeNull())
  })
})
