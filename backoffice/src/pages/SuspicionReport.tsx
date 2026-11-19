import { useEffect, useState, type FormEvent } from 'react'
import type { Api, SuspicionReport as Report } from '../api'
import { formatDate, formatDateTime, formatSigned, formatUsd, historyTitle } from '../format'
import { ErrorBanner, Icon, PageHead } from '../ui'

const idTypeLabel: Record<string, string> = { VOTER_CARD: "Carte d'électeur", PASSPORT: 'Passeport' }

function todayIso(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * Dossier de déclaration de soupçon : identité du client, faits, analyse, alertes et opérations de la
 * période. La conformité le rédige, l'imprime, le transmet à la CENAREF par le canal officiel, puis note
 * la date et la référence reçue. Le dossier est alors figé.
 */
export function SuspicionReportPage({ api, id, back, notify }: {
  api: Api
  id: string
  back: () => void
  notify: (m: string) => void
}) {
  const [report, setReport] = useState<Report | null>(null)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [facts, setFacts] = useState('')
  const [analysis, setAnalysis] = useState('')
  const [submittedOn, setSubmittedOn] = useState(todayIso)
  const [reference, setReference] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function show(r: Report) {
    setReport(r)
    setFrom(r.periodFrom)
    setTo(r.periodTo)
    setFacts(r.facts)
    setAnalysis(r.analysis)
  }

  useEffect(() => {
    setReport(null)
    api.suspicionReport(id).then(show, (e: Error) => setError(e.message))
  }, [api, id])

  const r = report
  const draft = r?.status === 'DRAFT'
  const dirty = !!r && (from !== r.periodFrom || to !== r.periodTo || facts.trim() !== r.facts || analysis.trim() !== r.analysis)

  async function save(e?: FormEvent) {
    e?.preventDefault()
    setBusy(true)
    setError(null)
    try {
      show(await api.saveSuspicionReport(id, { periodFrom: from, periodTo: to, facts, analysis }))
      notify('Brouillon enregistré')
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (dirty) show(await api.saveSuspicionReport(id, { periodFrom: from, periodTo: to, facts, analysis }))
      show(await api.submitSuspicionReport(id, submittedOn, reference.trim()))
      notify('Transmission à la CENAREF notée : le dossier est figé')
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="dossier">
      <div className="no-print">
        <PageHead title="Déclaration de soupçon" back={back}
          subtitle="Dossier confidentiel pour la CENAREF. Ne jamais en parler au client.">
          {r && (
            <button type="button" className="btn ghost" onClick={() => window.print()} disabled={dirty}
              title={dirty ? 'Enregistrez avant d\'imprimer' : undefined}>
              <Icon name="print" size={16} /> Imprimer
            </button>
          )}
        </PageHead>
      </div>
      <ErrorBanner error={error} />
      {r && (
        <div className="stack" style={{ gap: 16 }}>
          <div className="print-only-title">
            <h2 style={{ margin: 0 }}>Déclaration de soupçon · Capital Invest</h2>
          </div>
          <section className="card pad" aria-label="État de la déclaration">
            <div className="row" style={{ flexWrap: 'wrap' }}>
              {draft
                ? <span className="badge wait">Brouillon</span>
                : <span className="badge ok">Transmise à la CENAREF</span>}
              <span className="muted small">
                Ouverte par {r.createdByName} le {formatDateTime(r.createdAt)}
                {!draft && ` · transmise le ${formatDate(r.submittedOn!)} par ${r.submittedByName}, référence `}
                {!draft && <b className="mono">{r.cenarefReference}</b>}
              </span>
            </div>
          </section>

          <section className="card pad" aria-label="Personne déclarée">
            <h3 style={{ marginTop: 0 }}>Personne déclarée</h3>
            <dl className="facts-grid">
              <div><dt>Nom</dt><dd>{r.subject.fullName}</dd></div>
              <div><dt>Téléphone</dt><dd className="mono">{r.subject.phone}</dd></div>
              <div><dt>Client depuis</dt><dd>{formatDateTime(r.subject.openedAt)}</dd></div>
              <div><dt>Niveau KYC</dt><dd>{r.subject.kycLevel}</dd></div>
              <div><dt>Pièce d'identité</dt><dd>{r.subject.idType ? `${idTypeLabel[r.subject.idType] ?? r.subject.idType} ${r.subject.idNumber}` : 'non vérifiée (niveau 1)'}</dd></div>
              <div><dt>Date de naissance</dt><dd>{r.subject.birthDate ? formatDate(r.subject.birthDate) : '—'}</dd></div>
              <div><dt>État du compte</dt><dd>{r.subject.status === 'ACTIVE' ? 'Actif' : r.subject.status === 'SUSPENDED' ? 'Suspendu' : r.subject.status}</dd></div>
              <div><dt>Solde</dt><dd className="mono">{formatUsd(r.subject.balanceMinor)}</dd></div>
            </dl>
          </section>

          <form className="card pad stack" onSubmit={save} aria-label="Rédaction" style={{ gap: 12 }}>
            <div className="row" style={{ flexWrap: 'wrap', gap: 12 }}>
              <label className="field">Période du
                <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} disabled={!draft} required />
              </label>
              <label className="field">au
                <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} disabled={!draft} required />
              </label>
            </div>
            <label className="field">
              Faits constatés
              <textarea className="no-print" rows={6} value={facts} onChange={(e) => setFacts(e.target.value)} readOnly={!draft} maxLength={8000}
                placeholder="Ce qui s'est passé : dates, montants, personnes, opérations." />
            </label>
            <div className="print-text">{facts}</div>
            <label className="field">
              Analyse : pourquoi c'est suspect
              <textarea className="no-print" rows={6} value={analysis} onChange={(e) => setAnalysis(e.target.value)} readOnly={!draft} maxLength={8000}
                placeholder="Ce qui ne colle pas avec le profil du client, ce qu'il a répondu, les recherches faites." />
            </label>
            <div className="print-text">{analysis}</div>
            {draft && (
              <div className="row no-print">
                <span className="muted small">{dirty ? 'Modifications non enregistrées. Les opérations se mettent à jour à l\'enregistrement.' : 'Enregistré.'}</span>
                <span className="spacer" />
                <button className="btn small" disabled={busy || !dirty}>Enregistrer le brouillon</button>
              </div>
            )}
          </form>

          {r.alerts.length > 0 && (
            <section className="card pad" aria-label="Alertes de surveillance">
              <h3 style={{ marginTop: 0 }}>Alertes de surveillance</h3>
              <ul className="pay-list">
                {r.alerts.map((a) => (
                  <li key={a.id}>
                    <div>
                      <div style={{ fontWeight: 600 }}>{a.ruleLabel}</div>
                      <div className="muted">{a.detail}</div>
                      {a.resolutionNote && <div className="muted">Décision : {a.resolutionNote}</div>}
                    </div>
                    <div className="muted" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(a.createdAt)}</div>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section className="card table-wrap" aria-label="Opérations de la période">
            <div className="card-head row" style={{ flexWrap: 'wrap' }}>
              <h3>Opérations du {formatDate(r.periodFrom)} au {formatDate(r.periodTo)}</h3>
              <span className="spacer" />
              <span className="small">
                {r.totals.count} opération{r.totals.count > 1 ? 's' : ''} · entrées <b className="mono">{formatUsd(r.totals.inMinor)}</b> ·
                sorties <b className="mono">{formatUsd(r.totals.outMinor)}</b>
              </span>
            </div>
            {r.totals.truncated && <div className="banner info" role="note"><Icon name="alert" /><span>Plus de 500 opérations : seules les 500 plus récentes figurent ici. Joignez l'extrait complet.</span></div>}
            {r.operations.length === 0 ? (
              <div className="empty">Aucune opération sur la période.</div>
            ) : (
              <table>
                <thead><tr><th>Date</th><th>Opération</th><th className="hide-sm">N° de reçu</th><th style={{ textAlign: 'right' }}>Effet sur le solde</th></tr></thead>
                <tbody>
                  {r.operations.map((i) => (
                    <tr key={i.id}>
                      <td className="small mono" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(i.createdAt)}</td>
                      <td>
                        <div style={{ fontWeight: 600 }}>{historyTitle(i)}</div>
                        {i.counterpartyPhone && <div className="muted small mono">{i.counterpartyPhone}</div>}
                      </td>
                      <td className="small mono hide-sm">{i.receiptNumber}</td>
                      <td className="mono" style={{ textAlign: 'right', whiteSpace: 'nowrap', fontWeight: 600 }}>{formatSigned(i.netMinor)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          {draft && (
            <form className="card pad stack no-print" onSubmit={submit} aria-label="Transmission" style={{ gap: 12 }}>
              <h3 style={{ margin: 0 }}>Transmission à la CENAREF</h3>
              <div className="muted small">
                Imprimez le dossier, transmettez-le par le canal officiel de la CENAREF, puis notez ici la date et la référence reçue.
                Le dossier ne pourra plus être modifié.
              </div>
              <div className="row" style={{ flexWrap: 'wrap', gap: 12, alignItems: 'flex-end' }}>
                <label className="field">Transmise le
                  <input type="date" value={submittedOn} max={todayIso()} onChange={(e) => setSubmittedOn(e.target.value)} required />
                </label>
                <label className="field" style={{ flex: 1, minWidth: 200 }}>Référence CENAREF
                  <input type="text" value={reference} onChange={(e) => setReference(e.target.value)} required maxLength={100} placeholder="Ex. DS-2026-0142" />
                </label>
                <button className="btn small" disabled={busy || !reference.trim() || !facts.trim() || !analysis.trim()}>Noter la transmission</button>
              </div>
            </form>
          )}
        </div>
      )}
    </div>
  )
}
