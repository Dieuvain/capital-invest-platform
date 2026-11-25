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
import { Customers } from './pages/Customers'
import { Merchants } from './pages/Merchants'
import { Reconciliation } from './pages/Reconciliation'
import { Complaints } from './pages/Complaints'
import { Monitoring } from './pages/Monitoring'
import { SuspicionReportPage } from './pages/SuspicionReport'
import { Dashboard } from './pages/Dashboard'
import { ActivityPage } from './pages/Activity'
import { Credits } from './pages/Credits'
import { ago, buildNotices } from './notifications'
import type { KycRequest, Promotion } from './api'

const TOKEN_KEY = 'capital-invest-agent'
const SEEN_KEY = 'capital-invest-notifs-vues'

/** Chaque menu, et qui y a accès. Le serveur vérifie de toute façon les mêmes droits. */
export const sections: { path: string; label: string; icon: string; roles: Role[]; other?: boolean }[] = [
  { path: 'accueil', label: 'Tableau de bord', icon: 'home', roles: ['KYC_AGENT', 'COMPLIANCE', 'ADMIN', 'MARKETING'] },
  { path: 'activite', label: 'Activité', icon: 'chart', roles: ['COMPLIANCE', 'ADMIN', 'MARKETING'] },
  { path: 'kyc', label: 'Demandes KYC 2', icon: 'id', roles: ['KYC_AGENT', 'COMPLIANCE', 'ADMIN'] },
  { path: 'promotions', label: 'Promotions', icon: 'promo', roles: ['MARKETING', 'ADMIN'] },
  { path: 'credits', label: 'Crédits', icon: 'credit', roles: ['CREDIT_OFFICER', 'ADMIN'] },
  { path: 'reclamations', label: 'Réclamations', icon: 'chat', roles: ['COMPLIANCE', 'ADMIN'] },
  { path: 'clients', label: 'Clients', icon: 'search', roles: ['COMPLIANCE', 'ADMIN'] },
  { path: 'marchands', label: 'Marchands', icon: 'shop', roles: ['COMPLIANCE', 'ADMIN'] },
  { path: 'surveillance', label: 'Surveillance', icon: 'shield', roles: ['COMPLIANCE', 'ADMIN'] },
  { path: 'rapprochement', label: 'Rapprochement', icon: 'scale', roles: ['COMPLIANCE', 'ADMIN'] },
  { path: 'agents', label: 'Agents', icon: 'agents', roles: ['ADMIN'] },
  { path: 'journal', label: "Journal d'audit", icon: 'journal', roles: ['COMPLIANCE', 'ADMIN'], other: true },
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
  const [pendingKyc, setPendingKyc] = useState<KycRequest[] | null>(null)
  const [promos, setPromos] = useState<Promotion[] | null>(null)
  const [notifOpen, setNotifOpen] = useState(false)
  const [seen, setSeen] = useState(() => storage()?.getItem(SEEN_KEY) ?? '')
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

  // Demandes en attente et promotions : compteur du menu et notifications.
  useEffect(() => {
    if (!agent) return
    if (allowed.some((s) => s.path === 'kyc')) api.kycList('PENDING').then(setPendingKyc, () => {})
    if (allowed.some((s) => s.path === 'promotions')) api.promotions().then(setPromos, () => {})
    setNotifOpen(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent, api, route.join('/')])
  const pending = pendingKyc?.length ?? 0
  const notices = buildNotices(pendingKyc, promos)
  const unread = notices.some((n) => !seen.split(',').includes(n.id))

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
          go('accueil')
        }}
      />
    )
  }

  const [section = allowed[0]?.path, id] = route
  const current = allowed.find((s) => s.path === section) ?? allowed[0]
  const notify = (m: string) => setToast(m)

  let page = <p className="muted">Aucun menu n'est ouvert à votre rôle.</p>
  switch (current?.path) {
    case 'accueil':
      page = <Dashboard api={api} agent={agent} allowed={allowed.map((s) => s.path)} />
      break
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
    case 'credits':
      page = <Credits api={api} me={agent} notify={notify} />
      break
    case 'reclamations':
      page = <Complaints api={api} notify={notify} openCustomer={(cid) => go(`clients/${cid}`)} />
      break
    case 'clients':
      page = <Customers api={api} id={id ?? null} open={(cid) => go(cid ? `clients/${cid}` : 'clients')} notify={notify} />
      break
    case 'marchands':
      page = <Merchants api={api} me={agent} id={id ?? null} open={(mid) => go(mid ? `marchands/${mid}` : 'marchands')} notify={notify} />
      break
    case 'surveillance':
      page = id ? (
        <SuspicionReportPage api={api} id={id} back={() => go('surveillance')} notify={notify} />
      ) : (
        <Monitoring api={api} openCustomer={(cid) => go(`clients/${cid}`)} openMerchant={(mid) => go(`marchands/${mid}`)} openReport={(rid) => go(`surveillance/${rid}`)} notify={notify} />
      )
      break
    case 'activite':
      page = <ActivityPage api={api} />
      break
    case 'rapprochement':
      page = <Reconciliation api={api} notify={notify} />
      break
    case 'agents':
      page = <Agents api={api} me={agent} notify={notify} />
      break
    case 'journal':
      page = <Audit api={api} />
      break
  }

  const signOut = async () => {
    await api.logout().catch(() => {})
    storage()?.removeItem(TOKEN_KEY)
    setAgent(null)
  }
  const firstName = agent.fullName.split(' ')[0]
  const link = (s: (typeof sections)[number]) => (
    <a key={s.path} href={`#/${s.path}`} className={`nav-item${s === current ? ' active' : ''}`}
      aria-current={s === current ? 'page' : undefined}>
      <Icon name={s.icon} size={18} />
      <span className="nav-label">{s.label}</span>
      {s.path === 'kyc' && pending ? <span className="count" aria-label={`${pending} en attente`}>{pending}</span> : null}
    </a>
  )

  return (
    <div className="frame-outer">
      <div className="shell">
        <nav className="sidebar" aria-label="Menu principal">
          <div className="brand">
            <div className="logo">CI</div>
            Capital Invest
          </div>
          <div className="nav-title">Menu principal</div>
          {allowed.filter((s) => !s.other).map(link)}
          <div className="nav-title later">Autres</div>
          {allowed.filter((s) => s.other).map(link)}
          <button className="nav-item" onClick={signOut}>
            <Icon name="logout" size={18} />
            Se déconnecter
          </button>
        </nav>
        <div className="content">
          <header className="topbar">
            <div className="hello">
              <h1>Bonjour, {firstName} !</h1>
              <p className="muted">
                {roleLabel[agent.role]} · {pending ? `${pending} demande${pending > 1 ? 's' : ''} KYC en attente` : 'Suivez l\'activité en temps réel'}
              </p>
            </div>
            <button className="icon-btn" aria-label="Notifications" aria-expanded={notifOpen}
              onClick={() => {
                setNotifOpen((o) => !o)
                const ids = notices.map((n) => n.id).join(',')
                storage()?.setItem(SEEN_KEY, ids)
                setSeen(ids)
              }}>
              <Icon name="bell" size={18} />
              {unread && <span className="dot" />}
            </button>
            <div className="avatar big" title={agent.fullName}>{initials(agent.fullName)}</div>
            {notifOpen && (
              <section className="notifs" aria-label="Notifications">
                <header>
                  <h3 style={{ fontSize: 16, fontWeight: 500 }}>Notifications</h3>
                  <button onClick={() => setNotifOpen(false)} aria-label="Fermer"><Icon name="close" size={16} /></button>
                </header>
                {notices.length === 0 && <div className="none">Rien de nouveau. Tout est à jour.</div>}
                {notices.slice(0, 6).map((n) => (
                  <a key={n.id} className="notif" href={n.href} onClick={() => setNotifOpen(false)}>
                    <span className={`ic ${n.tone}`}><Icon name={n.icon} size={14} /></span>
                    <span className="tx">
                      {n.parts.map((t, i) => (i % 2 ? <b key={i}>{t}</b> : t))}
                      <div className="when">{ago(n.at)}</div>
                    </span>
                    <span className="muted"><Icon name="out" size={14} /></span>
                  </a>
                ))}
                {allowed.some((s) => s.path === 'kyc') && (
                  <a className="all" href="#/kyc" onClick={() => setNotifOpen(false)}>Voir toutes les demandes</a>
                )}
              </section>
            )}
          </header>
          <main>{page}</main>
        </div>
      </div>
      <Toast message={toast} onDone={() => setToast(null)} />
    </div>
  )
}
