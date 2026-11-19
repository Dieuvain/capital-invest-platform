import { useEffect, useState, type FormEvent } from 'react'
import type { Api, CustomerSummary, HistoryCategory, HistoryItem, Receipt } from '../api'
import { formatDateTime, formatSigned, formatUsd, historyStatus, historyTitle, operatorLabel } from '../format'
import { ErrorBanner, Icon, PageHead } from '../ui'

const filters: { value: HistoryCategory | null; label: string }[] = [
  { value: null, label: 'Tout' },
  { value: 'TOPUP', label: 'Recharges' },
  { value: 'TRANSFER', label: 'Envois' },
  { value: 'WITHDRAWAL', label: 'Retraits' },
]

/**
 * Fiche client pour le service client et la conformité : on retrouve le client par son numéro,
 * on lit ses opérations et on ouvre le reçu dont il parle. Chaque recherche est tracée.
 */
export function Customers({ api, id, open }: { api: Api; id: string | null; open: (id: string | null) => void }) {
  const [phone, setPhone] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [customer, setCustomer] = useState<CustomerSummary | null>(null)

  useEffect(() => {
    if (!id) {
      setCustomer(null)
      return
    }
    if (customer?.id === id) return
    api.customer(id).then(setCustomer, (e: Error) => setSearchError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, id])

  async function search(e: FormEvent) {
    e.preventDefault()
    setSearching(true)
    setSearchError(null)
    try {
      const c = await api.findCustomer(phone.trim())
      setCustomer(c)
      open(c.id)
    } catch (err) {
      setSearchError((err as Error).message)
    } finally {
      setSearching(false)
    }
  }

  return (
    <>
      <PageHead title="Clients" subtitle="Retrouvez un client par son numéro pour répondre à une réclamation. Chaque consultation est inscrite au journal d'audit.">
        <form className="row" onSubmit={search} role="search">
          <input type="tel" placeholder="+243 97…" value={phone} onChange={(e) => setPhone(e.target.value)}
            aria-label="Numéro du client" style={{ width: 220 }} required />
          <button className="btn" disabled={searching || !phone.trim()}>
            {searching ? 'Recherche…' : 'Rechercher'}
          </button>
        </form>
      </PageHead>
      <div className="stack">
        <ErrorBanner error={searchError} />
        {!customer && !searchError && (
          <div className="card empty">Entrez le numéro du client, au format +243… ou 0970….</div>
        )}
        {customer && <CustomerFile api={api} customer={customer} />}
      </div>
    </>
  )
}

function CustomerFile({ api, customer }: { api: Api; customer: CustomerSummary }) {
  const [category, setCategory] = useState<HistoryCategory | null>(null)
  const [items, setItems] = useState<HistoryItem[] | null>(null)
  const [next, setNext] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)

  async function load(cursor: string | null, cat: HistoryCategory | null) {
    setLoading(true)
    setError(null)
    try {
      const page = await api.customerHistory(customer.id, cat, cursor)
      setItems((prev) => (cursor ? [...(prev ?? []), ...page.items] : page.items))
      setNext(page.nextCursor)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    setItems(null)
    setSelected(null)
    load(null, category)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customer.id, category])

  return (
    <>
      <div className="recon-stats">
        <div className="card stat">
          <span className="lbl">Client</span>
          <span className="val" style={{ fontSize: 20 }}>{customer.fullName}</span>
          <span className="sub mono">{customer.phone}</span>
        </div>
        <div className="card stat">
          <span className="lbl">Solde</span>
          <span className="val mono">{formatUsd(customer.balanceMinor)}</span>
          <span className="sub">au moment de la recherche</span>
        </div>
        <div className="card stat">
          <span className="lbl">Niveau</span>
          <span className="val mono">{customer.kycLevel}</span>
          <span className="sub">{customer.status === 'ACTIVE' ? 'compte actif' : `compte ${customer.status.toLowerCase()}`}</span>
        </div>
      </div>
      <div className={selected ? 'history-split' : undefined}>
        <section className="card table-wrap" aria-labelledby="t-history">
          <div className="card-head row">
            <h3 id="t-history">Opérations</h3>
            <span className="spacer" />
            <div className="row" role="group" aria-label="Type d'opération">
              {filters.map((f) => (
                <button key={f.label} type="button" className={`chip${category === f.value ? ' on' : ''}`}
                  aria-pressed={category === f.value} onClick={() => setCategory(f.value)}>
                  {f.label}
                </button>
              ))}
            </div>
          </div>
          <ErrorBanner error={error} />
          {items && items.length === 0 ? (
            <div className="empty">Aucune opération{category ? ' de ce type' : ''}.</div>
          ) : (
            <table>
              <thead>
                <tr><th>Date</th><th>Opération</th><th className="hide-sm">N° de reçu</th><th style={{ textAlign: 'right' }}>Effet sur le solde</th></tr>
              </thead>
              <tbody>
                {items?.map((i) => {
                  const s = historyStatus[i.status]
                  return (
                    <tr key={i.id} className={`link${selected === i.id ? ' selected' : ''}`} tabIndex={0}
                      onClick={() => setSelected(i.id)} onKeyDown={(e) => e.key === 'Enter' && setSelected(i.id)}>
                      <td className="small mono" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(i.createdAt)}</td>
                      <td>
                        <div style={{ fontWeight: 600 }}>{historyTitle(i)}</div>
                        {s && <span className={`badge ${s.cls}`}>{s.label}</span>}
                      </td>
                      <td className="small mono hide-sm">{i.receiptNumber}</td>
                      <td className="mono" style={{ textAlign: 'right', whiteSpace: 'nowrap', fontWeight: 600, color: i.netMinor > 0 ? 'var(--success)' : undefined }}>
                        {formatSigned(i.netMinor)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
          {next && (
            <div className="foot-links">
              <span className="muted">{items?.length} opérations affichées</span>
              <button type="button" className="btn ghost small" onClick={() => load(next, category)} disabled={loading}>
                {loading ? 'Chargement…' : 'Voir plus'}
              </button>
            </div>
          )}
        </section>
        {selected && <ReceiptPanel api={api} customerId={customer.id} id={selected} close={() => setSelected(null)} />}
      </div>
    </>
  )
}

function ReceiptPanel({ api, customerId, id, close }: { api: Api; customerId: string; id: string; close: () => void }) {
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    setReceipt(null)
    setError(null)
    api.customerReceipt(customerId, id).then(setReceipt, (e: Error) => setError(e.message))
  }, [api, customerId, id])

  const i = receipt?.item
  const status = i ? historyStatus[i.status] : null
  const rows: [string, string][] = i
    ? [
        ['N° de reçu', i.receiptNumber],
        ['Date', formatDateTime(i.createdAt)],
        ...(i.counterpartyName ? [[i.kind === 'TRANSFER_RECEIVED' ? 'Expéditeur' : 'Destinataire', i.counterpartyName] as [string, string]] : []),
        ...(i.counterpartyPhone ? [['Numéro', i.counterpartyPhone] as [string, string]] : []),
        ...(i.operator ? [['Opérateur', operatorLabel[i.operator]] as [string, string]] : []),
        ...(i.msisdn && i.kind === 'WITHDRAWAL' ? [['Envoyé vers', i.msisdn] as [string, string]] : []),
        ['Montant', formatUsd(i.amountMinor)],
        ...(i.feeMinor ? [[i.kind === 'WITHDRAWAL_REFUND' ? 'Frais rendus' : 'Frais', formatUsd(i.feeMinor)] as [string, string]] : []),
        ...(i.note ? [['Message', i.note] as [string, string]] : []),
        ...(status ? [['État', status.label] as [string, string]] : []),
        ...(i.failureMessage ? [['Motif', i.failureMessage] as [string, string]] : []),
        ['Titulaire', `${receipt.holderName} · ${receipt.holderPhone}`],
      ]
    : []
  return (
    <aside className="card pad receipt" aria-label="Reçu">
      <div className="row">
        <h3 style={{ margin: 0 }}>Reçu</h3>
        <span className="spacer" />
        <button type="button" className="icon-btn" onClick={close} aria-label="Fermer le reçu"><Icon name="close" size={18} /></button>
      </div>
      <ErrorBanner error={error} />
      {i && (
        <>
          <div className="muted small">{historyTitle(i)}</div>
          <div className="receipt-amount mono" style={{ color: i.netMinor > 0 ? 'var(--success)' : undefined }}>{formatSigned(i.netMinor)}</div>
          <dl>
            {rows.map(([k, v]) => (
              <div key={k}><dt>{k}</dt><dd>{v}</dd></div>
            ))}
          </dl>
        </>
      )}
    </aside>
  )
}
