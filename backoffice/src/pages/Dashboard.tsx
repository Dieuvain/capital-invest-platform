import { useEffect, useState } from 'react'
import type { Agent, Api, KycRequest, Promotion } from '../api'
import { formatDateTime, idTypeLabel, initials, waitingFor } from '../format'
import { ErrorBanner, Icon } from '../ui'

interface Data {
  pending: KycRequest[]
  approved: KycRequest[]
  rejected: KycRequest[]
}

const DAY = 24 * 3600 * 1000
const dayName = new Intl.DateTimeFormat('fr-FR', { weekday: 'short' })

/** Demandes reçues chacun des 7 derniers jours, aujourd'hui compris. */
export function lastSevenDays(all: KycRequest[], now = new Date()) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  return Array.from({ length: 7 }, (_, i) => {
    const start = today - (6 - i) * DAY
    const count = all.filter((r) => {
      const t = new Date(r.createdAt).getTime()
      return t >= start && t < start + DAY
    }).length
    return { label: i === 6 ? "Auj." : dayName.format(new Date(start)).replace('.', ''), count }
  })
}

/** Délai médian entre l'envoi et la décision, en mots simples. */
export function medianDelay(done: KycRequest[]): string | null {
  const d = done
    .filter((r) => r.decidedAt)
    .map((r) => new Date(r.decidedAt!).getTime() - new Date(r.createdAt).getTime())
    .sort((a, b) => a - b)
  if (!d.length) return null
  const m = Math.round(d[Math.floor(d.length / 2)] / 60000)
  return m < 60 ? `${m} min` : m < 48 * 60 ? `${Math.round(m / 60)} h` : `${Math.round(m / 1440)} j`
}

const inLast30 = (r: KycRequest, now = Date.now()) => r.decidedAt && now - new Date(r.decidedAt).getTime() < 30 * DAY

