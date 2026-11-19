import { useState, type FormEvent } from 'react'
import type { Api, BackofficeComplaint, Complaint, ComplaintOutcome } from '../api'
import { formatDateTime, formatSigned, historyTitle, waitingFor } from '../format'
import { ErrorBanner, PageHead, useLoad } from '../ui'

const tabs: { status: Complaint['status']; label: string }[] = [
  { status: 'OPEN', label: 'À traiter' },
  { status: 'ANSWERED', label: 'Répondues' },
]

const outcomes: { value: ComplaintOutcome; label: string; help: string }[] = [
  { value: 'FOUNDED', label: 'Fondée', help: 'Le client a raison. Corrigez son solde depuis le rapprochement si de l’argent est en jeu.' },
  { value: 'UNFOUNDED', label: 'Non fondée', help: 'L’opération était correcte. Expliquez au client ce que vous avez vérifié.' },
]

/** « Reçue à l'instant », « Reçue il y a 3 h ». */
const received = (iso: string) => {
  const w = waitingFor(iso)
  return w === '0 min' ? 'Reçue à l’instant' : `Reçue il y a ${w}`
}

/** File des réclamations : les urgentes et les plus proches de l'échéance d'abord. */
export function Complaints({ api, notify, openCustomer }: {
  api: Api
  notify: (m: string) => void
  openCustomer: (id: string) => void
}) {
  const [status, setStatus] = useState<Complaint['status']>('OPEN')
  const list = useLoad(() => api.complaints(status), [status])
  const [openId, setOpenId] = useState<string | null>(null)

  const late = list.data?.filter((c) => c.overdue).length ?? 0
  return (
    <>
      <PageHead title="Réclamations"
        subtitle="Ce que les clients signalent depuis un reçu ou leur compte. Ils reçoivent votre réponse dans l'app et par SMS.">
        <div className="tabs" role="tablist">
          {tabs.map((t) => (
            <button key={t.status} role="tab" aria-selected={t.status === status}
              className={`tab${t.status === status ? ' active' : ''}`} onClick={() => { setStatus(t.status); setOpenId(null) }}>
              {t.label}
            </button>
          ))}
        </div>
      </PageHead>
      <ErrorBanner error={list.error} />
      {status === 'OPEN' && late > 0 && (
        <p className="badge alert" role="note" style={{ marginBottom: 12 }}>
          {late} réclamation{late > 1 ? 's' : ''} en retard sur le délai promis au client
        </p>
      )}
      {list.data && list.data.length === 0 && (
        <div className="card empty">{status === 'OPEN' ? 'Aucune réclamation en attente.' : 'Aucune réponse pour l’instant.'}</div>
      )}
      <div className="stack">
        {list.data?.map((c) => (
          <ComplaintCard key={c.complaint.id} c={c} openCustomer={openCustomer}
            answering={openId === c.complaint.id} onAnswer={() => setOpenId(c.complaint.id)} onCancel={() => setOpenId(null)}
            send={async (outcome, answer) => {
              await api.answerComplaint(c.complaint.id, outcome, answer)
              notify(`Réponse envoyée à ${c.customerName}`)
              setOpenId(null)
              list.reload()
            }} />
        ))}
      </div>
    </>
  )
}

function ComplaintCard({ c, openCustomer, answering, onAnswer, onCancel, send }: {
  c: BackofficeComplaint
  openCustomer: (id: string) => void
  answering: boolean
  onAnswer: () => void
  onCancel: () => void
  send: (outcome: ComplaintOutcome, answer: string) => Promise<void>
}) {
  const k = c.complaint
  const op = k.operation
  return (
    <section className="card" aria-labelledby={`c-${k.id}`} style={{ padding: 20 }}>
      <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
        <h3 id={`c-${k.id}`} className="mono" style={{ margin: 0, fontSize: 16 }}>{k.reference}</h3>
        {c.urgent && k.status === 'OPEN' && <span className="badge alert">Urgent : vol de compte possible</span>}
        {c.overdue && <span className="badge alert">En retard</span>}
        <span className="badge off">{k.categoryLabel}</span>
        <span className="spacer" />
        <span className="muted small">
          {k.status === 'OPEN' ? `${received(k.createdAt)} · réponse due le ${formatDateTime(k.dueAt)}` : `Reçue le ${formatDateTime(k.createdAt)}`}
        </span>
      </div>

      <div className="row" style={{ marginTop: 12, gap: 24, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <div>
          <div className="muted small">Client</div>
          <button className="link-btn" onClick={() => openCustomer(c.customerId)}>{c.customerName}</button>
          <div className="muted small mono">{c.customerPhone}</div>
        </div>
        <div>
          <div className="muted small">Opération</div>
          {op ? (
            <>
              <div style={{ fontWeight: 600 }}>{historyTitle(op)} · <span className="mono">{formatSigned(op.netMinor)}</span></div>
              <div className="muted small mono">{op.receiptNumber} · {formatDateTime(op.createdAt)}</div>
            </>
          ) : (
            <div className="muted">Le compte en général</div>
          )}
        </div>
      </div>

      <blockquote style={{ margin: '14px 0 0', padding: '10px 14px', borderLeft: '3px solid var(--line)' }}>« {k.message} »</blockquote>

      {k.status === 'ANSWERED' ? (
        <div style={{ marginTop: 14 }}>
          <div className="muted small">
            <span className={`badge ${k.outcome === 'FOUNDED' ? 'ok' : 'off'}`} style={{ marginRight: 8 }}>
              {k.outcome === 'FOUNDED' ? 'Fondée' : 'Non fondée'}
            </span>
            Réponse de {c.answeredByName}, le {formatDateTime(k.answeredAt!)}
          </div>
          <p style={{ margin: '8px 0 0' }}>{k.answer}</p>
        </div>
      ) : answering ? (
        <AnswerForm onCancel={onCancel} send={send} />
      ) : (
        <div className="row" style={{ marginTop: 14 }}>
          <span className="spacer" />
          <button className="btn small" onClick={onAnswer}>Répondre</button>
        </div>
      )}
    </section>
  )
}

function AnswerForm({ onCancel, send }: { onCancel: () => void; send: (o: ComplaintOutcome, a: string) => Promise<void> }) {
  const [outcome, setOutcome] = useState<ComplaintOutcome | null>(null)
  const [answer, setAnswer] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!outcome) return
    setBusy(true)
    setError(null)
    try {
      await send(outcome, answer.trim())
    } catch (err) {
      setError((err as Error).message)
      setBusy(false)
    }
  }

  return (
    <form className="decision" onSubmit={submit} style={{ marginTop: 14 }}>
      <ErrorBanner error={error} />
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="small" style={{ fontWeight: 600, marginBottom: 6 }}>Conclusion</legend>
        {outcomes.map((o) => (
          <label key={o.value} className="small" style={{ display: 'block', marginBottom: 6 }}>
            <input type="radio" name="outcome" value={o.value} checked={outcome === o.value} onChange={() => setOutcome(o.value)} />{' '}
            <strong>{o.label}</strong> <span className="muted">{o.help}</span>
          </label>
        ))}
      </fieldset>
      <label className="field">
        Réponse au client (il la lit telle quelle)
        <textarea required maxLength={1000} value={answer} onChange={(e) => setAnswer(e.target.value)}
          placeholder="Ex. : l'argent est bien arrivé sur le compte du destinataire le 19/11 à 10:02." />
      </label>
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn ghost small" onClick={onCancel} disabled={busy}>Annuler</button>
        <button className="btn small success" disabled={busy || !outcome || !answer.trim()}>Envoyer la réponse</button>
      </div>
    </form>
  )
}
