import { useEffect, useState, type FormEvent } from 'react'
import QRCode from 'qrcode'
import type { Agent, Api, Merchant, MerchantPayout, Operator } from '../api'
import { formatDateTime, formatUsd, operatorLabel } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

const operators: Operator[] = ['MPESA', 'AIRTEL', 'ORANGE']

/** Opérateur probable d'après le préfixe du numéro (+243 81… M-Pesa, 97… Airtel, 84… Orange). */
export function operatorForPhone(phone: string): Operator | null {
  const digits = phone.replace(/\D/g, '')
  const local = digits.startsWith('243') ? digits.slice(3) : digits.replace(/^0/, '')
  const p = local.slice(0, 2)
  if (['81', '82', '83'].includes(p)) return 'MPESA'
  if (['97', '98', '99'].includes(p)) return 'AIRTEL'
  if (['80', '84', '85', '89'].includes(p)) return 'ORANGE'
  return null
}

/** Montant tapé (« 49,50 ») en centimes, ou null. */
function parseUsd(text: string): number | null {
  const t = text.trim().replace(/\s/g, '').replace(',', '.')
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return null
  const minor = Math.round(Number(t) * 100)
  return minor > 0 ? minor : null
}

const payoutStatus: Record<MerchantPayout['status'], { label: string; cls: string }> = {
  PENDING: { label: 'En cours', cls: 'wait' },
  CONFIRMED: { label: 'Versé', cls: 'ok' },
  FAILED: { label: 'Refusé, revenu sur le compte', cls: 'off' },
  REVIEW: { label: 'À vérifier', cls: 'wait' },
}

const percent = (bps: number) => `${(bps / 100).toLocaleString('fr-FR', { maximumFractionDigits: 2 })} %`

/**
 * Marchands qui acceptent le paiement par QR. L'administrateur crée le commerce, imprime l'affiche
 * avec son QR pour la caisse, et peut le suspendre. La conformité consulte.
 */
