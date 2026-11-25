import { useEffect, useState, type FormEvent } from 'react'
import type { Agent, Api, BackofficeCredit, CreditSettings, CreditStatus } from '../api'
import { formatDate, formatDateTime, formatUsd } from '../format'
import { ErrorBanner, PageHead, useLoad } from '../ui'

type Tab = CreditStatus | 'SETTINGS'

const tabs: { value: Tab; label: string }[] = [
  { value: 'PENDING_REVIEW', label: 'À examiner' },
  { value: 'LATE', label: 'En retard' },
  { value: 'DEFAULTED', label: 'En défaut' },
  { value: 'ACTIVE', label: 'En cours' },
  { value: 'REPAID', label: 'Remboursés' },
  { value: 'SETTINGS', label: 'Réglages' },
]

export const creditStatusLabel: Record<CreditStatus, { label: string; tone: 'ok' | 'alert' | 'wait' | 'off' }> = {
  PENDING_REVIEW: { label: 'À examiner', tone: 'wait' },
  REJECTED: { label: 'Refusé', tone: 'off' },
  ACTIVE: { label: 'À jour', tone: 'ok' },
  LATE: { label: 'En retard', tone: 'alert' },
  DEFAULTED: { label: 'En défaut', tone: 'alert' },
  WRITTEN_OFF: { label: 'Passé en perte', tone: 'off' },
  REPAID: { label: 'Remboursé', tone: 'ok' },
}

const empty: Record<CreditStatus, string> = {
  PENDING_REVIEW: 'Aucune demande à examiner.',
  LATE: 'Aucun crédit en retard.',
  DEFAULTED: 'Aucun crédit en défaut.',
  ACTIVE: 'Aucun crédit en cours.',
  REPAID: 'Aucun crédit remboursé pour l’instant.',
  REJECTED: 'Aucune demande refusée.',
  WRITTEN_OFF: 'Aucun crédit passé en perte.',
}

const percent = (bp: number) => `${(bp / 100).toLocaleString('fr-FR', { maximumFractionDigits: 2 })} %`

/** Crédit express : demandes au-dessus du seuil, crédits en retard ou en défaut, réglages. */
export function Credits({ api, me, notify }: { api: Api; me: Agent; notify: (m: string) => void }) {
  const [tab, setTab] = useState<Tab>('PENDING_REVIEW')
  return (
    <>
      <PageHead title="Crédits"
        subtitle="Crédit express de l'app. Sous le seuil de revue, l'argent est versé tout de suite ; au-dessus, vous décidez.">
        <div className="tabs" role="tablist">
          {tabs.map((t) => (
            <button key={t.value} role="tab" aria-selected={t.value === tab}
              className={`tab${t.value === tab ? ' active' : ''}`} onClick={() => setTab(t.value)}>
              {t.label}
            </button>
          ))}
        </div>
      </PageHead>
      {tab === 'SETTINGS' ? <Settings api={api} me={me} notify={notify} /> : <CreditList api={api} me={me} status={tab} notify={notify} />}
    </>
  )
}

function CreditList({ api, me, status, notify }: { api: Api; me: Agent; status: CreditStatus; notify: (m: string) => void }) {
  const list = useLoad(() => api.credits(status), [status])
  return (
    <>
      <ErrorBanner error={list.error} />
      {list.data && list.data.length === 0 && <div className="card empty">{empty[status]}</div>}
      <div className="stack">
        {list.data?.map((c) => (
          <CreditCard key={c.credit.id} c={c} me={me} api={api}
            done={(m) => { notify(m); list.reload() }} />
        ))}
      </div>
    </>
  )
}

type Action = 'approve' | 'reject' | 'reschedule' | 'writeOff'

