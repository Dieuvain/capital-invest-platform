import type { Agent, AuditEntry, KycRequest, Promotion, ReconciliationItem, ReconciliationRun, ReviewTopup, Role } from '../api'

/** Faux serveur back-office : mêmes routes, mêmes codes d'erreur que l'API réelle, sans réseau. */
export class FakeServer {
  password = 'mot-de-passe-solide'
  agents: Agent[] = [
    { id: 'a-admin', email: 'admin@capitalinvest.cd', fullName: 'Grace Admin', role: 'ADMIN', active: true },
    { id: 'a-kyc', email: 'kyc@capitalinvest.cd', fullName: 'Patrick Ilunga', role: 'KYC_AGENT', active: true },
    { id: 'a-conf', email: 'conformite@capitalinvest.cd', fullName: 'Ruth Conformité', role: 'COMPLIANCE', active: true },
    { id: 'a-mkt', email: 'marketing@capitalinvest.cd', fullName: 'Joël Marketing', role: 'MARKETING', active: true },
  ]
  sessions = new Map<string, string>()
  kyc: KycRequest[] = [
    request('k-1', 'Amani Kabeya', '+243970000001', 'CLEAR', '2026-11-18T08:00:00Z'),
    request('k-2', 'Mutombo Fictif Sanction', '+243970000002', 'HIT', '2026-11-18T09:30:00Z'),
  ]
  promotions: Promotion[] = [
    {
      id: 'p-1', title: 'Passez au niveau 2', body: "Envoyez jusqu'à 4 000 $ par opération.", ctaLabel: 'Vérifier mon identité',
      action: 'KYC', style: 'OCHRE', audience: 'KYC1', priority: 30, startsAt: '2026-11-01T00:00:00Z', endsAt: null,
      active: true, live: true,
    },
  ]
  audit: AuditEntry[] = []
  review: ReviewTopup[] = [
    {
      id: 't-review', operator: 'MPESA', provider: 'PAWAPAY', amountMinor: 5000, currency: 'USD',
      reason: "Montant reçu chez l'opérateur : 4000 USD, attendu 5000 USD", customerName: 'Amani Kabeya',
      customerPhone: '+243970000001', checks: 3, createdAt: '2026-11-18T08:00:00Z',
    },
  ]
  runs: ReconciliationRun[] = [
    { id: 'r-1', businessDate: '2026-11-17', startedAt: '2026-11-18T00:00:00Z', finishedAt: '2026-11-18T00:00:12Z', checked: 42, matched: 41, discrepancies: 1 },
  ]
  items: ReconciliationItem[] = [
    {
      id: 'i-1', businessDate: '2026-11-17', topupId: 't-9', kind: 'CONFIRMED_NOT_CREDITED', ourStatus: 'FAILED',
      providerStatus: 'COMPLETED', ourAmountMinor: 2500, providerAmountMinor: 2500, operator: 'AIRTEL', provider: 'PAWAPAY',
      customerName: 'Béni Mukendi', customerPhone: '+243970000003', topupCreatedAt: '2026-11-17T15:00:00Z',
      resolvedAt: null, resolvedByName: null, resolution: null,
    },
    {
      id: 'i-2', businessDate: '2026-11-17', topupId: 't-10', kind: 'STILL_PENDING', ourStatus: 'PENDING',
      providerStatus: 'PENDING', ourAmountMinor: 700, providerAmountMinor: null, operator: 'ORANGE', provider: 'PAWAPAY',
      customerName: 'Josué Kalala', customerPhone: '+243970000004', topupCreatedAt: '2026-11-17T22:40:00Z',
      resolvedAt: null, resolvedByName: null, resolution: null,
    },
  ]
  requests: { method: string; path: string; body: unknown }[] = []
  documentsViewed: string[] = []

  login(role: Role): string {
    const a = this.agents.find((x) => x.role === role)!
    const token = `jeton-${a.id}`
    this.sessions.set(token, a.id)
    return token
  }

