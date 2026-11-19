import { useState } from 'react'
import type { Api } from '../api'
import { auditLabel, formatDateTime } from '../format'
import { ErrorBanner, PageHead, useLoad } from '../ui'

const actorLabel = { AGENT: 'Agent', CUSTOMER: 'Client', SYSTEM: 'Système' } as const

export function Audit({ api }: { api: Api }) {
  const { data, error } = useLoad(() => api.audit(300), [])
  const [q, setQ] = useState('')
  const needle = q.trim().toLowerCase()
  const rows = (data ?? []).filter(
    (e) =>
      !needle ||
      [auditLabel(e), e.actorName ?? '', e.detail ?? '', e.targetId].some((v) => v.toLowerCase().includes(needle)),
  )
  return (
    <>
      <PageHead title="Journal d'audit" subtitle="Qui a fait quoi, et quand. Le journal ne peut être ni modifié ni effacé.">
        <input type="text" placeholder="Filtrer : agent, action, identifiant…" value={q} onChange={(e) => setQ(e.target.value)}
          style={{ width: 300 }} aria-label="Filtrer le journal" />
      </PageHead>
      <ErrorBanner error={error} />
      <div className="card table-wrap">
        {data && rows.length === 0 ? (
          <div className="empty">Rien ne correspond.</div>
        ) : (
          <table>
            <thead>
              <tr><th>Date</th><th>Qui</th><th>Action</th><th>Détail</th></tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}>
                  <td className="small mono" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(e.createdAt)}</td>
                  <td>
                    <div style={{ fontWeight: 600 }}>{e.actorName ?? actorLabel[e.actorType]}</div>
                    {e.actorName && <div className="muted small">{actorLabel[e.actorType]}</div>}
                  </td>
                  <td>
                    <span className={`badge ${e.action.includes('HIT') || e.action.includes('REJECT') || e.action.includes('DISABLED') ? 'alert' : e.action.includes('APPROVED') ? 'ok' : 'off'}`}>
                      {auditLabel(e)}
                    </span>
                  </td>
                  <td className="small muted">{e.detail ?? <span className="mono">{e.targetType.toLowerCase()} {e.targetId.slice(0, 8)}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
