import { useEffect, useState, type ReactNode } from 'react'

/** Petites icônes au trait, pour ne dépendre d'aucune bibliothèque. */
const paths: Record<string, string> = {
  id: 'M4 6h16v12H4zM8 10.5a1.5 1.5 0 1 0 0-.01M6.5 15c.4-1.3 1.3-2 2.5-2s2.1.7 2.5 2M14 10h4M14 13h3',
  promo: 'M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1zM15 9a3 3 0 0 1 0 6M18 6a7 7 0 0 1 0 12',
  agents: 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 19c.6-3 3-4.5 6-4.5s5.4 1.5 6 4.5M16 5.5a3 3 0 0 1 0 5.5M18 14.5c1.6.6 2.7 2 3 4.5',
  journal: 'M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7',
  logout: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10',
  back: 'M15 18l-6-6 6-6',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  close: 'M6 6l12 12M18 6L6 18',
  alert: 'M12 4l9 16H3zM12 10v4M12 17v.01',
  plus: 'M12 5v14M5 12h14',
  shield: 'M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z',
  home: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  bell: 'M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15zM10 20a2 2 0 0 0 4 0',
  up: 'M12 19V5M6 11l6-6 6 6',
  down: 'M12 5v14M6 13l6 6 6-6',
  out: 'M7 17L17 7M9 7h8v8',
  scale: 'M12 4v16M8 20h8M4 7h16M7 7l-3 7h6zM17 7l-3 7h6z',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
}

export function Icon({ name, size = 20 }: { name: keyof typeof paths | string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={paths[name] ?? ''} />
    </svg>
  )
}

export function ErrorBanner({ error }: { error: string | null }) {
  if (!error) return null
  return (
    <div className="banner error" role="alert">
      <Icon name="alert" />
      <span>{error}</span>
    </div>
  )
}

export function Toast({ message, onDone }: { message: string | null; onDone: () => void }) {
  useEffect(() => {
    if (!message) return
    const t = setTimeout(onDone, 3500)
    return () => clearTimeout(t)
  }, [message, onDone])
  return message ? (
    <div className="toast" role="status">
      {message}
    </div>
  ) : null
}

export function PageHead({ title, subtitle, back, children }: {
  title: string
  subtitle?: ReactNode
  back?: () => void
  children?: ReactNode
}) {
  return (
    <div className="page-head">
      {back && (
        <button className="round" onClick={back} aria-label="Retour" title="Retour">
          <Icon name="back" />
        </button>
      )}
      <div className="grow">
        <h2 style={{ fontSize: 24, fontWeight: 500 }}>{title}</h2>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {children}
    </div>
  )
}

/** Charge une donnée au montage et quand `deps` change ; garde l'erreur en clair. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let live = true
    setError(null)
    load().then(
      (d) => live && setData(d),
      (e: Error) => live && setError(e.message),
    )
    return () => {
      live = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  return { data, error, setData, reload: () => setTick((t) => t + 1) }
}