export function Dashboard({ api, agent, allowed }: { api: Api; agent: Agent; allowed: string[] }) {
  const kycAccess = allowed.includes('kyc')
  const promoAccess = allowed.includes('promotions')
  const [data, setData] = useState<Data | null>(null)
  const [promos, setPromos] = useState<Promotion[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (kycAccess) {
      Promise.all([api.kycList('PENDING'), api.kycList('APPROVED'), api.kycList('REJECTED')]).then(
        ([pending, approved, rejected]) => setData({ pending, approved, rejected }),
        (e: Error) => setError(e.message),
      )
    }
    if (promoAccess) api.promotions().then(setPromos, (e: Error) => setError(e.message))
  }, [api, kycAccess, promoAccess])

  const live = promos?.filter((p) => p.live).length ?? 0
  const scheduled = promos?.filter((p) => p.active && !p.live && new Date(p.startsAt) > new Date()).length ?? 0

  if (!kycAccess) {
    return (
      <div className="stack">
        <ErrorBanner error={error} />
        <div className="overview">
          <div className="section-title">
            <h2>Vue d'ensemble</h2>
            <p>Le bandeau de promotions de l'accueil de l'app.</p>
          </div>
          <div className="stats">
            <Stat label="En ligne dans l'app" value={live} sub="5 au plus par client" />
            <Stat label="Programmées" value={scheduled} sub="passent en ligne seules" />
          </div>
        </div>
        <div className="row"><a className="btn" href="#/promotions">Gérer les promotions</a></div>
      </div>
    )
  }

  const all = data ? [...data.pending, ...data.approved, ...data.rejected] : []
  const days = lastSevenDays(all)
  const peak = Math.max(1, ...days.map((d) => d.count))
  const peakIndex = days.findIndex((d) => d.count === peak)
  const hits = data?.pending.filter((r) => r.screeningResult === 'HIT').length ?? 0
  const oldest = data?.pending.reduce<KycRequest | null>((a, r) => (!a || r.createdAt < a.createdAt ? r : a), null)
  const approved30 = data?.approved.filter((r) => inLast30(r)).length ?? 0
  const rejected30 = data?.rejected.filter((r) => inLast30(r)).length ?? 0
  const delay = data ? medianDelay([...data.approved, ...data.rejected]) : null
  const recent = [...(data?.approved ?? []), ...(data?.rejected ?? [])]
    .sort((a, b) => (b.decidedAt ?? '').localeCompare(a.decidedAt ?? ''))
    .slice(0, 5)

  return (
    <div className="stack" style={{ gap: 18 }}>
      <ErrorBanner error={error} />
      <div className="dash">
        <div className="stack" style={{ gap: 18 }}>
          <div className="overview">
            <div className="stack" style={{ gap: 16 }}>
              <div className="section-title">
                <h2>Vue d'ensemble</h2>
                <p>Les demandes de niveau 2 et leur traitement.</p>
              </div>
              <Stat label="Validées" value={approved30} sub="30 derniers jours" tone="ok" />
            </div>
            <div className="stack" style={{ gap: 16 }}>
              <div className="hero">
                <div className="top">
                  <span>Demandes en attente</span>
                  <Icon name="id" />
                </div>
                <div className="big">{data?.pending.length ?? '…'}</div>
                <div className="foot">
                  {oldest ? `La plus ancienne attend depuis ${waitingFor(oldest.createdAt)}` : 'Aucune demande en attente'}
                </div>
              </div>
              <div className="stats">
                <Stat label="Refusées" value={rejected30} sub="30 derniers jours" tone="alert" />
                <Stat label="Délai médian" value={delay ?? '-'} sub="de l'envoi à la décision" />
              </div>
            </div>
          </div>

          <div className="card table-wrap">
            <div className="row" style={{ padding: '18px 16px 4px' }}>
              <h2 style={{ flex: 1 }}>Décisions récentes</h2>
            </div>
            {recent.length === 0 ? (
              <div className="empty">Aucune décision pour le moment.</div>
            ) : (
              <table>
                <thead>
                  <tr><th>Date et heure</th><th>Client</th><th>Pièce</th><th>Décision</th></tr>
                </thead>
                <tbody>
                  {recent.map((r) => (
                    <tr key={r.id} className="link" onClick={() => (window.location.hash = `/kyc/${r.id}`)}>
                      <td className="small">{formatDateTime(r.decidedAt!)}</td>
                      <td>
                        <div className="person">
                          <span className="avatar">{initials(r.customerName)}</span>
                          <div>
                            <div className="tiny">{r.customerPhone}</div>
                            {r.customerName}
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="tiny">{idTypeLabel[r.idType]}</div>
                        <span className="mono">{r.idNumber}</span>
                      </td>
                      <td>
                        {r.status === 'APPROVED' ? <span className="badge ok">Niveau 2 accordé</span> : <span className="badge alert">Refusée</span>}
                        {r.rejectionReason && <div className="tiny" style={{ marginTop: 4 }}>{r.rejectionReason}</div>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="foot-links">
              <a href="#/kyc">Ouvrir la file d'attente</a>
              <a href="#/kyc">Toutes les demandes ↗</a>
            </div>
          </div>
        </div>

        <div className="stack" style={{ gap: 16 }}>
          <div className="card kpi">
            <div className="lbl">Alertes<br />sanctions</div>
            <span />
            <div className="val" style={{ color: hits ? 'var(--danger)' : undefined }}>{hits}</div>
            <div className="tiny">{hits ? 'Pour la conformité' : 'Aucune en attente'}</div>
          </div>
          {promoAccess && (
            <div className="card kpi">
              <div className="lbl">Promotions<br />en ligne</div>
              <span />
              <div className="val">{live}</div>
              <div className="tiny">{scheduled} programmée{scheduled > 1 ? 's' : ''}</div>
            </div>
          )}
          <div className="card pad">
            <div className="row">
              <h3 style={{ flex: 1, fontWeight: 500 }}>Demandes reçues</h3>
              <span className="badge off">7 jours</span>
            </div>
            <div className="chart" role="img"
              aria-label={`Demandes reçues : ${days.map((d) => `${d.label} ${d.count}`).join(', ')}`}>
              {days.map((d, i) => (
                <div key={i} className={`col${i === peakIndex && d.count ? ' peak' : ''}`}>
                  {i === peakIndex && d.count > 0 && (
                    <span className="tip" style={{ bottom: `calc(${(d.count / peak) * 100}% - 4px)` }}>{d.count}</span>
                  )}
                  <div className="bar" style={{ height: `${Math.max(6, (d.count / peak) * 82)}%` }} />
                  <span className="day">{d.label}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="card pad stack">
            <h3 style={{ fontWeight: 500 }}>Accès rapide</h3>
            <div className="quick">
              <a href="#/kyc"><span className="ic"><Icon name="id" size={18} /></span>File KYC</a>
              {promoAccess && <a href="#/promotions/nouvelle"><span className="ic"><Icon name="promo" size={18} /></span>Promotion</a>}
              {allowed.includes('agents') && <a href="#/agents"><span className="ic"><Icon name="agents" size={18} /></span>Agents</a>}
              {allowed.includes('journal') && <a href="#/journal"><span className="ic"><Icon name="journal" size={18} /></span>Journal</a>}
            </div>
          </div>
        </div>
      </div>
      <p className="tiny" style={{ margin: 0 }}>Connecté en tant que {agent.email}</p>
    </div>
  )
}

function Stat({ label, value, sub, tone }: { label: string; value: number | string; sub: string; tone?: 'ok' | 'alert' }) {
  return (
    <div className="card stat">
      <div className="row">
        <span className="lbl" style={{ flex: 1 }}>{label}</span>
        {tone && <span className={`badge ${tone}`}><Icon name={tone === 'ok' ? 'up' : 'down'} size={11} /></span>}
      </div>
      <div className="val">{value}</div>
      <div className="sub"><span>{sub}</span></div>
    </div>
  )
}
