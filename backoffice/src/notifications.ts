import type { KycRequest, Promotion } from './api'

export interface Notice {
  id: string
  tone: 'alert' | 'ok' | 'wait'
  icon: 'up' | 'down' | 'shield' | 'clock'
  /** Morceaux de texte ; les éléments pairs sont en gras. */
  parts: string[]
  at: string
  href: string
}

/** Ce qui demande l'attention de l'agent, le plus récent d'abord. */
export function buildNotices(kyc: KycRequest[] | null, promos: Promotion[] | null, now = new Date()): Notice[] {
  const out: Notice[] = []
  for (const r of kyc ?? []) {
    out.push(
      r.screeningResult === 'HIT'
        ? {
            id: `kyc-${r.id}`, tone: 'alert', icon: 'shield', at: r.createdAt, href: `#/kyc/${r.id}`,
            parts: ['', r.customerName, ' : correspondance au contrôle sanctions, à examiner par la conformité.'],
          }
        : {
            id: `kyc-${r.id}`, tone: 'wait', icon: 'down', at: r.createdAt, href: `#/kyc/${r.id}`,
            parts: ['Nouvelle demande de niveau 2 de ', r.customerName, '.'],
          },
    )
  }
  const week = 7 * 24 * 3600 * 1000
  for (const p of promos ?? []) {
    if (!p.active) continue
    const start = new Date(p.startsAt).getTime()
    const end = p.endsAt ? new Date(p.endsAt).getTime() : null
    if (start > now.getTime() && start - now.getTime() < week) {
      out.push({ id: `start-${p.id}`, tone: 'ok', icon: 'up', at: p.startsAt, href: `#/promotions/${p.id}`,
        parts: ['La promotion ', p.title, ` passe en ligne le ${short(p.startsAt)}.`] })
    }
    if (end && end > now.getTime() && end - now.getTime() < week) {
      out.push({ id: `end-${p.id}`, tone: 'wait', icon: 'clock', at: p.endsAt!, href: `#/promotions/${p.id}`,
        parts: ['La promotion ', p.title, ` se termine le ${short(p.endsAt!)}.`] })
    }
  }
  return out.sort((a, b) => b.at.localeCompare(a.at))
}

const short = (iso: string) => new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' }).format(new Date(iso))

/** « il y a 5 min », « il y a 3 h », « dans 2 jours ». */
export function ago(iso: string, now = new Date()): string {
  const diff = Math.round((now.getTime() - new Date(iso).getTime()) / 60000)
  const abs = Math.abs(diff)
  const txt = abs < 60 ? `${abs} min` : abs < 48 * 60 ? `${Math.floor(abs / 60)} h` : `${Math.floor(abs / 1440)} jours`
  if (abs < 1) return "à l'instant"
  return diff >= 0 ? `il y a ${txt}` : `dans ${txt}`
}
