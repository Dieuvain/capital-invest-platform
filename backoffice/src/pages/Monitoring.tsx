import { useState, type FormEvent, type ReactNode } from 'react'
import type { Api, MonitoringAlert } from '../api'
import { formatDateTime } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

type Pending = { id: string; action: 'DISMISS' | 'SUSPEND' }

const statusLabel: Record<MonitoringAlert['status'], { label: string; cls: string }> = {
  OPEN: { label: 'À traiter', cls: 'wait' },
  DISMISSED: { label: 'Sans suite', cls: 'off' },
  ESCALATED: { label: 'Compte suspendu', cls: 'alert' },
}

/**
 * Surveillance des opérations : le serveur repère les comportements à risque (rafales, montants juste sous
 * le plafond, compte qui collecte, retrait aussitôt l'argent reçu). La conformité regarde les opérations
 * du client, puis classe l'alerte sans suite ou suspend le compte, avec une note pour le dossier.
 */
export function Monitoring({ api, openCustomer, notify }: {
  api: Api
  openCustomer: (id: string) => void
  notify: (m: string) => void
}) {
  const [tab, setTab] = useState<'OPEN' | 'CLOSED'>('OPEN')
  const list = useLoad(() => api.alerts(tab), [tab])
  const [pending, setPending] = useState<Pending | null>(null)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function open(p: Pending) {
    setPending(p)
    setNote('')
    setError(null)
  }

  async function decide(e: FormEvent) {
    e.preventDefault()
    if (!pending) return
    setBusy(true)
    setError(null)
    try {
      const a = await api.resolveAlert(pending.id, pending.action, note.trim())
      notify(pending.action === 'SUSPEND' ? `Compte de ${a.customerName} suspendu` : 'Alerte classée sans suite')
      setPending(null)
      list.reload()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const alerts = list.data
  return (
    <>
      <PageHead title="Surveillance" subtitle="Comportements à risque repérés après chaque opération. Le client n'est jamais prévenu : ne lui en parlez pas.">
        <div className="row" role="group" aria-label="Alertes">
          {(['OPEN', 'CLOSED'] as const).map((t) => (
            <button key={t} type="button" className={`chip${tab === t ? ' on' : ''}`} aria-pressed={tab === t}
              onClick={() => { setTab(t); setPending(null) }}>
              {t === 'OPEN' ? 'À traiter' : 'Traitées'}
            </button>
          ))}
        </div>
      </PageHead>
      <ErrorBanner error={list.error} />
      <section className="card table-wrap" aria-label={tab === 'OPEN' ? 'Alertes à traiter' : 'Alertes traitées'}>
        {alerts && alerts.length === 0 ? (
          <div className="empty">{tab === 'OPEN' ? 'Aucune alerte à traiter.' : 'Aucune alerte traitée pour le moment.'}</div>
        ) : (
          <table>
            <thead>
              <tr><th>Client</th><th>Ce qui a été repéré</th><th>{tab === 'OPEN' ? 'Décision' : 'Décision prise'}</th></tr>
            </thead>
            <tbody>
              {alerts?.map((a) => (
                <AlertRow key={a.id} a={a} openCustomer={openCustomer} pending={pending?.id === a.id ? pending : null}
                  choose={(action) => open({ id: a.id, action })}>
                  {pending?.id === a.id && (
                    <form className="decision" onSubmit={decide}>
                      <ErrorBanner error={error} />
                      <label className="field">
                        {pending.action === 'SUSPEND'
                          ? 'Pourquoi suspendre ? Le client ne pourra plus se connecter ni faire bouger son argent.'
                          : "Pourquoi classer sans suite ? Ce qui explique l'activité du client."}
                        <input type="text" required minLength={5} maxLength={500} autoFocus value={note}
                          onChange={(e) => setNote(e.target.value)}
                          placeholder={pending.action === 'SUSPEND' ? 'Ex. collecte pour des tiers, déclaration à la CENAREF' : 'Ex. commerçant connu, recettes du marché'} />
                      </label>
                      <div className="row">
                        <span className="spacer" />
                        <button type="button" className="btn ghost small" onClick={() => setPending(null)} disabled={busy}>Annuler</button>
                        <button className={`btn small${pending.action === 'SUSPEND' ? ' danger' : ''}`} disabled={busy || note.trim().length < 5}>
                          {pending.action === 'SUSPEND' ? 'Suspendre le compte' : 'Classer sans suite'}
                        </button>
                      </div>
                    </form>
                  )}
                </AlertRow>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  )
}

function AlertRow({ a, openCustomer, pending, choose, children }: {
  a: MonitoringAlert
  openCustomer: (id: string) => void
  pending: Pending | null
  choose: (action: 'DISMISS' | 'SUSPEND') => void
  children: ReactNode
}) {
  const s = statusLabel[a.status]
  return (
    <>
      <tr>
        <td style={{ verticalAlign: 'top' }}>
          <button type="button" className="link-btn" onClick={() => openCustomer(a.customerId)}>{a.customerName}</button>
          <div className="muted small mono">{a.customerPhone}</div>
          <div className="muted small">
            Niveau {a.kycLevel}{a.customerStatus !== 'ACTIVE' && ' · '}
            {a.customerStatus !== 'ACTIVE' && <span className="badge alert">suspendu</span>}
          </div>
        </td>
        <td style={{ verticalAlign: 'top' }}>
          <div className="row" style={{ gap: 6, alignItems: 'flex-start', flexWrap: 'nowrap' }}>
            <span style={{ flexShrink: 0, display: 'grid', marginTop: 1 }}><Icon name="alert" size={16} /></span>
            <b>{a.ruleLabel}</b>
          </div>
          <div className="small">{a.detail}</div>
          <div className="muted small">{formatDateTime(a.createdAt)}</div>
        </td>
        <td style={{ verticalAlign: 'top' }}>
          {a.status === 'OPEN' ? (
            !pending && (
              <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                <button type="button" className="btn ghost small" onClick={() => choose('DISMISS')}>Sans suite</button>
                <button type="button" className="btn ghost small" onClick={() => choose('SUSPEND')}>Suspendre</button>
              </div>
            )
          ) : (
            <>
              <span className={`badge ${s.cls}`}>{s.label}</span>
              <div className="small">{a.resolutionNote}</div>
              <div className="muted small">{a.resolvedByName}{a.resolvedAt && `, ${formatDateTime(a.resolvedAt)}`}</div>
            </>
          )}
        </td>
      </tr>
      {pending && (
        <tr><td colSpan={3}>{children}</td></tr>
      )}
    </>
  )
}
