import { useCallback, useEffect, useState } from 'react'
import type { Agent, Api, Role } from './api'
import { initials, roleLabel } from './format'
import { Icon, Toast } from './ui'
import { Login } from './pages/Login'
import { KycQueue } from './pages/KycQueue'
import { KycReview } from './pages/KycReview'
import { Promotions } from './pages/Promotions'
import { PromotionEditor } from './pages/PromotionEditor'
import { Agents } from './pages/Agents'
import { Audit } from './pages/Audit'

const TOKEN_KEY = 'capital-invest-agent'

/** Chaque menu, et qui y a accès. Le serveur vérifie de toute façon les mêmes droits. */
export const sections: { path: string; label: string; icon: string; roles: Role[] }[] = [
  { path: 'kyc', label: 'Demandes KYC 2', icon: 'id', roles: ['KYC_AGENT', 'COMPLIANCE', 'ADMIN'] },
  { path: 'promotions', label: 'Promotions', icon: 'promo', roles: ['MARKETING', 'ADMIN'] },
  { path: 'agents', label: 'Agents', icon: 'agents', roles: ['ADMIN'] },
  { path: 'journal', label: "Journal d'audit", icon: 'journal', roles: ['COMPLIANCE', 'ADMIN'] },
]

function useHashRoute(): [string[], (path: string) => void] {
  const read = () => window.location.hash.replace(/^#\/?/, '').split('/').filter(Boolean)
  const [parts, setParts] = useState(read)
  useEffect(() => {
    const on = () => setParts(read())
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  const go = useCallback((path: string) => {
    window.location.hash = `/${path}`
  }, [])
  return [parts, go]
}

function storage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

export function App({ api }: { api: Api }) {
  const [agent, setAgent] = useState<Agent | null>(null)
  const [restoring, setRestoring] = useState(true)
  const [expired, setExpired] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const [pending, setPending] = useState<number | null>(null)
  const [route, go] = useHashRoute()

  useEffect(() => {
    api.onExpired = () => {
      storage()?.removeItem(TOKEN_KEY)
      setAgent(null)
      setExpired(true)
    }
    const saved = storage()?.getItem(TOKEN_KEY)
    if (!saved) {
      setRestoring(false)
      return
    }
    api.token = saved
    api
      .me()
      .then(setAgent, () => storage()?.removeItem(TOKEN_KEY))
      .finally(() => setRestoring(false))
  }, [api])

  const allowed = agent ? sections.filter((s) => s.roles.includes(agent.role)) : []

  // Nombre de demandes en attente, affiché dans le menu.
  useEffect(() => {
    if (!agent || !allowed.some((s) => s.path === 'kyc')) return
    api.kycList('PENDING').then((l) => setPending(l.length), () => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent, api, route.join('/')])

  if (restoring) return null
  if (!agent) {
    return (
      <Login
        api={api}
        expired={expired}
        onSignedIn={(a) => {
          if (api.token) storage()?.setItem(TOKEN_KEY, api.token)
          setExpired(false)
          setAgent(a)
        }}
      />
    )
  }

  const [section = allowed[0]?.path, id] = route
  const current = allowed.find((s) => s.path === section) ?? allowed[0]
  const notify = (m: string) => setToast(m)

  let page = <p className="muted">Aucun menu n'est ouvert à votre rôle.</p>
  switch (current?.path) {
    case 'kyc':
      page = id ? (
        <KycReview api={api} agent={agent} id={id} onDone={(m) => { notify(m); go('kyc') }} back={() => go('kyc')} />
      ) : (
        <KycQueue api={api} open={(rid) => go(`kyc/${rid}`)} />
      )
      break
    case 'promotions':
      page = id ? (
        <PromotionEditor api={api} id={id === 'nouvelle' ? null : id} back={() => go('promotions')}
          onSaved={(m) => { notify(m); go('promotions') }} />
      ) : (
        <Promotions api={api} open={(pid) => go(`promotions/${pid}`)} />
      )
      break
    case 'agents':
      page = <Agents api={api} me={agent} notify={notify} />
      break
    case 'journal':
      page = <Audit api={api} />
      break
  }

  return (
    <div className="shell">
      <nav className="sidebar" aria-label="Menu principal">
        <div className="brand">
          <div className="logo">CI</div>
          <div>
            Capital Invest
            <div className="small" style={{ opacity: 0.6, fontWeight: 500 }}>Back-office</div>
          </div>
        </div>
        {allowed.map((s) => (
          <a key={s.path} href={`#/${s.path}`} className={`nav-item${s === current ? ' active' : ''}`}
            aria-current={s === current ? 'page' : undefined}>
            <Icon name={s.icon} />
            <span className="nav-label">{s.label}</span>
            {s.path === 'kyc' && pending ? <span className="count" aria-label={`${pending} en attente`}>{pending}</span> : null}
          </a>
        ))}
        <div className="me">
          <div className="avatar">{initials(agent.fullName)}</div>
          <div className="who">
            <div style={{ fontWeight: 700 }}>{agent.fullName}</div>
            <div className="small" style={{ opacity: 0.6 }}>{roleLabel[agent.role]}</div>
          </div>
          <button className="round" style={{ background: 'transparent', color: 'inherit', boxShadow: 'none' }}
            title="Se déconnecter" aria-label="Se déconnecter"
            onClick={async () => {
              await api.logout().catch(() => {})
              storage()?.removeItem(TOKEN_KEY)
              setAgent(null)
            }}>
            <Icon name="logout" />
          </button>
        </div>
      </nav>
      <main>{page}</main>
      <Toast message={toast} onDone={() => setToast(null)} />
    </div>
  )
}
