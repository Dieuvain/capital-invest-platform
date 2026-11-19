import { useState, type FormEvent, type ReactNode } from 'react'
import type { Api, PendingPurchase, ReconciliationItem, ReviewTopup } from '../api'
import { formatDate, formatDateTime, formatShortDate, formatUsd, kindLabel, operatorLabel, providerLabel } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

/** Une décision proposée sur une ligne : ce qu'on demande, le bouton, et l'appel au serveur. */
interface Choice {
  label: string
  /** Bouton vert : l'argent va au client ou part chez l'opérateur. */
  positive: boolean
  prompt: string
  placeholder: string
  confirm: string
  done: string
  /** Demande aussi le code du compteur (facture SNEL prépayée). */
  token?: boolean
  send: (note: string, token: string) => Promise<unknown>
  reload: 'review' | 'items' | 'purchases'
}

type Pending = { key: string; choice: Choice }

const kindOf = { TOPUP: 'Recharge', WITHDRAWAL: 'Retrait' } as const

function yesterday(now = new Date()): string {
  const d = new Date(now)
  d.setDate(d.getDate() - 1)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function Reconciliation({ api, notify }: { api: Api; notify: (m: string) => void }) {
  const review = useLoad(() => api.reviewTopups(), [])
  const items = useLoad(() => api.reconciliationItems(true), [])
  const purchases = useLoad(() => api.pendingPurchases(), [])
  const runs = useLoad(() => api.reconciliationRuns(14), [])
  const [date, setDate] = useState(yesterday)
  const [running, setRunning] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const [note, setNote] = useState('')
  const [token, setToken] = useState('')
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
    setToken('')
    setDecisionError(null)
  }

  async function decide(e: FormEvent) {
    e.preventDefault()
    if (!pending) return
    setBusy(true)
    setDecisionError(null)
    try {
      await pending.choice.send(note.trim(), token.trim())
      ;({ review, items, purchases })[pending.choice.reload].reload()
      notify(pending.choice.done)
      setPending(null)
    } catch (err) {
      setDecisionError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const decision = (key: string) =>
    pending?.key === key ? (
      <tr>
        <td colSpan={5}>
          <form className="decision" onSubmit={decide}>
            <ErrorBanner error={decisionError} />
            {pending.choice.token && (
              <label className="field">
                Code du compteur donné par l'agrégateur (20 chiffres)
                <input type="text" inputMode="numeric" required autoFocus value={token} onChange={(e) => setToken(e.target.value)}
                  placeholder="1234 5678 9012 3456 7890" className="mono" />
              </label>
            )}
            <label className="field">
              {pending.choice.prompt}
              <input type="text" required maxLength={300} autoFocus={!pending.choice.token} value={note} onChange={(e) => setNote(e.target.value)}
                placeholder={pending.choice.placeholder} />
            </label>
            <div className="row">
              <span className="spacer" />
              <button type="button" className="btn ghost small" onClick={() => setPending(null)} disabled={busy}>Annuler</button>
              <button className={`btn small ${pending.choice.positive ? 'success' : ''}`} disabled={busy || !note.trim() || (!!pending.choice.token && token.replace(/\D/g, '').length !== 20)}>
                {pending.choice.confirm}
              </button>
            </div>
          </form>
        </td>
      </tr>
    ) : null

  const reviewChoices = (t: ReviewTopup): Choice[] =>
    t.type === 'TOPUP'
      ? [
          {
            label: 'Créditer', positive: true, confirm: 'Créditer le client', reload: 'review',
            prompt: "Pourquoi créditer ? Le statut est relu chez l'opérateur : on crédite le montant réellement encaissé.",
            placeholder: 'Ex. : paiement confirmé par M-Pesa au téléphone', done: `${t.customerName} a été crédité`,
            send: (n) => api.resolveTopup(t.id, 'CREDIT', n),
          },
          {
            label: 'Clore', positive: false, confirm: 'Clore le dossier', reload: 'review',
            prompt: 'Pourquoi clore ? Ce motif est gardé dans le journal.', placeholder: 'Ex. : paiement jamais arrivé',
            done: 'Dossier clos', send: (n) => api.resolveTopup(t.id, 'CLOSE', n),
          },
        ]
      : [
          {
            label: 'Noter versé', positive: true, confirm: 'Noter le retrait versé', reload: 'review',
            prompt: "Pourquoi noter versé ? L'opérateur doit confirmer le versement, relu à l'instant.",
            placeholder: 'Ex. : le client confirme avoir reçu l’argent', done: 'Retrait noté versé',
            send: (n) => api.resolveWithdrawal(t.id, 'PAID', n),
          },
          {
            label: 'Rembourser', positive: false, confirm: 'Rembourser le client', reload: 'review',
            prompt: "Pourquoi rembourser ? L'opérateur doit confirmer l'échec, sinon le client serait payé deux fois.",
            placeholder: 'Ex. : échec confirmé par Airtel', done: `${t.customerName} a été remboursé`,
            send: (n) => api.resolveWithdrawal(t.id, 'REFUND', n),
          },
        ]

  const purchaseChoices = (p: PendingPurchase): Choice[] => [
    {
      label: p.type === 'AIRTIME' ? 'Noter envoyé' : 'Noter payé', positive: true, reload: 'purchases', token: p.needsToken,
      confirm: p.type === 'AIRTIME' ? 'Noter le crédit envoyé' : 'Noter la facture payée',
      prompt: "Comment l'avez-vous vérifié ? Le client garde la dépense.",
      placeholder: p.type === 'AIRTIME' ? 'Ex. : unités confirmées par le prestataire' : 'Ex. : paiement confirmé par l’agrégateur',
      done: p.type === 'AIRTIME' ? 'Crédit noté envoyé' : 'Facture notée payée, le client reçoit le code',
      send: (n, t) => api.resolvePurchase(p, 'DONE', n, p.needsToken ? t : undefined),
    },
    {
      label: 'Rembourser', positive: false, reload: 'purchases', confirm: 'Rembourser le client',
      prompt: "Pourquoi rembourser ? Le prestataire doit confirmer l'échec : si les unités ou le paiement sont passés, l'argent serait perdu.",
      placeholder: 'Ex. : échec confirmé par le prestataire', done: `${p.customerName} a été remboursé`,
      send: (n) => api.resolvePurchase(p, 'REFUND', n),
    },
  ]

  const itemChoices = (i: ReconciliationItem): Choice[] => [
    ...(i.kind === 'CONFIRMED_NOT_CREDITED' || i.kind === 'MARKED_PAID_NOT_PAID'
      ? [{
          label: i.type === 'TOPUP' ? 'Créditer' : 'Rembourser', positive: true, reload: 'items' as const,
          confirm: i.type === 'TOPUP' ? 'Créditer le client' : 'Rembourser le client',
          prompt: "Pourquoi ? Le statut est relu chez l'opérateur avant de rendre l'argent au client.",
          placeholder: 'Ex. : confirmé par l’opérateur au téléphone',
          done: `${i.customerName} a été ${i.type === 'TOPUP' ? 'crédité' : 'remboursé'}`,
          send: (n: string) => api.resolveItem(i.id, 'CREDIT', n),
        }]
      : []),
    {
      label: 'Clore', positive: false, confirm: 'Clore le dossier', reload: 'items',
      prompt: 'Pourquoi clore ? Ce motif est gardé dans le journal.', placeholder: 'Ex. : corrigé avec la finance',
      done: 'Dossier clos', send: (n) => api.resolveItem(i.id, 'CLOSE', n),
    },
  ]

  return (
    <>
      <PageHead
        title="Rapprochement"
        subtitle="Chaque nuit, chaque recharge et chaque retrait de la veille est comparé à son statut chez l'opérateur. Rien n'est corrigé sans vous."
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
            <span className="lbl">À vérifier</span>
            <span className="val mono">{review.data?.length ?? 0}</span>
            <span className="sub">en attente d'une décision</span>
          </div>
        </div>

        <section className="card table-wrap" aria-labelledby="t-review">
          <div className="card-head">
            <h3 id="t-review">Recharges et retraits à vérifier</h3>
            <p className="muted small">Montant différent chez l'opérateur, ou aucune réponse sûre après 24 h. Le client voit « en vérification ».</p>
          </div>
          <ErrorBanner error={review.error} />
          {review.data && review.data.length === 0 ? (
            <div className="empty">Rien à vérifier.</div>
          ) : (
            <table>
              <thead>
                <tr><th>Client</th><th>Opération</th><th>Pourquoi</th><th>Depuis</th><th /></tr>
              </thead>
              <tbody>
                {review.data?.map((t) => (
                  <Row key={t.id} choices={reviewChoices(t)} onPick={(choice) => open({ key: `r-${t.id}`, choice })}
                    cells={<ReviewCells t={t} />}>
                    {decision(`r-${t.id}`)}
                  </Row>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="card table-wrap" aria-labelledby="t-purchases">
          <div className="card-head">
            <h3 id="t-purchases">Crédit téléphone et factures sans réponse</h3>
            <p className="muted small">Relancés chaque minute chez le prestataire, puis à trancher après 24 h. Le client voit « en cours », puis « en vérification ».</p>
          </div>
          <ErrorBanner error={purchases.error} />
          {purchases.data && purchases.data.length === 0 ? (
            <div className="empty">Aucun achat en attente.</div>
          ) : (
            <table>
              <thead>
                <tr><th>Client</th><th>Achat</th><th>Où en est-on</th><th>Depuis</th><th /></tr>
              </thead>
              <tbody>
                {purchases.data?.map((p) => (
                  <Row key={p.id} choices={purchaseChoices(p)} onPick={(choice) => open({ key: `p-${p.id}`, choice })}
                    cells={<PurchaseCells p={p} />}>
                    {decision(`p-${p.id}`)}
                  </Row>
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
                <tr><th>Client</th><th>Opération</th><th>Écart</th><th>Journée</th><th /></tr>
              </thead>
              <tbody>
                {items.data?.map((i) => (
                  <Row key={i.id} choices={itemChoices(i)} onPick={(choice) => open({ key: `i-${i.id}`, choice })}
                    cells={<ItemCells i={i} />}>
                    {decision(`i-${i.id}`)}
                  </Row>
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

/** Une ligne, ses boutons de décision, et le formulaire de motif juste en dessous quand il est ouvert. */
function Row({ cells, choices, onPick, children }: {
  cells: ReactNode
  choices: Choice[]
  onPick: (c: Choice) => void
  children: ReactNode
}) {
  return (
    <>
      <tr>
        {cells}
        <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
          {choices.map((c) => (
            <button key={c.label} className={`btn small ${c.positive ? 'success' : 'ghost'}`} style={{ marginLeft: 6 }}
              onClick={() => onPick(c)}>
              {c.label}
            </button>
          ))}
        </td>
      </tr>
      {children}
    </>
  )
}

function Operation({ type, amount, operator, provider, extra }: {
  type: keyof typeof kindOf
  amount: number
  operator: ReviewTopup['operator']
  provider: string
  extra?: ReactNode
}) {
  return (
    <td>
      <div className="mono" style={{ fontWeight: 600 }}>
        <span className={`badge ${type === 'TOPUP' ? 'off' : 'wait'}`} style={{ marginRight: 8 }}>{kindOf[type]}</span>
        {formatUsd(amount)}
        {extra}
      </div>
      <div className="muted small" style={{ marginTop: 4 }}>{operatorLabel[operator]} · {providerLabel(provider)}</div>
    </td>
  )
}

function ReviewCells({ t }: { t: ReviewTopup }) {
  return (
    <>
      <Who name={t.customerName} phone={t.customerPhone} />
      <Operation type={t.type} amount={t.amountMinor} operator={t.operator} provider={t.provider} />
      <td className="small">{t.reason ?? '—'}</td>
      <td className="small mono" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(t.createdAt)}</td>
    </>
  )
}

function PurchaseCells({ p }: { p: PendingPurchase }) {
  return (
    <>
      <Who name={p.customerName} phone={p.customerPhone} />
      <td>
        <div className="mono" style={{ fontWeight: 600 }}>
          <span className={`badge ${p.type === 'AIRTIME' ? 'off' : 'wait'}`} style={{ marginRight: 8 }}>
            {p.type === 'AIRTIME' ? 'Crédit' : 'Facture'}
          </span>
          {formatUsd(p.amountMinor)}
        </div>
        <div className="muted small" style={{ marginTop: 4 }}>
          {p.label} · <span className="mono">{p.target}</span>{p.holderName ? ` · ${p.holderName}` : ''} · {providerLabel(p.provider)}
        </div>
      </td>
      <td className="small">
        {p.status === 'REVIEW'
          ? <><span className="badge alert">À trancher</span><div className="muted" style={{ marginTop: 4 }}>{p.reason}</div></>
          : <><span className="badge wait">Relances en cours</span><div className="muted" style={{ marginTop: 4 }}>{p.checks} relance{p.checks > 1 ? 's' : ''} sans réponse</div></>}
      </td>
      <td className="small mono" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(p.createdAt)}</td>
    </>
  )
}

function ItemCells({ i }: { i: ReconciliationItem }) {
  const k = kindLabel[i.kind]
  return (
    <>
      <Who name={i.customerName} phone={i.customerPhone} />
      <Operation type={i.type} amount={i.ourAmountMinor} operator={i.operator} provider={i.provider}
        extra={i.providerAmountMinor != null && i.providerAmountMinor !== i.ourAmountMinor && (
          <span className="muted"> · opérateur {formatUsd(i.providerAmountMinor)}</span>
        )} />
      <td>
        <span className={`badge ${k.tone}`}>{k.title}</span>
        <div className="muted small" style={{ marginTop: 4, maxWidth: 320 }}>{k.help}</div>
      </td>
      <td className="small" style={{ whiteSpace: 'nowrap' }}>{formatDate(i.businessDate)}</td>
    </>
  )
}
