import { useState } from 'react'
import type { Api, KycStatus } from '../api'
import { formatDateTime, idTypeLabel, waitingFor } from '../format'
import { ErrorBanner, PageHead, useLoad } from '../ui'

const tabs: { status: KycStatus; label: string }[] = [
  { status: 'PENDING', label: 'En attente' },
  { status: 'APPROVED', label: 'Validées' },
  { status: 'REJECTED', label: 'Refusées' },
]

export function KycQueue({ api, open }: { api: Api; open: (id: string) => void }) {
  const [status, setStatus] = useState<KycStatus>('PENDING')
  const { data, error } = useLoad(() => api.kycList(status), [status])
  const list = data ?? []
  const alerts = list.filter((r) => r.screeningResult === 'HIT').length

  return (
    <>
      <PageHead
        title="Demandes KYC 2"
        subtitle={
          status === 'PENDING'
            ? 'Les plus anciennes en premier. Vérifiez que le selfie correspond à la pièce et que les informations concordent.'
            : 'Demandes déjà traitées.'
        }
      >
        <div className="tabs" role="tablist">
          {tabs.map((t) => (
            <button key={t.status} role="tab" aria-selected={t.status === status}
              className={`tab${t.status === status ? ' active' : ''}`} onClick={() => setStatus(t.status)}>
              {t.label}
            </button>
          ))}
        </div>
      </PageHead>
      <div className="stack">
        <ErrorBanner error={error} />
        {status === 'PENDING' && alerts > 0 && (
          <div className="banner alert">
            <strong>{alerts} demande{alerts > 1 ? 's' : ''} signalée{alerts > 1 ? 's' : ''} par le contrôle sanctions.</strong>
            <span className="muted">Seule la conformité peut les valider.</span>
          </div>
        )}
        <div className="card table-wrap">
          {data && list.length === 0 ? (
            <div className="empty">
              {status === 'PENDING' ? 'Aucune demande en attente. Tout est à jour.' : 'Aucune demande ici pour le moment.'}
            </div>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Client</th>
                  <th>Pièce</th>
                  <th>Contrôle sanctions</th>
                  <th>{status === 'PENDING' ? 'Envoyée' : 'Décision'}</th>
                  {status === 'PENDING' && <th>Attente</th>}
                </tr>
              </thead>
              <tbody>
                {list.map((r) => (
                  <tr key={r.id} className="link" tabIndex={0} onClick={() => open(r.id)}
                    onKeyDown={(e) => e.key === 'Enter' && open(r.id)}>
                    <td>
                      <div style={{ fontWeight: 700 }}>{r.customerName}</div>
                      <div className="muted small mono">{r.customerPhone}</div>
                    </td>
                    <td>
                      <div>{idTypeLabel[r.idType]}</div>
                      <div className="muted small mono">{r.idNumber}</div>
                    </td>
                    <td>
                      {r.screeningResult === 'HIT' ? (
                        <span className="badge alert">Correspondance</span>
                      ) : (
                        <span className="badge ok">Aucune correspondance</span>
                      )}
                    </td>
                    <td className="small">
                      {formatDateTime(status === 'PENDING' ? r.createdAt : (r.decidedAt ?? r.createdAt))}
                      {r.rejectionReason && <div className="muted">{r.rejectionReason}</div>}
                    </td>
                    {status === 'PENDING' && (
                      <td>
                        <span className="badge wait">{waitingFor(r.createdAt)}</span>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </>
  )
}
