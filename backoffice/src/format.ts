import type { AuditEntry, DiscrepancyKind, HistoryItem, KycRequest, Operator, PromoAction, PromoAudience, PromoStyle, Role } from './api'

export const roleLabel: Record<Role, string> = {
  KYC_AGENT: 'Agent KYC',
  COMPLIANCE: 'Conformité',
  ADMIN: 'Administrateur',
  MARKETING: 'Marketing',
}

export const idTypeLabel: Record<KycRequest['idType'], string> = {
  VOTER_CARD: "Carte d'électeur",
  PASSPORT: 'Passeport',
}

export const actionLabel: Record<PromoAction, string> = {
  KYC: 'Ouvre le passage au niveau 2',
  TOPUP: 'Ouvre la recharge',
  TRANSFER: "Ouvre l'envoi d'argent",
  LIMITS: 'Ouvre les plafonds',
}

export const styleLabel: Record<PromoStyle, string> = { OCHRE: 'Ocre', INK: 'Encre', GREEN: 'Vert', SKY: 'Ciel' }

export const audienceLabel: Record<PromoAudience, string> = {
  ALL: 'Tous les clients',
  KYC1: 'Clients niveau 1',
  KYC2: 'Clients niveau 2',
}

const auditLabels: Record<string, string> = {
  AGENT_LOGIN: 'Connexion',
  AGENT_CREATED: 'Agent créé',
  AGENT_ENABLED: 'Agent réactivé',
  AGENT_DISABLED: 'Agent désactivé',
  KYC_REQUEST_VIEWED: 'Demande KYC ouverte',
  KYC_DOCUMENT_VIEWED: "Pièce d'identité consultée",
  KYC_APPROVED: 'Demande KYC validée',
  KYC_REJECTED: 'Demande KYC refusée',
  KYC_SUBMITTED: 'Demande KYC envoyée',
  KYC_DOCUMENT_UPLOADED: 'Photo envoyée par le client',
  SCREENING_HIT: 'Alerte sanctions ou PEP',
  PROMOTION_CREATED: 'Promotion créée',
  PROMOTION_UPDATED: 'Promotion modifiée',
  PROMOTION_DISABLED: 'Promotion retirée',
  TOPUP_REVIEW_CREDITED: 'Recharge vérifiée et créditée',
  TOPUP_REVIEW_CLOSED: 'Recharge vérifiée et close',
  RECONCILIATION_RUN: 'Rapprochement lancé',
  RECONCILIATION_CREDIT: 'Écart corrigé : client crédité',
  RECONCILIATION_CLOSE: 'Écart clos',
  WITHDRAWAL_REVIEW_PAID: 'Retrait vérifié : versé',
  WITHDRAWAL_REVIEW_REFUND: 'Retrait vérifié : remboursé',
  CUSTOMER_VIEWED: 'Fiche client consultée',
  MERCHANT_CREATED: 'Marchand créé',
  MERCHANT_SUSPENDED: 'Marchand suspendu',
  MERCHANT_REACTIVATED: 'Marchand réactivé',
  MERCHANT_PAYOUT_ACCOUNT_CHANGED: 'Compte de reversement changé',
  MERCHANT_PAYOUT_REQUESTED: 'Reversement au marchand demandé',
  MONITORING_ALERT_OPENED: 'Alerte de surveillance ouverte',
  MONITORING_ALERT_DISMISSED: 'Alerte classée sans suite',
  MONITORING_ALERT_ESCALATED: 'Alerte retenue, compte suspendu',
  CUSTOMER_SUSPENDED: 'Client suspendu',
  SUSPICION_REPORT_DRAFTED: 'Déclaration de soupçon ouverte',
  SUSPICION_REPORT_VIEWED: 'Déclaration de soupçon consultée',
  SUSPICION_REPORT_SUBMITTED: 'Déclaration transmise à la CENAREF',
  CUSTOMER_REACTIVATED: 'Client réactivé',
  MERCHANT_PAYOUT_AUTOMATIC: 'Reversement automatique de nuit',
  MERCHANT_AUTO_PAYOUT_ON: 'Reversement automatique activé',
  MERCHANT_AUTO_PAYOUT_OFF: 'Reversement automatique coupé',
  AIRTIME_REVIEW_DONE: 'Crédit téléphone noté envoyé',
  AIRTIME_REVIEW_REFUND: 'Crédit téléphone remboursé',
  BILL_REVIEW_DONE: 'Facture notée payée',
  BILL_REVIEW_REFUND: 'Facture remboursée',
  COMPLAINT_ANSWERED_FOUNDED: 'Réclamation fondée, réponse envoyée',
  COMPLAINT_ANSWERED_UNFOUNDED: 'Réclamation non fondée, réponse envoyée',
}
export const auditLabel = (e: AuditEntry) => auditLabels[e.action] ?? e.action

const dateTime = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
const dateOnly = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long' })

export const formatDateTime = (iso: string) => dateTime.format(new Date(iso))
export const formatDate = (iso: string) => dateOnly.format(new Date(`${iso}T12:00:00`))
const dateShort = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short' })
/** « 19 nov. », pour les chiffres clés. */
export const formatShortDate = (iso: string) => dateShort.format(new Date(`${iso}T12:00:00`))