function CreditCard({ c, me, api, done }: { c: BackofficeCredit; me: Agent; api: Api; done: (m: string) => void }) {
  const k = c.credit
  const [action, setAction] = useState<Action | null>(null)
  const [schedule, setSchedule] = useState(false)
  const s = creditStatusLabel[k.status]
  const late = k.status === 'LATE' || k.status === 'DEFAULTED'
  return (
    <section className="card" aria-labelledby={`cr-${k.id}`} style={{ padding: 20 }}>
      <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
        <h3 id={`cr-${k.id}`} style={{ margin: 0, fontSize: 17 }}>
          {c.customerName} · <span className="mono">{formatUsd(k.principalMinor)}</span> sur {k.durationMonths} mois
        </h3>
        <span className={`badge ${s.tone}`}>{s.label}</span>
        {k.rescheduleCount > 0 && <span className="badge off">Rééchelonné{k.rescheduleCount > 1 ? ` ${k.rescheduleCount} fois` : ''}</span>}
        <span className="spacer" />
        <span className="muted small">Demandé le {formatDateTime(k.createdAt)}</span>
      </div>

      <dl className="facts-grid" style={{ marginTop: 14 }}>
        <div><dt>Client</dt><dd>{c.customerName}<div className="muted small mono">{c.customerPhone} · niveau {c.kycLevel}</div></dd></div>
        <div><dt>Score</dt><dd>{k.score} sur 100 · plafond {formatUsd(c.ceilingMinor)}</dd></div>
        <div><dt>Taux</dt><dd>{percent(k.monthlyRateBp)} par mois · TEG {percent(k.aprBp)}</dd></div>
        {k.status === 'PENDING_REVIEW' ? (
          <div><dt>Coût total</dt><dd>{formatUsd(k.totalInterestMinor)} d’intérêts</dd></div>
        ) : (
          <div><dt>Capital restant</dt><dd>{formatUsd(k.outstandingPrincipalMinor)} · payé {formatUsd(k.totalPaidMinor)}</dd></div>
        )}
        {late && <div><dt>Impayé</dt><dd className="mono">{formatUsd(k.dueNowMinor)} · {k.daysLate} jour{k.daysLate > 1 ? 's' : ''} de retard</dd></div>}
        {late && <div><dt>Solde du client</dt><dd className="mono">{formatUsd(c.walletBalanceMinor)}</dd></div>}
      </dl>

      <div style={{ marginTop: 12 }}>
        <div className="muted small" style={{ marginBottom: 6 }}>Ce qui explique le score</div>
        <ul className="reasons" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {c.factors.map((f) => (
            <li key={f.label} className={`badge ${f.positive ? 'ok' : 'off'}`}>{f.positive ? '+ ' : '− '}{f.label}</li>
          ))}
        </ul>
      </div>

      {k.decisionNote && (
        <p className="small" style={{ margin: '12px 0 0' }}>
          <span className="muted">{c.decidedBy ? `Note de ${c.decidedBy}` : 'Note'}{c.decidedAt ? `, le ${formatDateTime(c.decidedAt)}` : ''} : </span>
          {k.decisionNote}
        </p>
      )}

      {k.instalments.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <button className="link-btn small" aria-expanded={schedule} onClick={() => setSchedule((o) => !o)}>
            {schedule ? 'Masquer l’échéancier' : `Voir l’échéancier (${k.instalments.length} échéances)`}
          </button>
          {schedule && <Schedule c={c} />}
        </div>
      )}

      {action ? (
        <DecisionForm action={action} c={c} onCancel={() => setAction(null)}
          send={async (note, months) => {
            if (action === 'approve') await api.approveCredit(k.id, note)
            if (action === 'reject') await api.rejectCredit(k.id, note)
            if (action === 'reschedule') await api.rescheduleCredit(k.id, months!, note)
            if (action === 'writeOff') await api.writeOffCredit(k.id, note)
            done({
              approve: `Crédit de ${c.customerName} approuvé et versé`,
              reject: `Refus envoyé à ${c.customerName}`,
              reschedule: `Nouvel échéancier envoyé à ${c.customerName}`,
              writeOff: `Crédit de ${c.customerName} passé en perte`,
            }[action])
          }} />
      ) : (
        <div className="row" style={{ marginTop: 14, gap: 8 }}>
          <span className="spacer" />
          {k.status === 'PENDING_REVIEW' && (
            <>
              <button className="btn ghost small" onClick={() => setAction('reject')}>Refuser</button>
              <button className="btn small success" onClick={() => setAction('approve')}>Approuver et verser</button>
            </>
          )}
          {late && <button className="btn small" onClick={() => setAction('reschedule')}>Rééchelonner</button>}
          {k.status === 'DEFAULTED' && me.role === 'ADMIN' && (
            <button className="btn small danger" onClick={() => setAction('writeOff')}>Passer en perte</button>
          )}
        </div>
      )}
    </section>
  )
}