  fetch = async (url: string, init?: RequestInit): Promise<Response> => {
    const method = init?.method ?? 'GET'
    const u = new URL(url, 'http://test')
    const path = u.pathname
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    this.requests.push({ method, path, body })
    const json = (data: unknown, status = 200) =>
      new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
    const problem = (status: number, code: string, detail: string) => json({ status, code, detail }, status)

    if (method === 'POST' && path === '/v1/backoffice/auth/login') {
      const a = this.agents.find((x) => x.email === body.email)
      if (!a || body.password !== this.password) return problem(401, 'INVALID_CREDENTIALS', 'Adresse ou mot de passe incorrect')
      if (!a.active) return problem(403, 'AGENT_DISABLED', 'Compte agent désactivé')
      const token = `jeton-${a.id}`
      this.sessions.set(token, a.id)
      return json({ accessToken: token, expiresInSeconds: 900, agent: a })
    }
    const token = new Headers(init?.headers).get('Authorization')?.replace('Bearer ', '') ?? ''
    const me = this.agents.find((a) => a.id === this.sessions.get(token))
    if (!me) return problem(401, 'SESSION_INVALID', 'Session agent invalide ou expirée')
    const role = (...roles: Role[]) => roles.includes(me.role)

    let m: RegExpMatchArray | null
    if (path === '/v1/backoffice/me') return json(me)
    if (path === '/v1/backoffice/auth/logout') {
      this.sessions.delete(token)
      return new Response(null, { status: 204 })
    }
    if (path.startsWith('/v1/backoffice/kyc') && !role('KYC_AGENT', 'COMPLIANCE', 'ADMIN')) {
      return problem(403, 'KYC_ONLY', 'Réservé aux agents KYC')
    }
    if (path === '/v1/backoffice/kyc') return json(this.kyc.filter((k) => k.status === u.searchParams.get('status')))
    if ((m = path.match(/^\/v1\/backoffice\/kyc\/([^/]+)$/))) return json(this.kyc.find((k) => k.id === m![1]))
    if ((m = path.match(/^\/v1\/backoffice\/kyc\/([^/]+)\/documents\/([^/]+)$/))) {
      this.documentsViewed.push(m[2])
      return new Response(new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' }))
    }
    if ((m = path.match(/^\/v1\/backoffice\/kyc\/([^/]+)\/(approve|reject)$/))) {
      const k = this.kyc.find((x) => x.id === m![1])!
      if (m[2] === 'approve' && k.screeningResult === 'HIT' && me.role !== 'COMPLIANCE') {
        return problem(403, 'COMPLIANCE_REQUIRED', 'Correspondance sanctions : validation réservée à la conformité')
      }
      Object.assign(k, {
        status: m[2] === 'approve' ? 'APPROVED' : 'REJECTED',
        rejectionReason: body?.reason ?? null,
        decidedBy: me.id,
        decidedAt: '2026-11-19T10:00:00Z',
      })
      return json(k)
    }
    if (path.startsWith('/v1/backoffice/promotions')) {
      if (method !== 'GET' && !role('MARKETING', 'ADMIN')) return problem(403, 'MARKETING_ONLY', 'Réservé au marketing')
      if (path === '/v1/backoffice/promotions' && method === 'GET') return json(this.promotions)
      if (body?.ctaLabel && !body.action) return problem(422, 'PROMOTION_INVALID', "Un bouton a besoin d'une action")
      if (method === 'POST') {
        const p = { ...body, id: `p-${this.promotions.length + 1}`, startsAt: body.startsAt ?? '2026-11-19T00:00:00Z', live: body.active }
        this.promotions.push(p)
        return json(p, 201)
      }
      const p = this.promotions.find((x) => x.id === path.split('/').pop())!
      Object.assign(p, body, { live: body.active })
      return json(p)
    }
    if (path === '/v1/backoffice/agents') {
      if (!role('ADMIN')) return problem(403, 'ADMIN_ONLY', 'Réservé aux administrateurs')
      if (method === 'GET') return json(this.agents)
      if (body.password.length < 12) return problem(422, 'PASSWORD_TOO_SHORT', 'Mot de passe de 12 caractères au moins')
      const a: Agent = { id: `a-${this.agents.length + 1}`, email: body.email, fullName: body.fullName, role: body.role, active: true }
      this.agents.push(a)
      return json(a, 201)
    }
    if ((m = path.match(/^\/v1\/backoffice\/agents\/([^/]+)\/(enable|disable)$/))) {
      const a = this.agents.find((x) => x.id === m![1])!
      a.active = m[2] === 'enable'
      if (!a.active) for (const [t, id] of this.sessions) if (id === a.id) this.sessions.delete(t)
      return json(a)
    }
    if (path.startsWith('/v1/backoffice/reconciliation') || path.startsWith('/v1/backoffice/topups')) {
      if (!role('ADMIN', 'COMPLIANCE')) return problem(403, 'COMPLIANCE_ONLY', 'Réservé à la conformité et aux administrateurs')
      if (path === '/v1/backoffice/topups/review') return json(this.review)
      if ((m = path.match(/^\/v1\/backoffice\/topups\/([^/]+)\/resolve$/))) {
        this.review = this.review.filter((t) => t.id !== m![1])
        return json({ id: m[1], status: body.action === 'CREDIT' ? 'CONFIRMED' : 'FAILED' })
      }
      if (path === '/v1/backoffice/reconciliation/runs' && method === 'GET') return json(this.runs)
      if (path === '/v1/backoffice/reconciliation/runs') {
        const r = { ...this.runs[0], id: 'r-2', businessDate: body.businessDate, checked: 12, matched: 12, discrepancies: 0 }
        this.runs.unshift(r)
        return json(r)
      }
      if (path === '/v1/backoffice/reconciliation/items') return json(this.items.filter((i) => !i.resolvedAt))
      if ((m = path.match(/^\/v1\/backoffice\/reconciliation\/items\/([^/]+)\/resolve$/))) {
        const i = this.items.find((x) => x.id === m![1])!
        if (body.action === 'CREDIT' && i.kind !== 'CONFIRMED_NOT_CREDITED') {
          return problem(422, 'CREDIT_NOT_ALLOWED', 'Créditer n\'est possible que pour un paiement encaissé et non crédité')
        }
        Object.assign(i, { resolvedAt: '2026-11-19T10:00:00Z', resolvedByName: me.fullName, resolution: body.note })
        return json(i)
      }
    }
    if (path === '/v1/backoffice/audit') {
      if (!role('ADMIN', 'COMPLIANCE')) return problem(403, 'COMPLIANCE_ONLY', 'Réservé à la conformité')
      return json(this.audit)
    }
    return problem(404, 'NOT_FOUND', `Route inconnue ${method} ${path}`)
  }
}

function request(id: string, name: string, phone: string, screening: 'CLEAR' | 'HIT', createdAt: string): KycRequest {
  return {
    id, customerId: `c-${id}`, customerName: name, customerPhone: phone, idType: 'VOTER_CARD', idNumber: 'CE 1234 5678',
    birthDate: '1990-05-12', idFrontDoc: `${id}-front`, idBackDoc: `${id}-back`, selfieDoc: `${id}-selfie`,
    screeningResult: screening, screeningDetail: screening === 'HIT' ? 'Liste simulée : Mutombo Fictif Sanction' : null,
    status: 'PENDING', decidedBy: null, decidedAt: null, rejectionReason: null, createdAt,
  }
}
