import type { AuditEntry, KycRequest, PromoAction, PromoAudience, PromoStyle, Role } from './api'

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
}
export const auditLabel = (e: AuditEntry) => auditLabels[e.action] ?? e.action

const dateTime = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
const dateOnly = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long' })

export const formatDateTime = (iso: string) => dateTime.format(new Date(iso))
export const formatDate = (iso: string) => dateOnly.format(new Date(`${iso}T12:00:00`))

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
