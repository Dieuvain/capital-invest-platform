import { useState, type FormEvent } from 'react'
import type { Agent, Api } from '../api'
import { ErrorBanner } from '../ui'

export function Login({ api, expired, onSignedIn }: { api: Api; expired: boolean; onSignedIn: (a: Agent) => void }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onSignedIn(await api.login(email, password))
    } catch (err) {
      setError((err as Error).message)
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="card stack" onSubmit={submit} style={{ gap: 18 }}>
        <div className="row" style={{ gap: 12 }}>
          <div className="logo" style={{ width: 44, height: 44, borderRadius: 14 }}>CI</div>
          <div>
            <div style={{ fontWeight: 700 }}>Capital Invest</div>
            <div className="muted small">Back-office des agents</div>
          </div>
        </div>
        <div>
          <h1>Connexion</h1>
          <p className="muted" style={{ margin: '4px 0 0' }}>Réservé au personnel habilité. Chaque action est tracée.</p>
        </div>
        {expired && !error && (
          <div className="banner info" role="status">Votre session a expiré après 15 minutes sans activité. Reconnectez-vous.</div>
        )}
        <ErrorBanner error={error} />
        <label className="field">
          Adresse e-mail
          <input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          Mot de passe
          <input type="password" autoComplete="current-password" required value={password}
            onChange={(e) => setPassword(e.target.value)} />
        </label>
        <button className="btn" disabled={busy || !email || !password} style={{ justifyContent: 'center', padding: 14 }}>
          {busy ? 'Connexion…' : 'Se connecter'}
        </button>
        <p className="muted small" style={{ margin: 0, textAlign: 'center' }}>
          5 erreurs bloquent le compte 15 minutes.
        </p>
      </form>
    </div>
  )
}
