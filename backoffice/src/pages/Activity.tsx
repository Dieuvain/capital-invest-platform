import { useState } from 'react'
import type { Activity, ActivityDay, ActivityTotals, Api, Flow } from '../api'
import { formatShortDate, formatUsd } from '../format'
import { ErrorBanner, PageHead, useLoad } from '../ui'

const periods = [7, 30, 90] as const
type Period = (typeof periods)[number]

/** Les familles d'opérations, dans l'ordre où l'argent circule : il entre, il circule, il sort. */
const families = [
  { key: 'topups', label: 'Rechargements', hint: 'Mobile money vers Capital Invest' },
  { key: 'transfers', label: 'Envois entre clients', hint: "L'argent reste chez Capital Invest" },
  { key: 'merchantPayments', label: 'Paiements marchands', hint: 'QR et code marchand' },
  { key: 'billsAndAirtime', label: 'Factures et crédit', hint: 'SNEL, REGIDESO, TV, crédit téléphone' },
  { key: 'withdrawals', label: 'Retraits', hint: 'Capital Invest vers mobile money' },
] as const
type FamilyKey = (typeof families)[number]['key']

const number = new Intl.NumberFormat('fr-FR')
const count = (n: number) => number.format(n).replace(/\s/g, ' ')

/** Évolution par rapport à la période précédente : « +12 % », « −3 % », ou rien si on part de zéro. */
export function change(now: number, before: number): { text: string; tone: 'ok' | 'alert' | 'off' } | null {
  if (before === 0) return now === 0 ? null : { text: 'nouveau', tone: 'ok' }
  const pct = Math.round(((now - before) / before) * 100)
  if (pct === 0) return { text: '=', tone: 'off' }
  return { text: `${pct > 0 ? '+' : '−'}${Math.abs(pct)} %`, tone: pct > 0 ? 'ok' : 'alert' }
}

function Change({ now, before }: { now: number; before: number }) {
  const c = change(now, before)
  if (!c) return null
  return <span className={`badge ${c.tone}`} title="Par rapport à la période précédente">{c.text}</span>
}

/** Volume total qui a bougé dans la journée, toutes familles confondues. */
const volume = (d: ActivityTotals) => families.reduce((s, f) => s + d[f.key].amountMinor, 0)

export function ActivityPage({ api }: { api: Api }) {
  const [days, setDays] = useState<Period>(30)
  const { data, error } = useLoad(() => api.activity(days), [days])
  const [metric, setMetric] = useState<FamilyKey | 'all'>('all')

  return (
    <>
      <PageHead
        title="Activité"
        subtitle={
          data
            ? `Du ${formatShortDate(data.from)} au ${formatShortDate(data.to)}, comparé aux ${days} jours d'avant. Heure de Lubumbashi.`
            : 'Chiffres tirés du grand livre, heure de Lubumbashi.'
        }
      >
        <div className="tabs" role="tablist" aria-label="Période">
          {periods.map((p) => (
            <button key={p} role="tab" aria-selected={p === days} className={`tab${p === days ? ' active' : ''}`} onClick={() => setDays(p)}>
              {p} jours
            </button>
          ))}
        </div>
      </PageHead>
      <div className="stack" style={{ gap: 18 }}>
        <ErrorBanner error={error} />
        {!data ? (
          !error && <div className="empty">Chargement…</div>
        ) : (
          <>
            <Overview data={data} />
            <Chart data={data} metric={metric} setMetric={setMetric} />
            <Families totals={data.totals} previous={data.previous} />
          </>
        )}
      </div>
    </>
  )
}

function Overview({ data }: { data: Activity }) {
  const t = data.totals
  const p = data.previous
  return (
    <div className="activity-top">
      <div className="hero">
        <div className="top"><span>Argent des clients</span></div>
        <div className="big">{formatUsd(data.customerHoldingsMinor)}</div>
        <div className="foot">Portefeuilles et épargne, aujourd'hui, {count(data.customersActive)} comptes ouverts</div>
      </div>
      <Stat label="Clients actifs" value={count(t.activeCustomers)} sub="au moins une opération" now={t.activeCustomers} before={p.activeCustomers} />
      <Stat label="Nouveaux clients" value={count(t.newCustomers)} sub="comptes créés" now={t.newCustomers} before={p.newCustomers} />
      <Stat label="Argent qui a circulé" value={formatUsd(volume(t))} sub="toutes opérations" now={volume(t)} before={volume(p)} />
      <Stat label="Frais encaissés" value={formatUsd(t.feesMinor)} sub="remboursements déduits" now={t.feesMinor} before={p.feesMinor} />
    </div>
  )
}