function Schedule({ c }: { c: BackofficeCredit }) {
  const label = { UPCOMING: 'À venir', LATE: 'En retard', PAID: 'Payée', CANCELLED: 'Remplacée' }
  return (
    <div className="table-wrap" style={{ marginTop: 8 }}>
      <table aria-label={`Échéancier de ${c.customerName}`}>
        <thead>
          <tr><th>N°</th><th>Date</th><th>Montant</th><th>Dont intérêts</th><th>Payé</th><th>État</th></tr>
        </thead>
        <tbody>
          {c.credit.instalments.map((i) => (
            <tr key={i.number}>
              <td>{i.number}</td>
              <td>{formatDate(i.dueDate)}</td>
              <td className="mono">{formatUsd(i.amountMinor)}</td>
              <td className="mono">{formatUsd(i.interestMinor)}</td>
              <td className="mono">{formatUsd(i.paidMinor)}</td>
              <td>{label[i.status]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const forms: Record<Action, { title: string; field: string; placeholder: string; submit: string; required: boolean; tone: string }> = {
  approve: {
    title: 'Approuver', field: 'Note interne (facultative)', placeholder: 'Ex. : paie régulière de la mine, vérifiée sur ses relevés.',
    submit: 'Approuver et verser', required: false, tone: 'success',
  },
  reject: {
    title: 'Refuser', field: 'Explication au client (il la lit telle quelle)',
    placeholder: 'Ex. : vos revenus sont à confirmer. Passez à l’agence avec vos trois dernières fiches de paie.',
    submit: 'Envoyer le refus', required: true, tone: 'danger',
  },
  reschedule: {
    title: 'Rééchelonner', field: 'Accord trouvé avec le client',
    placeholder: 'Ex. : la cliente a perdu son emploi en octobre ; elle reprend à la paie de décembre.',
    submit: 'Envoyer le nouvel échéancier', required: true, tone: '',
  },
  writeOff: {
    title: 'Passer en perte', field: 'Décision du comité de crédit', placeholder: 'Ex. : comité du 25/11, recouvrement sans suite depuis 90 jours.',
    submit: 'Passer en perte', required: true, tone: 'danger',
  },
}

function DecisionForm({ action, c, onCancel, send }: {
  action: Action
  c: BackofficeCredit
  onCancel: () => void
  send: (note: string, months?: number) => Promise<void>
}) {
  const f = forms[action]
  const [note, setNote] = useState('')
  const [months, setMonths] = useState(6)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await send(note.trim(), action === 'reschedule' ? months : undefined)
    } catch (err) {
      setError((err as Error).message)
      setBusy(false)
    }
  }

  return (
    <form className="decision" onSubmit={submit} style={{ marginTop: 14 }} aria-label={f.title}>
      <ErrorBanner error={error} />
      {action === 'reschedule' && (
        <label className="field">
          Nouvelle durée
          <select value={months} onChange={(e) => setMonths(Number(e.target.value))}>
            {[3, 6, 9, 12, 18, 24].map((m) => <option key={m} value={m}>{m} mois</option>)}
          </select>
          <span className="muted small">
            Le capital restant ({formatUsd(c.credit.outstandingPrincipalMinor)}) et les intérêts en retard sont étalés à partir d’aujourd’hui.
          </span>
        </label>
      )}
      {action === 'writeOff' && (
        <p className="banner alert small" role="note">
          {formatUsd(c.credit.outstandingPrincipalMinor)} de capital passent en perte. Le client ne pourra plus avoir de crédit express.
        </p>
      )}
      <label className="field">
        {f.field}
        <textarea maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={f.placeholder} />
      </label>
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn ghost small" onClick={onCancel} disabled={busy}>Annuler</button>
        <button className={`btn small ${f.tone}`} disabled={busy || (f.required && !note.trim())}>{f.submit}</button>
      </div>
    </form>
  )
}

/** Réglages du crédit. Les agents de crédit les lisent ; seul un administrateur les change. */
function Settings({ api, me, notify }: { api: Api; me: Agent; notify: (m: string) => void }) {
  const loaded = useLoad(() => api.creditSettings(), [])
  const [form, setForm] = useState<Record<string, string> | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const admin = me.role === 'ADMIN'

  useEffect(() => {
    const s = loaded.data
    if (!s) return
    setForm({
      minScore: String(s.minScore),
      reviewThreshold: String(s.reviewThresholdMinor / 100),
      minAmount: String(s.minAmountMinor / 100),
      maxAmount: String(s.maxAmountMinor / 100),
      monthlyRate: String(s.monthlyRateBp / 100),
      durations: s.durationsMonths.join(', '),
      ceilingPercent: String(s.ceilingPercentOfInflow),
      defaultAfterDays: String(s.defaultAfterDays),
    })
  }, [loaded.data])

  if (!form) return <ErrorBanner error={loaded.error} />
  const set = (k: string) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })
  const cents = (v: string) => Math.round(Number(v.replace(',', '.')) * 100)

  async function save(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const s: CreditSettings = {
        minScore: Number(form!.minScore),
        reviewThresholdMinor: cents(form!.reviewThreshold),
        minAmountMinor: cents(form!.minAmount),
        maxAmountMinor: cents(form!.maxAmount),
        monthlyRateBp: cents(form!.monthlyRate),
        durationsMonths: form!.durations.split(/[,; ]+/).filter(Boolean).map(Number),
        ceilingPercentOfInflow: Number(form!.ceilingPercent),
        defaultAfterDays: Number(form!.defaultAfterDays),
      }
      await api.saveCreditSettings(s)
      notify('Réglages du crédit enregistrés')
      loaded.reload()
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const field = (k: string, label: string, help: string, suffix?: string) => (
    <label className="field" key={k}>
      {label}{suffix ? ` (${suffix})` : ''}
      <input value={form[k]} onChange={set(k)} readOnly={!admin} inputMode="decimal" required />
      <span className="muted small">{help}</span>
    </label>
  )

  return (
    <form className="card pad stack" onSubmit={save} aria-label="Réglages du crédit">
      <ErrorBanner error={error} />
      {!admin && <p className="banner info small" role="note">Seul un administrateur peut changer ces réglages.</p>}
      <p className="muted small" style={{ margin: 0 }}>
        Valeurs provisoires, à fixer par la direction générale et le comité de crédit.
        {loaded.data?.updatedAt ? ` Dernière modification le ${formatDateTime(loaded.data.updatedAt)}.` : ''}
      </p>
      {field('minScore', 'Score minimal', 'En dessous, pas d’offre : le client voit ses leviers pour progresser.', 'sur 100')}
      {field('reviewThreshold', 'Seuil de revue', 'Au-dessus, la demande arrive ici avant tout versement.', '$')}
      {field('minAmount', 'Montant minimal', 'Plus petit crédit proposé.', '$')}
      {field('maxAmount', 'Montant maximal', 'Plafond pour tous les clients, quel que soit le score.', '$')}
      {field('monthlyRate', 'Taux d’intérêt', 'Par mois, sur le capital restant dû.', '% par mois')}
      {field('durations', 'Durées proposées', 'En mois, séparées par des virgules.', 'mois')}
      {field('ceilingPercent', 'Part des entrées', 'Le plafond d’un client vaut cette part de l’argent reçu par mois.', '%')}
      {field('defaultAfterDays', 'Passage en défaut', 'Jours de retard sur la plus vieille échéance.', 'jours')}
      {admin && (
        <div className="row">
          <span className="spacer" />
          <button className="btn small" disabled={busy}>Enregistrer</button>
        </div>
      )}
    </form>
  )
}