/** Âge en années révolues à une date donnée. */
export function ageOn(birthDate: string, today = new Date()): number {
  const [y, m, d] = birthDate.split('-').map(Number)
  let age = today.getFullYear() - y
  if (today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)) age--
  return age
}

/** Depuis combien de temps une demande attend, en mots simples. */
export function waitingFor(iso: string, now = new Date()): string {
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(iso).getTime()) / 60000))
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return `${hours} h`
  return `${Math.floor(hours / 24)} jours`
}

/** Valeur d'un champ datetime-local (heure locale) vers ISO, et l'inverse. */
export const fromLocalInput = (v: string) => (v ? new Date(v).toISOString() : null)
export function toLocalInput(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export const initials = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('')

export const operatorLabel: Record<Operator, string> = { MPESA: 'M-Pesa', AIRTEL: 'Airtel Money', ORANGE: 'Orange Money' }

export const providerLabel = (p: string) => ({ PAWAPAY: 'pawaPay', SIMULATOR: 'Simulateur' })[p] ?? p

/** Ce que chaque écart veut dire, et ce qu'il faut faire. */
export const kindLabel: Record<DiscrepancyKind, { title: string; help: string; tone: 'alert' | 'wait' }> = {
  CONFIRMED_NOT_CREDITED: {
    title: 'Encaissé, non crédité',
    help: "L'opérateur a reçu l'argent mais le client n'a rien eu : créditez-le.",
    tone: 'alert',
  },
  CREDITED_NOT_CONFIRMED: {
    title: 'Crédité, non encaissé',
    help: "Le client a été crédité alors que l'opérateur n'a rien reçu : à corriger avec la finance.",
    tone: 'alert',
  },
  AMOUNT_MISMATCH: {
    title: 'Montants différents',
    help: "Le montant crédité ne correspond pas à celui reçu par l'opérateur.",
    tone: 'alert',
  },
  PAID_BUT_REFUNDED: {
    title: 'Versé et remboursé',
    help: "L'opérateur a versé le retrait alors que le client a été remboursé : il a reçu l'argent deux fois. À régler avec le client.",
    tone: 'alert',
  },
  MARKED_PAID_NOT_PAID: {
    title: 'Noté versé, non payé',
    help: "Le retrait est noté versé mais l'opérateur n'a rien payé : remboursez le client.",
    tone: 'alert',
  },
  STILL_PENDING: {
    title: 'Toujours en attente',
    help: "Pas encore de réponse définitive de l'opérateur. Relancez le rapprochement plus tard.",
    tone: 'wait',
  },
}

const usd = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
/** 125075 → « 1 250,75 $ » (espaces insécables normalisés pour l'affichage et les tests). */
export const formatUsd = (minor: number) => `${usd.format(minor / 100).replace(/\s/g, ' ')} $`

/** Titre d'une opération du point de vue du client. */
export function historyTitle(i: HistoryItem): string {
  const op = i.operator ? operatorLabel[i.operator] : null
  switch (i.kind) {
    case 'TOPUP': return op ? `Recharge ${op}` : 'Recharge'
    case 'TRANSFER_SENT': return `Envoi à ${i.counterpartyName ?? 'un client'}`
    case 'TRANSFER_RECEIVED': return `Reçu de ${i.counterpartyName ?? 'un client'}`
    case 'WITHDRAWAL': return `Retrait vers ${op ?? 'mobile money'}`
    case 'WITHDRAWAL_REFUND': return 'Retrait remboursé'
    case 'MERCHANT_PAYMENT': return `Paiement à ${i.counterpartyName ?? 'un marchand'}`
    case 'AIRTIME': return `Crédit téléphone ${i.msisdn ?? ''}`.trim()
    case 'AIRTIME_REFUND': return 'Crédit non envoyé, remboursé'
    case 'BILL_PAYMENT': return `Facture ${i.counterpartyName ?? ''} ${i.billReference ?? ''}`.replace(/\s+/g, ' ').trim()
    case 'BILL_REFUND': return 'Facture non payée, remboursée'
    case 'SAVINGS_DEPOSIT': return 'Mis de côté (épargne)'
    case 'SAVINGS_WITHDRAWAL': return 'Repris de l\'épargne'
    default: return 'Correction de solde'
  }
}

export const historyStatus: Record<HistoryItem['status'], { label: string; cls: string } | null> = {
  COMPLETED: null,
  PENDING: { label: 'En cours', cls: 'wait' },
  REVIEW: { label: 'En vérification', cls: 'wait' },
  FAILED: { label: 'Refusé, remboursé', cls: 'alert' },
}

export const formatSigned = (minor: number) => `${minor > 0 ? '+' : ''}${formatUsd(minor)}`

const weekdays = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche']

/** « Chaque mois, le 5 », « Chaque vendredi ». */
export function scheduleLabel(frequency: 'WEEKLY' | 'MONTHLY', day: number): string {
  if (frequency === 'WEEKLY') return `Chaque ${weekdays[day - 1]}`
  if (day === 31) return 'Chaque mois, le dernier jour'
  return `Chaque mois, le ${day === 1 ? '1er' : day}`
}
