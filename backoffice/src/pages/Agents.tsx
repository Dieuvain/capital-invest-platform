import { useState, type FormEvent } from 'react'
import type { Agent, Api, Role } from '../api'
import { initials, roleLabel } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

const roleHelp: Record<Role, string> = {
  KYC_AGENT: 'Examine et valide les demandes KYC 2',
  COMPLIANCE: 'Comme un agent KYC, plus les alertes sanctions et le journal',
  MARKETING: 'Gère les promotions de l\'app',
  ADMIN: 'Tous les accès, dont la gestion des agents',
}

export function Agents({ api, me, notify }: { api: Api; me: Agent; notify: (m: string) => void }) {
  const { data, error, reload } = useLoad(() => api.agents(), [])
  const [form, setForm] = useState(false)
  const [email, setEmail] = useState('')
  const [fullName, setFullName] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>('KYC_AGENT')
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [rowError, setRowError] = useState<string | null>(null)

  async function create(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setFormError(null)
    try {
      const a = await api.createAgent({ email, fullName, password, role })
      notify(`Compte créé pour ${a.fullName}`)
      setForm(false)
      setEmail('')
      setFullName('')
      setPassword('')
      reload()
    } catch (err) {
      setFormError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function toggle(a: Agent) {
    setRowError(null)
    try {
      await api.setAgentActive(a.id, !a.active)
      notify(a.active ? `Compte de ${a.fullName} désactivé, sessions fermées` : `Compte de ${a.fullName} réactivé`)
      reload()
    } catch (err) {
      setRowError((err as Error).message)
    }
  }

  return (
    <>
      <PageHead title="Agents" subtitle="Comptes du personnel. Désactiver un agent ferme aussitôt toutes ses sessions.">
        {!form && (
          <button className="btn ochre" onClick={() => setForm(true)}>
            <Icon name="plus" /> Nouvel agent
          </button>
        )}
      </PageHead>
      <div className="stack">
        {form && (
          <form className="card pad stack" onSubmit={create}>
            <h2>Nouvel agent</h2>
            <ErrorBanner error={formError} />
            <div className="grid-2">
              <label className="field">
                Nom complet
                <input type="text" required maxLength={120} value={fullName} onChange={(e) => setFullName(e.target.value)} />
              </label>
              <label className="field">
                Adresse e-mail professionnelle
                <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
              </label>
              <label className="field">
                Mot de passe provisoire <span className="hint">12 caractères au moins, à transmettre en main propre</span>
                <input type="password" required minLength={12} autoComplete="new-password" value={password}
                  onChange={(e) => setPassword(e.target.value)} />
              </label>
              <label className="field">
                Rôle <span className="hint">{roleHelp[role]}</span>
                <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
                  {(Object.keys(roleLabel) as Role[]).map((r) => (
                    <option key={r} value={r}>{roleLabel[r]}</option>
                  ))}
                </select>
              </label>
            </div>
            <div className="row">
              <span className="spacer" />
              <button type="button" className="btn ghost" onClick={() => setForm(false)} disabled={busy}>Annuler</button>
              <button className="btn" disabled={busy}>Créer le compte</button>
            </div>
          </form>
        )}
        <ErrorBanner error={error ?? rowError} />
        <div className="card table-wrap">
          <table>
            <thead>
              <tr><th>Agent</th><th>Rôle</th><th>État</th><th /></tr>
            </thead>
            <tbody>
              {data?.map((a) => (
                <tr key={a.id} style={{ opacity: a.active ? 1 : 0.55 }}>
                  <td>
                    <div className="row">
                      <div className="avatar" style={{ width: 34, height: 34, fontSize: 13 }}>{initials(a.fullName)}</div>
                      <div>
                        <div style={{ fontWeight: 700 }}>{a.fullName}{a.id === me.id && <span className="muted"> (vous)</span>}</div>
                        <div className="muted small">{a.email}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <div>{roleLabel[a.role]}</div>
                    <div className="muted small">{roleHelp[a.role]}</div>
                  </td>
                  <td>{a.active ? <span className="badge ok">Actif</span> : <span className="badge off">Désactivé</span>}</td>
                  <td style={{ textAlign: 'right' }}>
                    {a.id !== me.id && (
                      <button className="btn ghost small" onClick={() => toggle(a)}>
                        {a.active ? 'Désactiver' : 'Réactiver'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
