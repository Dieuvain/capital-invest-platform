import { useEffect, useState, type FormEvent } from 'react'
import QRCode from 'qrcode'
import type { Agent, Api, Merchant } from '../api'
import { formatDateTime, formatUsd } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

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
