import { useEffect, useState } from 'react'
import type { Agent, Api, KycRequest } from '../api'
import { ageOn, formatDate, formatDateTime, idTypeLabel } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

const reasons = [
  'Photo floue ou illisible',
  'Le selfie ne correspond pas à la pièce',
  "La pièce d'identité est expirée",
  'Le nom ne correspond pas à celui du compte',
  'Le numéro ou la date de naissance ne correspondent pas',
]

/** Photo chargée avec le jeton agent et gardée en mémoire seulement. */
function Photo({ api, request, docId, caption, onZoom }: {
  api: Api
  request: KycRequest
  docId: string
  caption: string
  onZoom: (url: string) => void
}) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let objectUrl: string | null = null
    let live = true
    api.document(request.id, docId).then(
      (blob) => {
        if (!live) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      },
      () => live && setFailed(true),
    )
    return () => {
      live = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [api, request.id, docId])
  return (
    <figure className="photo" style={{ margin: 0 }}>
      <div className="frame" onClick={() => url && onZoom(url)}>
        {url ? <img src={url} alt={caption} /> : <span className="muted small">{failed ? 'Photo indisponible' : 'Chargement…'}</span>}
      </div>
      <figcaption className="cap">{caption}</figcaption>
    </figure>
  )
}

export function KycReview({ api, agent, id, onDone, back }: {
  api: Api
  agent: Agent
  id: string
  onDone: (message: string) => void
  back: () => void
}) {
  const { data: r, error: loadError } = useLoad(() => api.kyc(id), [id])
  const [zoom, setZoom] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!r) return <ErrorBanner error={loadError} />

  const age = ageOn(r.birthDate)
  const hit = r.screeningResult === 'HIT'
  const canApprove = !hit || agent.role === 'COMPLIANCE'
  const pending = r.status === 'PENDING'

  async function decide(action: () => Promise<KycRequest>, message: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onDone(message)
    } catch (e) {
      setError((e as Error).message)
      setBusy(false)
    }
  }

  return (
    <>
      <PageHead title={r.customerName} subtitle={`${r.customerPhone} · demande du ${formatDateTime(r.createdAt)}`} back={back}>
        {r.status === 'APPROVED' && <span className="badge ok">Validée</span>}
        {r.status === 'REJECTED' && <span className="badge alert">Refusée</span>}
        {pending && <span className="badge wait">En attente</span>}
      </PageHead>

      <div className="stack">
        {hit && (
          <div className="banner alert" role="alert">
            <Icon name="shield" />
            <div>
              <strong>Correspondance sanctions ou PEP.</strong> {r.screeningDetail}
              <div className="muted small">
                {agent.role === 'COMPLIANCE'
                  ? 'Vérifiez la correspondance avant de décider.'
                  : 'Seule la conformité peut valider cette demande. Vous pouvez la refuser ou la laisser à la conformité.'}
              </div>
            </div>
          </div>
        )}
        <ErrorBanner error={error} />

        <div className="card pad stack">
          <h2>Photos</h2>
          <div className="photos">
            <Photo api={api} request={r} docId={r.idFrontDoc} caption="Recto de la pièce" onZoom={setZoom} />
            {r.idBackDoc ? (
              <Photo api={api} request={r} docId={r.idBackDoc} caption="Verso de la pièce" onZoom={setZoom} />
            ) : (
              <div className="photo"><div className="frame" style={{ cursor: 'default' }}><span className="muted small">Pas de verso (passeport)</span></div></div>
            )}
            <Photo api={api} request={r} docId={r.selfieDoc} caption="Selfie" onZoom={setZoom} />
          </div>
          <p className="muted small" style={{ margin: 0 }}>Cliquez sur une photo pour l'agrandir. Chaque consultation est inscrite au journal.</p>
        </div>

        <div className="grid-2">
          <div className="card pad stack">
            <h2>Informations déclarées</h2>
            <dl className="facts">
              <dt>Nom sur le compte</dt><dd>{r.customerName}</dd>
              <dt>Pièce</dt><dd>{idTypeLabel[r.idType]}</dd>
              <dt>Numéro</dt><dd className="mono">{r.idNumber}</dd>
              <dt>Date de naissance</dt><dd>{formatDate(r.birthDate)} ({age} ans)</dd>
            </dl>
          </div>
          <div className="card pad stack">
            <h2>À vérifier</h2>
            <ul className="muted" style={{ margin: 0, paddingLeft: 18, lineHeight: 1.8 }}>
              <li>Le visage du selfie est celui de la pièce</li>
              <li>Le nom et la date de naissance sont ceux de la pièce</li>
              <li>La pièce est lisible, entière et non expirée</li>
              <li>Aucun signe de retouche ou de photo d'écran</li>
            </ul>
          </div>
        </div>

        {pending ? (
          <div className="card pad stack">
            {rejecting ? (
              <>
                <h2>Motif du refus</h2>
                <p className="muted" style={{ margin: 0 }}>Le client est prévenu par SMS et lit ce motif dans l'app. Il pourra envoyer une nouvelle demande.</p>
                <div className="reasons">
                  {reasons.map((m) => (
                    <button key={m} type="button" className={`chip${reason === m ? ' on' : ''}`} onClick={() => setReason(m)}>{m}</button>
                  ))}
                </div>
                <label className="field">
                  Motif <span className="hint">300 caractères au plus</span>
                  <textarea maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
                </label>
                <div className="row">
                  <button className="btn ghost" onClick={() => setRejecting(false)} disabled={busy}>Annuler</button>
                  <span className="spacer" />
                  <button className="btn danger" disabled={busy || !reason.trim()}
                    onClick={() => decide(() => api.reject(r.id, reason.trim()), `Demande de ${r.customerName} refusée`)}>
                    Refuser la demande
                  </button>
                </div>
              </>
            ) : (
              <div className="row wrap">
                <div className="grow" style={{ flex: 1, minWidth: 220 }}>
                  <h2>Décision</h2>
                  <p className="muted" style={{ margin: '4px 0 0' }}>
                    En validant, le client passe au niveau 2 : 4 000 $ par opération, 10 000 $ par jour.
                  </p>
                </div>
                <button className="btn ghost" onClick={() => setRejecting(true)} disabled={busy}>Refuser…</button>
                <button className="btn success" disabled={busy || !canApprove}
                  title={canApprove ? undefined : 'Réservé à la conformité'}
                  onClick={() => decide(() => api.approve(r.id), `Niveau 2 accordé à ${r.customerName}`)}>
                  <Icon name="check" /> Valider le niveau 2
                </button>
              </div>
            )}
          </div>
        ) : (
          <div className={`banner ${r.status === 'APPROVED' ? 'ok' : 'error'}`}>
            {r.status === 'APPROVED' ? 'Demande validée' : `Demande refusée : ${r.rejectionReason}`}
            {r.decidedAt && ` le ${formatDateTime(r.decidedAt)}`}.
          </div>
        )}
      </div>

      {zoom && (
        <div className="zoom" onClick={() => setZoom(null)} role="dialog" aria-label="Photo agrandie">
          <img src={zoom} alt="" />
        </div>
      )}
    </>
  )
}
