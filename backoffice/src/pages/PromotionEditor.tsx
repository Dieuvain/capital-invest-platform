import { useEffect, useState, type FormEvent } from 'react'
import { PROMO_IMAGE_MAX_BYTES, PROMO_IMAGE_TYPES, type Api, type Promotion, type PromoAction, type PromoAudience, type PromoStyle, type PromotionInput } from '../api'
import { actionLabel, audienceLabel, fromLocalInput, styleLabel, toLocalInput } from '../format'
import { ErrorBanner, PageHead } from '../ui'
import { PromoCard, usePromoImage } from './Promotions'

const empty: PromotionInput = {
  title: '',
  body: '',
  ctaLabel: null,
  action: null,
  style: 'OCHRE',
  audience: 'ALL',
  priority: 0,
  startsAt: null,
  endsAt: null,
  active: true,
}

export function PromotionEditor({ api, id, back, onSaved }: {
  api: Api
  id: string | null
  back: () => void
  onSaved: (message: string) => void
}) {
  const [p, setP] = useState<PromotionInput>(empty)
  const [saved, setSaved] = useState<Promotion | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [fileUrl, setFileUrl] = useState<string | null>(null)
  const savedImage = usePromoImage(api, saved)
  const image = fileUrl ?? savedImage
  const hasImage = image !== null
  const [loaded, setLoaded] = useState(id === null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    api.promotions().then(
      (all) => {
        const found = all.find((x) => x.id === id)
        if (found) {
          setP(found)
          setSaved(found)
        }
        else setError('Promotion introuvable')
        setLoaded(true)
      },
      (e: Error) => setError(e.message),
    )
  }, [api, id])

  useEffect(() => {
    if (!file) return
    const u = URL.createObjectURL(file)
    setFileUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [file])

  function pick(f: File | undefined) {
    setError(null)
    if (!f) return
    if (!PROMO_IMAGE_TYPES.includes(f.type)) return setError('Visuel JPEG, PNG ou WebP attendu.')
    if (f.size > PROMO_IMAGE_MAX_BYTES) return setError('Visuel de 2 Mo au plus attendu. Demandez une version plus légère au graphiste.')
    setFile(f)
  }

  const set = <K extends keyof PromotionInput>(k: K, v: PromotionInput[K]) => setP((x) => ({ ...x, [k]: v }))

  async function save(next: PromotionInput, message: string) {
    setBusy(true)
    setError(null)
    try {
      const body = {
        ...next,
        body: next.body?.trim() || null,
        ctaLabel: next.action ? next.ctaLabel?.trim() || null : null,
      }
      // Sans visuel, l'app dessine une carte de secours : elle a besoin d'un texte et, s'il y a une action, d'un bouton.
      if (!hasImage && !body.body) throw new Error('Ajoutez un visuel, ou écrivez un texte pour la carte de secours.')
      if (!hasImage && body.action && !body.ctaLabel) throw new Error('Indiquez le texte du bouton, ou choisissez « Aucune action ».')
      const result = await (id ? api.updatePromotion(id, body) : api.createPromotion(body))
      if (file) {
        try {
          await api.uploadPromotionImage(result.id, file)
        } catch (e) {
          throw new Error(`Promotion enregistrée, mais le visuel n'a pas été envoyé : ${(e as Error).message}`)
        }
      }
      onSaved(message)
    } catch (e) {
      setError((e as Error).message)
      setBusy(false)
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    save(p, id ? 'Promotion enregistrée' : 'Promotion publiée')
  }

  if (!loaded) return <ErrorBanner error={error} />

  return (
    <>
      <PageHead title={id ? 'Modifier la promotion' : 'Nouvelle promotion'} back={back}
        subtitle="L'aperçu à droite montre la carte telle qu'elle apparaîtra dans l'app." />
      <form className="editor" onSubmit={submit}>
        <div className="card pad stack">
          <ErrorBanner error={error} />
          <div className="field">
            Visuel <span className="hint">JPEG, PNG ou WebP, 1200 x 520 px, 2 Mo au plus</span>
            <div className="row">
              <label className="btn ghost file">
                {hasImage ? 'Remplacer le visuel' : 'Choisir le visuel'}
                <input type="file" accept={PROMO_IMAGE_TYPES.join(',')} aria-label="Visuel"
                  onChange={(e) => pick(e.target.files?.[0])} />
              </label>
              <span className="muted small">
                {file ? `${file.name} · envoyé à l'enregistrement` : hasImage ? 'Visuel en ligne' : 'Aucun visuel : la carte de secours est montrée'}
              </span>
            </div>
          </div>
          <label className="field">
            Titre <span className="hint">60 caractères au plus. Lu à voix haute aux malvoyants sur un visuel.</span>
            <input type="text" required maxLength={60} value={p.title} onChange={(e) => set('title', e.target.value)} />
          </label>
          <label className="field">
            Texte <span className="hint">{hasImage ? 'facultatif avec un visuel' : "140 caractères au plus, deux lignes dans l'app"}</span>
            <textarea maxLength={140} value={p.body ?? ''} onChange={(e) => set('body', e.target.value)} />
          </label>
          <div className="grid-2">
            <label className="field">
              Au toucher
              <select value={p.action ?? ''} onChange={(e) => set('action', (e.target.value || null) as PromoAction | null)}>
                <option value="">Aucune action (simple information)</option>
                {(Object.keys(actionLabel) as PromoAction[]).map((a) => (
                  <option key={a} value={a}>{actionLabel[a]}</option>
                ))}
              </select>
            </label>
            <label className="field">
              Texte du bouton <span className="hint">{hasImage ? 'facultatif : tout le visuel mène à l\'action' : '30 caractères au plus'}</span>
              <input type="text" maxLength={30} disabled={!p.action} value={p.ctaLabel ?? ''}
                placeholder={p.action ? 'Ex. : Recharger' : ''} onChange={(e) => set('ctaLabel', e.target.value)} />
            </label>
          </div>
          <div className="field" style={{ fontSize: 13, fontWeight: 600 }}>
            Couleur
            <div className="styles" role="radiogroup" aria-label="Couleur">
              {(Object.keys(styleLabel) as PromoStyle[]).map((s) => (
                <button key={s} type="button" role="radio" aria-checked={p.style === s} aria-label={styleLabel[s]}
                  title={styleLabel[s]} className={`swatch ${s}${p.style === s ? ' on' : ''}`} onClick={() => set('style', s)} />
              ))}
            </div>
          </div>
          <div className="grid-2">
            <label className="field">
              Qui la voit
              <select value={p.audience} onChange={(e) => set('audience', e.target.value as PromoAudience)}>
                {(Object.keys(audienceLabel) as PromoAudience[]).map((a) => (
                  <option key={a} value={a}>{audienceLabel[a]}</option>
                ))}
              </select>
            </label>
            <label className="field">
              Priorité <span className="hint">la plus haute passe en premier</span>
              <input type="number" value={p.priority} onChange={(e) => set('priority', Number(e.target.value) || 0)} />
            </label>
            <label className="field">
              Début <span className="hint">vide : tout de suite</span>
              <input type="datetime-local" value={toLocalInput(p.startsAt)} onChange={(e) => set('startsAt', fromLocalInput(e.target.value))} />
            </label>
            <label className="field">
              Fin <span className="hint">vide : sans fin</span>
              <input type="datetime-local" value={toLocalInput(p.endsAt)} onChange={(e) => set('endsAt', fromLocalInput(e.target.value))} />
            </label>
          </div>
          <div className="row" style={{ marginTop: 6 }}>
            {id && p.active && (
              <button type="button" className="btn ghost" disabled={busy}
                onClick={() => save({ ...p, active: false }, 'Promotion retirée de l\'app')}>
                Retirer de l'app
              </button>
            )}
            {id && !p.active && (
              <button type="button" className="btn ghost" disabled={busy}
                onClick={() => save({ ...p, active: true }, 'Promotion remise en ligne')}>
                Remettre en ligne
              </button>
            )}
            <span className="spacer" />
            <button type="button" className="btn ghost" onClick={back} disabled={busy}>Annuler</button>
            <button className="btn" disabled={busy}>{id ? 'Enregistrer' : 'Publier'}</button>
          </div>
        </div>
        <aside>
          <div className="small muted" style={{ fontWeight: 600 }}>Aperçu dans l'app</div>
          <div className="phone"><PromoCard p={p} image={image} /></div>
          <p className="muted small" style={{ margin: 0 }}>
            Rien n'est effacé : une promotion retirée reste dans la liste et dans le journal d'audit.
          </p>
        </aside>
      </form>
    </>
  )
}
