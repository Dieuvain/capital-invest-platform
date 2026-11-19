import { useEffect, useState } from 'react'
import type { Api, Promotion, PromotionInput } from '../api'
import { audienceLabel, formatDateTime } from '../format'
import { ErrorBanner, Icon, PageHead, useLoad } from '../ui'

/** Charge le visuel d'une promotion (il faut le jeton agent, donc pas de simple <img src>). */
export function usePromoImage(api: Api, p: Pick<Promotion, 'id' | 'imageUpdatedAt'> | null): string | null {
  const [url, setUrl] = useState<string | null>(null)
  const id = p?.id
  const version = p?.imageUpdatedAt
  useEffect(() => {
    setUrl(null)
    if (!id || !version) return
    let objectUrl: string | null = null
    let live = true
    api.promotionImage(id).then(
      (blob) => {
        if (!live) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      },
      () => {},
    )
    return () => {
      live = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [api, id, version])
  return url
}

/** La carte telle que les clients la voient dans l'app : le visuel du graphiste, ou la carte de secours. */
export function PromoCard({ p, image }: { p: Pick<PromotionInput, 'title' | 'body' | 'ctaLabel' | 'style'>; image?: string | null }) {
  if (image) {
    return (
      <div className="promo visual">
        <img src={image} alt={p.title} />
      </div>
    )
  }
  return (
    <div className={`promo ${p.style}`}>
      <span className="tag">PROMO</span>
      <div className="t">{p.title || 'Titre de la promotion'}</div>
      <div className="b">{p.body || 'Le texte de la promotion apparaît ici.'}</div>
      {p.ctaLabel && <div className="cta">{p.ctaLabel} →</div>}
    </div>
  )
}

function status(p: Promotion): { label: string; cls: string } {
  if (!p.active) return { label: 'Retirée', cls: 'off' }
  if (p.live) return { label: "En ligne dans l'app", cls: 'ok' }
  if (new Date(p.startsAt) > new Date()) return { label: `Programmée · ${formatDateTime(p.startsAt)}`, cls: 'wait' }
  return { label: 'Terminée', cls: 'off' }
}

export function Promotions({ api, open }: { api: Api; open: (id: string) => void }) {
  const { data, error } = useLoad(() => api.promotions(), [])
  const live = data?.filter((p) => p.live).length ?? 0
  return (
    <>
      <PageHead
        title="Promotions"
        subtitle={`Le bandeau défilant de l'accueil de l'app. ${live} en ligne, 5 au plus sont montrées à un client, par priorité.`}
      >
        <button className="btn ochre" onClick={() => open('nouvelle')}>
          <Icon name="plus" /> Nouvelle promotion
        </button>
      </PageHead>
      <ErrorBanner error={error} />
      {data && data.length === 0 && <div className="card empty">Aucune promotion pour le moment.</div>}
      <div className="promo-list">
        {data?.map((p) => {
          const s = status(p)
          return (
            <div key={p.id} className="card promo-item" role="button" tabIndex={0} onClick={() => open(p.id)}
              onKeyDown={(e) => e.key === 'Enter' && open(p.id)} style={{ opacity: p.active ? 1 : 0.6 }}>
              <ListedCard api={api} p={p} />
              <div className="meta">
                <span className={`badge ${s.cls}`}>{s.label}</span>
                <span className="muted">{audienceLabel[p.audience]}</span>
                <span className="muted">· priorité {p.priority}</span>
                {p.endsAt && <span className="muted">· jusqu'au {formatDateTime(p.endsAt)}</span>}
                {!p.imageUpdatedAt && <span className="muted">· sans visuel</span>}
              </div>
            </div>
          )
        })}
      </div>
    </>
  )
}

function ListedCard({ api, p }: { api: Api; p: Promotion }) {
  return <PromoCard p={p} image={usePromoImage(api, p)} />
}