function Stat({ label, value, sub, now, before }: { label: string; value: string; sub: string; now: number; before: number }) {
  return (
    <div className="card stat">
      <div className="lbl">{label}</div>
      <div className="val activity-val">{value}</div>
      <div className="sub">
        <span>{sub}</span>
        <Change now={now} before={before} />
      </div>
    </div>
  )
}

function Chart({ data, metric, setMetric }: { data: Activity; metric: FamilyKey | 'all'; setMetric: (m: FamilyKey | 'all') => void }) {
  const value = (d: ActivityDay) => (metric === 'all' ? volume(d) : d[metric].amountMinor)
  const values = data.days.map(value)
  const peak = Math.max(1, ...values)
  const peakIndex = values.indexOf(Math.max(...values))
  const many = data.days.length > 31
  const label = metric === 'all' ? 'Toutes opérations' : families.find((f) => f.key === metric)!.label
  // Une étiquette tous les 7 jours au-delà d'un mois, sinon toutes les 5 : la date du jour toujours.
  const step = many ? 14 : data.days.length > 7 ? 5 : 1
  return (
    <div className="card pad stack">
      <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
        <h3 style={{ flex: 1, fontWeight: 500, minWidth: 160 }}>Montant par jour</h3>
        {(['all', ...families.map((f) => f.key)] as const).map((k) => (
          <button key={k} type="button" className={`chip${metric === k ? ' on' : ''}`} aria-pressed={metric === k} onClick={() => setMetric(k)}>
            {k === 'all' ? 'Tout' : families.find((f) => f.key === k)!.label}
          </button>
        ))}
      </div>
      <div className={`chart activity-chart${many ? ' dense' : ''}`} role="img"
        aria-label={`${label}, montant par jour : ${data.days.map((d, i) => `${formatShortDate(d.date)} ${formatUsd(values[i])}`).join(', ')}`}>
        {data.days.map((d, i) => {
          const last = i === data.days.length - 1
          return (
            <div key={d.date} className={`col${i === peakIndex && values[i] ? ' peak' : ''}`} title={`${formatShortDate(d.date)} : ${formatUsd(values[i])}`}>
              {i === peakIndex && values[i] > 0 && (
                <span className="tip" style={{ bottom: `calc(${(values[i] / peak) * 82}% + 22px)` }}>{formatUsd(values[i])}</span>
              )}
              <div className="bar" style={{ height: `${Math.max(2, (values[i] / peak) * 82)}%` }} />
              <span className="day">{last ? 'Auj.' : (data.days.length - 1 - i) % step === 0 ? formatShortDate(d.date) : ' '}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function Families({ totals, previous }: { totals: ActivityTotals; previous: ActivityTotals }) {
  return (
    <div className="card table-wrap">
      <div className="row" style={{ padding: '18px 16px 4px' }}>
        <h2 style={{ flex: 1 }}>Par type d'opération</h2>
      </div>
      <table>
        <thead>
          <tr>
            <th>Opération</th>
            <th className="num">Nombre</th>
            <th className="num">Montant</th>
            <th className="num hide-sm">Montant moyen</th>
            <th className="num">Évolution</th>
          </tr>
        </thead>
        <tbody>
          {families.map((f) => {
            const now: Flow = totals[f.key]
            const before: Flow = previous[f.key]
            return (
              <tr key={f.key}>
                <td>
                  {f.label}
                  <div className="tiny">{f.hint}</div>
                </td>
                <td className="num">{count(now.count)}</td>
                <td className="num">{formatUsd(now.amountMinor)}</td>
                <td className="num hide-sm">{now.count ? formatUsd(Math.round(now.amountMinor / now.count)) : '-'}</td>
                <td className="num"><Change now={now.amountMinor} before={before.amountMinor} /></td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <p className="tiny" style={{ padding: '4px 16px 16px', margin: 0 }}>
        Montants sans les frais. Un retrait ou une facture remboursé vient en déduction du jour du remboursement.
      </p>
    </div>
  )
}