export function Merchants({ api, me, id, open, notify }: {
  api: Api
  me: Agent
  id: string | null
  open: (id: string | null) => void
  notify: (m: string) => void
}) {
  const { data, error, reload } = useLoad(() => api.merchants(), [])
  const [form, setForm] = useState(false)
  const admin = me.role === 'ADMIN'

  return (
    <>
      <PageHead title="Marchands" subtitle="Commerces qui acceptent le paiement par QR. Le client ne paie aucun frais ; le marchand paie une commission sur chaque paiement.">
        {admin && !form && (
          <button className="btn ochre" onClick={() => setForm(true)}>
            <Icon name="plus" /> Nouveau marchand
          </button>
        )}
      </PageHead>
      <div className="stack">
        {form && (
          <MerchantForm api={api} cancel={() => setForm(false)} created={(m) => {
            notify(`${m.name} peut maintenant être payé. Imprimez son affiche.`)
            setForm(false)
            reload()
            open(m.id)
          }} />
        )}
        <ErrorBanner error={error} />
        <div className={id ? 'history-split' : undefined}>
          <div className="card table-wrap">
            {data && data.length === 0 ? (
              <div className="empty">Aucun marchand pour l'instant.{admin ? ' Créez le premier pour imprimer son QR.' : ''}</div>
            ) : (
              <table>
                <thead>
                  <tr><th>Marchand</th><th className="hide-sm">Ville</th><th className="hide-sm">Commission</th><th style={{ textAlign: 'right' }}>Dû au marchand</th><th>État</th></tr>
                </thead>
                <tbody>
                  {data?.map((m) => (
                    <tr key={m.id} className={`link${id === m.id ? ' selected' : ''}`} tabIndex={0}
                      onClick={() => open(m.id)} onKeyDown={(e) => e.key === 'Enter' && open(m.id)}>
                      <td>
                        <div style={{ fontWeight: 600 }}>{m.name}</div>
                        <div className="muted small mono">{m.code}{m.category ? ` · ${m.category}` : ''}</div>
                      </td>
                      <td className="hide-sm">{m.city ?? '—'}</td>
                      <td className="hide-sm mono">{percent(m.commissionBps)}</td>
                      <td className="mono" style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{formatUsd(m.balanceMinor)}</td>
                      <td>{m.status === 'ACTIVE' ? <span className="badge ok">Actif</span> : <span className="badge off">Suspendu</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          {id && <MerchantPanel api={api} id={id} admin={admin} close={() => open(null)} changed={(m) => { notify(m); reload() }} />}
        </div>
      </div>
    </>
  )
}

function MerchantForm({ api, cancel, created }: { api: Api; cancel: () => void; created: (m: Merchant) => void }) {
  const [name, setName] = useState('')
  const [category, setCategory] = useState('')
  const [city, setCity] = useState('')
  const [phone, setPhone] = useState('')
  const [commission, setCommission] = useState('1')
  const [payoutOperator, setPayoutOperator] = useState<Operator | ''>('')
  const [payoutPhone, setPayoutPhone] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const pct = Number(commission.replace(',', '.'))
    if (!Number.isFinite(pct) || pct < 0 || pct > 10) {
      setError('La commission doit être entre 0 et 10 %')
      return
    }
    setBusy(true)
    setError(null)
    try {
      created(await api.createMerchant({
        name: name.trim(), category: category.trim() || null, city: city.trim() || null, phone: phone.trim(),
        commissionBps: Math.round(pct * 100),
        payoutOperator: payoutOperator || null,
        payoutPhone: payoutOperator ? (payoutPhone.trim() || phone.trim()) : null,
      }))
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card pad stack" onSubmit={submit}>
      <h2>Nouveau marchand</h2>
      <ErrorBanner error={error} />
      <div className="grid-2">
        <label className="field">
          Nom du commerce <span className="hint">Le client le voit avant de payer</span>
          <input type="text" required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          Numéro qui reçoit les SMS <span className="hint">Le caissier y voit chaque paiement reçu</span>
          <input type="tel" required placeholder="+243 99…" value={phone} onChange={(e) => setPhone(e.target.value)} />
        </label>
        <label className="field">
          Activité
          <input type="text" maxLength={60} placeholder="Alimentation, pharmacie…" value={category} onChange={(e) => setCategory(e.target.value)} />
        </label>
        <label className="field">
          Ville
          <input type="text" maxLength={60} value={city} onChange={(e) => setCity(e.target.value)} />
        </label>
        <label className="field">
          Commission (%) <span className="hint">Retenue sur chaque paiement, de 0 à 10 %</span>
          <input type="text" inputMode="decimal" required value={commission} onChange={(e) => setCommission(e.target.value)} />
        </label>
        <label className="field">
          Reversements vers <span className="hint">Compte mobile money qui reçoit l'argent du marchand</span>
          <select value={payoutOperator} onChange={(e) => {
            setPayoutOperator(e.target.value as Operator | '')
            if (e.target.value && !payoutPhone) setPayoutPhone(phone)
          }}>
            <option value="">Plus tard</option>
            {operators.map((o) => <option key={o} value={o}>{operatorLabel[o]}</option>)}
          </select>
        </label>
        {payoutOperator && (
          <label className="field">
            Numéro {operatorLabel[payoutOperator]} des reversements
            <input type="tel" required value={payoutPhone} onChange={(e) => setPayoutPhone(e.target.value)} />
          </label>
        )}
      </div>
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn ghost" onClick={cancel} disabled={busy}>Annuler</button>
        <button className="btn" disabled={busy}>Créer le marchand</button>
      </div>
    </form>
  )
}

/** Code QR en SVG, net à toutes les tailles et à l'impression. */
function Qr({ payload, label }: { payload: string; label: string }) {
  const [svg, setSvg] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    QRCode.toString(payload, { type: 'svg', margin: 1, errorCorrectionLevel: 'M', color: { dark: '#1c1a17', light: '#ffffff' } })
      .then((s) => live && setSvg(s), () => live && setSvg(null))
    return () => {
      live = false
    }
  }, [payload])
  return <div className="qr" role="img" aria-label={label} dangerouslySetInnerHTML={svg ? { __html: svg } : undefined} />
}

function MerchantPanel({ api, id, admin, close, changed }: {
  api: Api
  id: string
  admin: boolean
  close: () => void
  changed: (message: string) => void
}) {
  const { data, error, reload } = useLoad(() => api.merchant(id), [id])
  const [reason, setReason] = useState('')
  const [asking, setAsking] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const m = data?.merchant

  useEffect(() => {
    setAsking(false)
    setReason('')
    setActionError(null)
  }, [id])

  async function setStatus(e?: FormEvent) {
    e?.preventDefault()
    if (!m) return
    const next = m.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
    setActionError(null)
    try {
      await api.setMerchantStatus(m.id, next, reason.trim())
      setAsking(false)
      setReason('')
      reload()
      changed(next === 'SUSPENDED' ? `${m.name} est suspendu : ses clients ne peuvent plus le payer` : `${m.name} est réactivé`)
    } catch (err) {
      setActionError((err as Error).message)
    }
  }

  return (
    <aside className="card pad receipt" aria-label="Fiche marchand">
      <div className="row">
        <h3 style={{ margin: 0 }}>{m?.name ?? 'Marchand'}</h3>
        <span className="spacer" />
        <button type="button" className="icon-btn" onClick={close} aria-label="Fermer la fiche"><Icon name="close" size={18} /></button>
      </div>
      <ErrorBanner error={error ?? actionError} />
      {m && (
        <>
          {/* L'affiche : seule partie de la page qui sort à l'impression. */}
          <div className="poster">
            <div className="poster-brand"><span className="logo">CI</span> Capital Invest</div>
            <div className="poster-name">{m.name}</div>
            <Qr payload={m.qrPayload} label={`QR de paiement de ${m.name}`} />
            <div className="poster-code mono" aria-label="Code marchand">{m.code.slice(0, 3)} {m.code.slice(3)}</div>
            <div className="poster-help">Payez ici avec l'app Capital Invest : touchez <b>Payer</b>, scannez ce code, confirmez par votre PIN. Sans frais.</div>
          </div>
          <div className="row">
            <button type="button" className="btn small" onClick={() => window.print()}>
              <Icon name="print" size={16} /> Imprimer l'affiche
            </button>
            {m.status === 'SUSPENDED' && <span className="badge off">Suspendu</span>}
          </div>
          <dl>
            <div><dt>Dû au marchand</dt><dd className="mono">{formatUsd(m.balanceMinor)}</dd></div>
            <div><dt>Commission</dt><dd>{percent(m.commissionBps)}</dd></div>
            <div><dt>SMS des paiements</dt><dd className="mono">{m.phone}</dd></div>
            {m.category && <div><dt>Activité</dt><dd>{m.category}</dd></div>}
            {m.city && <div><dt>Ville</dt><dd>{m.city}</dd></div>}
            <div><dt>Créé le</dt><dd>{formatDateTime(m.createdAt)}</dd></div>
          </dl>
          <Payouts api={api} merchant={m} payouts={data.payouts} admin={admin}
            done={(message) => { reload(); changed(message) }} />
          <h4 style={{ margin: '10px 0 0' }}>Derniers paiements</h4>
          {data.payments.length === 0 ? (
            <div className="muted small">Aucun paiement reçu pour l'instant.</div>
          ) : (
            <ul className="pay-list" aria-label="Derniers paiements">
              {data.payments.map((p) => (
                <li key={p.transactionId}>
                  <div>
                    <div style={{ fontWeight: 600 }}>{p.customerName}</div>
                    <div className="muted mono">{p.receiptNumber}</div>
                    <div className="muted">{formatDateTime(p.createdAt)}</div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className="mono" style={{ fontWeight: 600 }}>{formatUsd(p.amountMinor)}</div>
                    <div className="muted">commission {formatUsd(p.commissionMinor)}</div>
                  </div>
                </li>
              ))}
            </ul>
          )}
          {admin && (asking ? (
            <form className="stack" onSubmit={setStatus} style={{ marginTop: 8 }}>
              <label className="field">
                Motif de la suspension
                <input type="text" required maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
              </label>
              <div className="row">
                <span className="spacer" />
                <button type="button" className="btn ghost small" onClick={() => setAsking(false)}>Annuler</button>
                <button className="btn small">Suspendre</button>
              </div>
            </form>
          ) : (
            <button type="button" className="btn ghost small" style={{ marginTop: 8, alignSelf: 'flex-start' }}
              onClick={() => (m.status === 'ACTIVE' ? setAsking(true) : setStatus())}>
              {m.status === 'ACTIVE' ? 'Suspendre le marchand' : 'Réactiver le marchand'}
            </button>
          ))}
        </>
      )}
    </aside>
  )
}

/** Reversements : compte qui les reçoit, bouton pour reverser ce qui est dû, et suivi chez l'opérateur. */
function Payouts({ api, merchant: m, payouts, admin, done }: {
  api: Api
  merchant: Merchant
  payouts: MerchantPayout[]
  admin: boolean
  done: (message: string) => void
}) {
  const [editing, setEditing] = useState(false)
  const [operator, setOperator] = useState<Operator>(m.payoutOperator ?? operatorForPhone(m.phone) ?? 'MPESA')
  const [phone, setPhone] = useState(m.payoutPhone ?? m.phone)
  const [paying, setPaying] = useState(false)
  const [amount, setAmount] = useState('')
  const [requestId, setRequestId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setEditing(false)
    setPaying(false)
    setError(null)
  }, [m.id])

  async function saveAccount(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const updated = await api.setPayoutAccount(m.id, operator, phone.trim())
      setEditing(false)
      done(updated.payoutBlockedUntil
        ? 'Compte de reversement changé. Le marchand est prévenu par SMS ; reversements possibles dans 24 h.'
        : 'Compte de reversement enregistré')
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  function startPayout() {
    setAmount((m.balanceMinor / 100).toFixed(2).replace('.', ','))
    setRequestId(crypto.randomUUID())
    setError(null)
    setPaying(true)
  }

  async function pay(e: FormEvent) {
    e.preventDefault()
    const minor = parseUsd(amount)
    if (minor === null) return setError('Montant invalide, par exemple 49,50')
    if (minor > m.balanceMinor) return setError(`Le marchand n'a que ${formatUsd(m.balanceMinor)} à reverser`)
    setBusy(true)
    setError(null)
    try {
      const p = await api.payoutMerchant(m.id, minor, requestId)
      setPaying(false)
      done(p.status === 'FAILED'
        ? `${operatorLabel[p.operator]} a refusé le versement : l'argent reste sur le compte du marchand`
        : `Reversement de ${formatUsd(p.amountMinor)} envoyé vers ${operatorLabel[p.operator]} ${p.phone}`)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const blocked = m.payoutBlockedUntil
  return (
    <section className="stack" aria-label="Reversements" style={{ gap: 8, marginTop: 10 }}>
      <h4 style={{ margin: 0 }}>Reversements</h4>
      <ErrorBanner error={error} />
      {editing ? (
        <form className="stack" onSubmit={saveAccount} style={{ gap: 8 }}>
          <label className="field">
            Opérateur
            <select value={operator} onChange={(e) => setOperator(e.target.value as Operator)}>
              {operators.map((o) => <option key={o} value={o}>{operatorLabel[o]}</option>)}
            </select>
          </label>
          <label className="field">
            Numéro qui reçoit les reversements
            <input type="tel" required value={phone} onChange={(e) => {
              setPhone(e.target.value)
              const guess = operatorForPhone(e.target.value)
              if (guess) setOperator(guess)
            }} />
          </label>
          {m.payoutPhone && <div className="muted small">Le marchand sera prévenu par SMS, et aucun reversement ne partira pendant 24 h.</div>}
          <div className="row">
            <span className="spacer" />
            <button type="button" className="btn ghost small" onClick={() => setEditing(false)} disabled={busy}>Annuler</button>
            <button className="btn small" disabled={busy}>Enregistrer</button>
          </div>
        </form>
      ) : (
        <div className="row" style={{ fontSize: 13 }}>
          <span className="muted">Vers</span>
          <b>{m.payoutOperator ? `${operatorLabel[m.payoutOperator]} ${m.payoutPhone}` : 'aucun compte renseigné'}</b>
          <span className="spacer" />
          {admin && (
            <button type="button" className="btn ghost small" onClick={() => setEditing(true)}>
              {m.payoutOperator ? 'Modifier' : 'Renseigner'}
            </button>
          )}
        </div>
      )}
      {blocked && (
        <div className="banner info" role="note">
          <Icon name="clock" />
          <span>Compte changé récemment : reversements possibles à partir du {formatDateTime(blocked)}.</span>
        </div>
      )}
      {admin && !editing && (paying ? (
        <form className="stack" onSubmit={pay} style={{ gap: 8 }}>
          <label className="field">
            Montant à reverser ($)
            <input type="text" inputMode="decimal" required value={amount} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <div className="muted small">
            Vers {m.payoutOperator && operatorLabel[m.payoutOperator]} {m.payoutPhone}. L'argent est bloqué dès maintenant ; s'il n'est pas versé, il revient sur le compte du marchand.
          </div>
          <div className="row">
            <span className="spacer" />
            <button type="button" className="btn ghost small" onClick={() => setPaying(false)} disabled={busy}>Annuler</button>
            <button className="btn small" disabled={busy}>{busy ? 'Envoi…' : 'Confirmer le reversement'}</button>
          </div>
        </form>
      ) : (
        <button type="button" className="btn small" style={{ alignSelf: 'flex-start' }}
          disabled={!m.payoutOperator || !!blocked || m.balanceMinor < 100} onClick={startPayout}>
          Reverser {formatUsd(m.balanceMinor)}
        </button>
      ))}
      {payouts.length > 0 && (
        <ul className="pay-list" aria-label="Reversements au marchand">
          {payouts.map((p) => (
            <li key={p.id}>
              <div>
                <div className="mono" style={{ fontWeight: 600 }}>{formatUsd(p.amountMinor)}</div>
                <div className="muted">{operatorLabel[p.operator]} {p.phone}</div>
                <div className="muted">{formatDateTime(p.createdAt)}</div>
                {(p.failureMessage || p.reviewReason) && <div className="muted">{p.failureMessage ?? p.reviewReason}</div>}
              </div>
              <div><span className={`badge ${payoutStatus[p.status].cls}`}>{payoutStatus[p.status].label}</span></div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
