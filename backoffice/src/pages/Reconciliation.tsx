import { useState, type FormEvent, type ReactNode } from 'react'
import type { Api, ReconciliationItem, ResolveAction, ReviewTopup } from '../api'
import { formatDate, formatDateTime, formatShortDate, formatUsd, kindLabel, operatorLabel, providerLabel } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

/** Décision en cours : sur une recharge à vérifier, ou sur un écart du rapprochement. */
type Pending =
  | { target: 'topup'; row: ReviewTopup; action: ResolveAction }
  | { target: 'item'; row: ReconciliationItem; action: ResolveAction }

function yesterday(now = new Date()): string {
  const d = new Date(now)
  d.setDate(d.getDate() - 1)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function Reconciliation({ api, notify }: { api: Api; notify: (m: string) => void }) {
  const review = useLoad(() => api.reviewTopups(), [])
  const items = useLoad(() => api.reconciliationItems(true), [])
  const runs = useLoad(() => api.reconciliationRuns(14), [])
  const [date, setDate] = useState(yesterday)
  const [running, setRunning] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [decisionError, setDecisionError] = useState<string | null>(null)

  const last = runs.data?.[0]

  async function run() {
    setRunning(true)
    setRunError(null)
    try {
      const r = await api.runReconciliation(date)
      notify(
        r.discrepancies === 0
          ? `Rapprochement du ${formatDate(r.businessDate)} : tout correspond`
          : `Rapprochement du ${formatDate(r.businessDate)} : ${r.discrepancies} écart${r.discrepancies > 1 ? 's' : ''} à traiter`,
      )
      runs.reload()
      items.reload()
      review.reload()
    } catch (e) {
      setRunError((e as Error).message)
    } finally {
      setRunning(false)
    }
  }

  function open(p: Pending) {
    setPending(p)
    setNote('')
    setDecisionError(null)
  }

  async function decide(e: FormEvent) {
    e.preventDefault()
    if (!pending) return
    setBusy(true)
    setDecisionError(null)
    try {
      if (pending.target === 'topup') {
        await api.resolveTopup(pending.row.id, pending.action, note.trim())
        review.reload()
      } else {
        await api.resolveItem(pending.row.id, pending.action, note.trim())
        items.reload()
      }
      notify(pending.action === 'CREDIT' ? `${pending.row.customerName} a été crédité` : 'Dossier clos')
      setPending(null)
    } catch (err) {
      setDecisionError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const decision = (key: string) =>
    pending && `${pending.target}-${pending.row.id}` === key ? (
      <tr>
        <td colSpan={5}>
          <form className="decision" onSubmit={decide}>
            <ErrorBanner error={decisionError} />
            <label className="field">
              {pending.action === 'CREDIT'
                ? "Pourquoi créditer ? Le statut est relu chez l'opérateur avant tout crédit."
                : 'Pourquoi clore ? Ce motif est gardé dans le journal.'}
              <input type="text" required maxLength={300} autoFocus value={note} onChange={(e) => setNote(e.target.value)}
                placeholder={pending.action === 'CREDIT' ? 'Ex. : paiement confirmé par M-Pesa au téléphone' : 'Ex. : remboursé par l’opérateur'} />
            </label>
            <div className="row">
              <span className="spacer" />
              <button type="button" className="btn ghost small" onClick={() => setPending(null)} disabled={busy}>Annuler</button>
              <button className={`btn small ${pending.action === 'CREDIT' ? 'success' : ''}`} disabled={busy || !note.trim()}>
                {pending.action === 'CREDIT' ? 'Créditer le client' : 'Clore le dossier'}
              </button>
            </div>
          </form>
        </td>
      </tr>
    ) : null

  return (
    <>
      <PageHead
        title="Rapprochement"
        subtitle="Chaque nuit, chaque recharge de la veille est comparée à son statut chez l'opérateur. Rien n'est corrigé sans vous."
      >
        <label className="field inline">
          <span className="small muted">Journée</span>
          <input type="date" value={date} max={yesterday(new Date(Date.now() + 86_400_000))} onChange={(e) => setDate(e.target.value)} aria-label="Journée à rapprocher" />
        </label>
        <button className="btn" onClick={run} disabled={running || !date}>
          <Icon name="scale" /> {running ? 'Rapprochement…' : 'Lancer'}
        </button>
      </PageHead>
      <div className="stack">
        <ErrorBanner error={runError ?? runs.error} />

        <div className="recon-stats">
          <div className="card stat">
            <span className="lbl">Dernier rapprochement</span>
            <span className="val">{last ? formatShortDate(last.businessDate) : '—'}</span>
            <span className="sub">{last?.finishedAt ? `terminé le ${formatDateTime(last.finishedAt)}` : 'pas encore lancé'}</span>
          </div>
          <div className="card stat">
            <span className="lbl">Recharges comparées</span>
            <span className="val mono">{last?.checked ?? 0}</span>
            <span className="sub">{last ? `${last.matched} identiques chez l'opérateur` : ' '}</span>
          </div>
          <div className={`card stat ${(items.data?.length ?? 0) > 0 ? 'warn' : ''}`}>
            <span className="lbl">Écarts à traiter</span>
            <span className="val mono">{items.data?.length ?? 0}</span>
            <span className="sub">tous jours confondus</span>
          </div>
          <div className={`card stat ${(review.data?.length ?? 0) > 0 ? 'warn' : ''}`}>
            <span className="lbl">Recharges à vérifier</span>
            <span className="val mono">{review.data?.length ?? 0}</span>
            <span className="sub">en attente d'une décision</span>
          </div>
        </div>

        <section className="card table-wrap" aria-labelledby="t-review">
          <div className="card-head">
            <h3 id="t-review">Recharges à vérifier</h3>
            <p className="muted small">Montant différent chez l'opérateur, ou aucune réponse sûre après 24 h. Le client voit « en vérification ».</p>
          </div>
          <ErrorBanner error={review.error} />
          {review.data && review.data.length === 0 ? (
            <div className="empty">Aucune recharge à vérifier.</div>
          ) : (
            <table>
              <thead>
                <tr><th>Client</th><th>Recharge</th><th>Pourquoi</th><th>Depuis</th><th /></tr>
              </thead>
              <tbody>
                {review.data?.map((t) => (
                  <ReviewRows key={t.id} t={t} onAction={(action) => open({ target: 'topup', row: t, action })}>
                    {decision(`topup-${t.id}`)}
                  </ReviewRows>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="card table-wrap" aria-labelledby="t-items">
          <div className="card-head">
            <h3 id="t-items">Écarts à traiter</h3>
            <p className="muted small">Ce que le rapprochement a trouvé de différent entre nos comptes et l'opérateur.</p>
          </div>
          <ErrorBanner error={items.error} />
          {items.data && items.data.length === 0 ? (
            <div className="empty">Aucun écart ouvert. Nos comptes correspondent à ceux des opérateurs.</div>
          ) : (
            <table>
              <thead>
                <tr><th>Client</th><th>Recharge</th><th>Écart</th><th>Journée</th><th /></tr>
              </thead>
              <tbody>
                {items.data?.map((i) => (
                  <ItemRows key={i.id} i={i} onAction={(action) => open({ target: 'item', row: i, action })}>
                    {decision(`item-${i.id}`)}
                  </ItemRows>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {runs.data && runs.data.length > 0 && (
          <section className="card table-wrap" aria-labelledby="t-runs">
            <div className="card-head"><h3 id="t-runs">Derniers rapprochements</h3></div>
            <table>
              <thead>
                <tr><th>Journée</th><th>Comparées</th><th>Identiques</th><th>Écarts</th></tr>
              </thead>
              <tbody>
                {runs.data.map((r) => (
                  <tr key={r.id}>
                    <td>{formatDate(r.businessDate)}</td>
                    <td className="mono">{r.checked}</td>
                    <td className="mono">{r.matched}</td>
                    <td>{r.discrepancies === 0 ? <span className="badge ok">0</span> : <span className="badge alert">{r.discrepancies}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </div>
    </>
  )
}

function Who({ name, phone }: { name: string; phone: string }) {
  return (
    <td>
      <div style={{ fontWeight: 600 }}>{name}</div>
      <div className="muted small mono">{phone}</div>
    </td>
  )
}

function ReviewRows({ t, onAction, children }: { t: ReviewTopup; onAction: (a: ResolveAction) => void; children: ReactNode }) {
  return (
    <>
      <tr>
        <Who name={t.customerName} phone={t.customerPhone} />
        <td>
          <div className="mono" style={{ fontWeight: 600 }}>{formatUsd(t.amountMinor)}</div>
          <div className="muted small">{operatorLabel[t.operator]} · {providerLabel(t.provider)}</div>
        </td>
        <td className="small">{t.reason ?? '—'}</td>
        <td className="small mono" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(t.createdAt)}</td>
        <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
          <button className="btn small success" onClick={() => onAction('CREDIT')}>Créditer</button>{' '}
          <button className="btn ghost small" onClick={() => onAction('CLOSE')}>Clore</button>
        </td>
      </tr>
      {children}
    </>
  )
}

function ItemRows({ i, onAction, children }: { i: ReconciliationItem; onAction: (a: ResolveAction) => void; children: ReactNode }) {
  const k = kindLabel[i.kind]
  return (
    <>
      <tr>
        <Who name={i.customerName} phone={i.customerPhone} />
        <td>
          <div className="mono" style={{ fontWeight: 600 }}>
            {formatUsd(i.ourAmountMinor)}
            {i.providerAmountMinor != null && i.providerAmountMinor !== i.ourAmountMinor && (
              <span className="muted"> · reçu {formatUsd(i.providerAmountMinor)}</span>
            )}
          </div>
          <div className="muted small">{operatorLabel[i.operator]} · {providerLabel(i.provider)}</div>
        </td>
        <td>
          <span className={`badge ${k.tone}`}>{k.title}</span>
          <div className="muted small" style={{ marginTop: 4, maxWidth: 320 }}>{k.help}</div>
        </td>
        <td className="small" style={{ whiteSpace: 'nowrap' }}>{formatDate(i.businessDate)}</td>
        <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
          {i.kind === 'CONFIRMED_NOT_CREDITED' && (
            <>
              <button className="btn small success" onClick={() => onAction('CREDIT')}>Créditer</button>{' '}
            </>
          )}
          <button className="btn ghost small" onClick={() => onAction('CLOSE')}>Clore</button>
        </td>
      </tr>
      {children}
    </>
  )
}
