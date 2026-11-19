import type { Agent, HistoryItem, AuditEntry, Merchant, MonitoringAlert, MerchantPayment, MerchantPayout, KycRequest, Promotion, ReconciliationItem, ReconciliationRun, ReviewTopup, Role } from '../api'

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
      active: true, live: true, imageType: null, imageUpdatedAt: null,
    },
  ]
  /** Visuels reçus, par promotion. */
  images = new Map<string, File>()
  audit: AuditEntry[] = []
  alerts: MonitoringAlert[] = [
    {
      id: 'al-1', rule: 'FAN_IN', ruleLabel: 'Argent reçu de nombreux clients', detail: '7 expéditeurs différents en 24 h, pour 640,00 $',
      transactionId: 'tx-al1', status: 'OPEN', createdAt: '2026-11-19T08:40:00Z', customerId: 'c-1', customerName: 'Amani Kabeya',
      customerPhone: '+243970000001', customerStatus: 'ACTIVE', kycLevel: 1,
    },
    {
      id: 'al-0', rule: 'VELOCITY', ruleLabel: "Rafale d'opérations", detail: "12 sorties d'argent en 60 min, pour 84,00 $",
      transactionId: 'tx-al0', status: 'DISMISSED', createdAt: '2026-11-17T15:00:00Z', customerId: 'c-2', customerName: 'Béni Mukendi',
      customerPhone: '+243970000402', customerStatus: 'ACTIVE', kycLevel: 2, resolvedByName: 'Ruth Conformité',
      resolvedAt: '2026-11-17T16:00:00Z', resolutionNote: 'Commerçant connu, paie ses fournisseurs',
    },
  ]
  customer = { id: 'c-1', phone: '+243970000001', fullName: 'Amani Kabeya', kycLevel: 1, status: 'ACTIVE', balanceMinor: 4750 }
  history: HistoryItem[] = [
    item('w1', 'WITHDRAWAL', -1015, 15, { status: 'PENDING', operator: 'AIRTEL', msisdn: '+243970000001' }),
    item('s1', 'TRANSFER_SENT', -2020, 20, { counterpartyName: 'Béni Mukendi', counterpartyPhone: '+243970000402', note: 'Loyer' }),
    item('t1', 'TOPUP', 7785, 0, { operator: 'MPESA' }),
  ]
  review: ReviewTopup[] = [
    {
      id: 't-review', type: 'TOPUP', operator: 'MPESA', provider: 'PAWAPAY', amountMinor: 5000, currency: 'USD',
      reason: "Montant reçu chez l'opérateur : 40,00 $, attendu 50,00 $", customerName: 'Amani Kabeya',
      customerPhone: '+243970000001', checks: 3, createdAt: '2026-11-18T08:00:00Z',
    },
    {
      id: 'w-review', type: 'WITHDRAWAL', operator: 'AIRTEL', provider: 'PAWAPAY', amountMinor: 3000, currency: 'USD',
      reason: 'Aucune réponse définitive après 24 h (toujours en cours chez l\'opérateur)', customerName: 'Esther Mbuyi',
      customerPhone: '+243970000005', checks: 12, createdAt: '2026-11-18T09:00:00Z',
    },
  ]
  runs: ReconciliationRun[] = [
    { id: 'r-1', businessDate: '2026-11-17', startedAt: '2026-11-18T00:00:00Z', finishedAt: '2026-11-18T00:00:12Z', checked: 42, matched: 41, discrepancies: 1 },
  ]
  items: ReconciliationItem[] = [
    {
      id: 'i-1', businessDate: '2026-11-17', type: 'TOPUP', targetId: 't-9', kind: 'CONFIRMED_NOT_CREDITED', ourStatus: 'FAILED',
      providerStatus: 'COMPLETED', ourAmountMinor: 2500, providerAmountMinor: 2500, operator: 'AIRTEL', provider: 'PAWAPAY',
      customerName: 'Béni Mukendi', customerPhone: '+243970000003', topupCreatedAt: '2026-11-17T15:00:00Z',
      resolvedAt: null, resolvedByName: null, resolution: null,
    },
    {
      id: 'i-2', businessDate: '2026-11-17', type: 'TOPUP', targetId: 't-10', kind: 'STILL_PENDING', ourStatus: 'PENDING',
      providerStatus: 'PENDING', ourAmountMinor: 700, operator: 'ORANGE', provider: 'PAWAPAY',
      customerName: 'Josué Kalala', customerPhone: '+243970000004', topupCreatedAt: '2026-11-17T22:40:00Z',
      resolvedAt: null, resolvedByName: null, resolution: null,
    },
    {
      id: 'i-3', businessDate: '2026-11-17', type: 'WITHDRAWAL', targetId: 'w-5', kind: 'MARKED_PAID_NOT_PAID', ourStatus: 'CONFIRMED',
      providerStatus: 'FAILED', ourAmountMinor: 2500, operator: 'MPESA', provider: 'PAWAPAY',
      customerName: 'Grâce Ngalula', customerPhone: '+243810000006', topupCreatedAt: '2026-11-17T11:00:00Z',
      resolvedAt: null, resolvedByName: null, resolution: null,
    },
  ]
  merchants: Merchant[] = [
    {
      id: 'm-1', code: 'K7P2QX', name: 'Alimentation Mama Nzuzi', category: 'Alimentation', city: 'Lubumbashi',
      phone: '+243990000101', commissionBps: 100, status: 'ACTIVE', balanceMinor: 1237,
      qrPayload: 'capitalinvest://pay?m=K7P2QX', createdAt: '2026-11-18T08:00:00Z',
      payoutOperator: 'AIRTEL', payoutPhone: '+243990000101', payoutBlockedUntil: null, autoPayout: true,
    },
  ]
  merchantPayments: Record<string, MerchantPayment[]> = {
    'm-1': [{
      transactionId: 'tx-m1', receiptNumber: 'CI-20261119-0000M1', customerName: 'Amani Kabeya', customerPhone: '+243970000001',
      amountMinor: 1250, commissionMinor: 13, createdAt: '2026-11-19T09:00:00Z',
    }],
  }
  merchantPayouts: Record<string, MerchantPayout[]> = {
    'm-1': [{
      id: 'po-0', amountMinor: 5000, operator: 'AIRTEL', phone: '+243990000101', status: 'CONFIRMED', createdAt: '2026-11-18T18:00:00Z',
      automatic: true,
    }],
  }
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
    const form = init?.body instanceof FormData ? init.body : null
    const body = init?.body && !form ? JSON.parse(String(init.body)) : undefined
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
    if (path.startsWith('/v1/backoffice/monitoring')) {
      if (!role('COMPLIANCE', 'ADMIN')) return problem(403, 'COMPLIANCE_ONLY', 'Réservé à la conformité')
      if ((m = path.match(/^\/v1\/backoffice\/monitoring\/alerts\/([^/]+)\/resolve$/))) {
        const a = this.alerts.find((x) => x.id === m![1])!
        if (a.status !== 'OPEN') return problem(422, 'ALERT_ALREADY_RESOLVED', 'Cette alerte a déjà été traitée')
        Object.assign(a, {
          status: body.action === 'SUSPEND' ? 'ESCALATED' : 'DISMISSED', resolutionNote: body.note,
          resolvedByName: me.fullName, resolvedAt: '2026-11-19T10:10:00Z',
        })
        if (body.action === 'SUSPEND') {
          this.alerts.filter((x) => x.customerId === a.customerId).forEach((x) => { x.customerStatus = 'SUSPENDED' })
          if (a.customerId === this.customer.id) this.customer.status = 'SUSPENDED'
        }
        return json(a)
      }
      const status = u.searchParams.get('status')
      const cid = u.searchParams.get('customerId')
      return json(this.alerts.filter((a) => (status === 'ALL' || (status === 'OPEN') === (a.status === 'OPEN')) && (!cid || a.customerId === cid)))
    }
    if (path.startsWith('/v1/backoffice/customers')) {
      if (!role('COMPLIANCE', 'ADMIN')) return problem(403, 'COMPLIANCE_ONLY', 'Réservé à la conformité')
      if (path.endsWith('/status') && method === 'POST') {
        this.customer.status = body.status
        return json({ customerId: this.customer.id, status: body.status })
      }
      if (path === '/v1/backoffice/customers') {
        const phone = u.searchParams.get('phone')!.replace(/[^+\d]/g, '')
        return phone === this.customer.phone ? json(this.customer) : problem(404, 'CUSTOMER_NOT_FOUND', 'Aucun client avec ce numéro')
      }
      if ((m = path.match(/^\/v1\/backoffice\/customers\/[^/]+\/history\/([^/]+)$/))) {
        const i = this.history.find((h) => h.id === m![1])
        return i ? json({ item: i, holderName: this.customer.fullName, holderPhone: this.customer.phone, issuedAt: '2026-11-19T10:00:00Z' })
          : problem(404, 'OPERATION_NOT_FOUND', 'Opération introuvable')
      }
      if (path.endsWith('/history')) {
        const cat = u.searchParams.get('category')
        const kinds: Record<string, string[]> = { TOPUP: ['TOPUP'], TRANSFER: ['TRANSFER_SENT', 'TRANSFER_RECEIVED'], WITHDRAWAL: ['WITHDRAWAL', 'WITHDRAWAL_REFUND'], PAYMENT: ['MERCHANT_PAYMENT'] }
        const all = this.history.filter((h) => !cat || kinds[cat].includes(h.kind))
        // Deux opérations par page, pour exercer « Voir plus ».
        const start = Number(u.searchParams.get('cursor') ?? 0)
        return json({ items: all.slice(start, start + 2), nextCursor: start + 2 < all.length ? String(start + 2) : null })
      }
      return json(this.customer)
    }
    if (path.startsWith('/v1/backoffice/merchants')) {
      if (!role('COMPLIANCE', 'ADMIN')) return problem(403, 'COMPLIANCE_ONLY', 'Réservé à la conformité')
      if (method === 'POST' && !role('ADMIN')) return problem(403, 'ADMIN_ONLY', 'Réservé aux administrateurs')
      if (path === '/v1/backoffice/merchants' && method === 'GET') return json(this.merchants)
      if (path === '/v1/backoffice/merchants') {
        const digits = String(body.phone).replace(/\D/g, '')
        if (digits.length < 9) return problem(422, 'INVALID_PHONE', 'Numéro du marchand invalide (+243…)')
        const n = this.merchants.length + 1
        const code = `NEW${n}AB`.slice(0, 6)
        const created: Merchant = {
          id: `m-${n}`, code, name: body.name, category: body.category, city: body.city, phone: `+243${digits.slice(-9)}`,
          commissionBps: body.commissionBps, status: 'ACTIVE', balanceMinor: 0, qrPayload: `capitalinvest://pay?m=${code}`,
          createdAt: '2026-11-19T10:00:00Z', payoutOperator: body.payoutOperator, payoutPhone: body.payoutPhone, payoutBlockedUntil: null,
          autoPayout: true,
        }
        this.merchants.push(created)
        return json(created, 201)
      }
      if ((m = path.match(/^\/v1\/backoffice\/merchants\/([^/]+)\/payout-account$/))) {
        const found = this.merchants.find((x) => x.id === m![1])!
        const first = !found.payoutPhone
        Object.assign(found, {
          payoutOperator: body.operator, payoutPhone: `+243${String(body.phone).replace(/\D/g, '').slice(-9)}`,
          payoutBlockedUntil: first ? null : '2026-11-20T10:00:00Z',
        })
        return json(found)
      }
      if ((m = path.match(/^\/v1\/backoffice\/merchants\/([^/]+)\/payouts$/))) {
        const found = this.merchants.find((x) => x.id === m![1])!
        if (body.amountMinor > found.balanceMinor) return problem(422, 'INSUFFICIENT_FUNDS', 'Le marchand n\'a pas assez à reverser')
        found.balanceMinor -= body.amountMinor
        const p: MerchantPayout = {
          id: `po-${body.requestId}`, amountMinor: body.amountMinor, operator: found.payoutOperator!, phone: found.payoutPhone!,
          status: 'PENDING', createdAt: '2026-11-19T10:05:00Z', automatic: false,
        }
        this.merchantPayouts[found.id] = [p, ...(this.merchantPayouts[found.id] ?? [])]
        return json(p, 202)
      }
      if ((m = path.match(/^\/v1\/backoffice\/merchants\/([^/]+)\/auto-payout$/))) {
        const found = this.merchants.find((x) => x.id === m![1])!
        found.autoPayout = body.enabled
        return json(found)
      }
      if ((m = path.match(/^\/v1\/backoffice\/merchants\/([^/]+)\/status$/))) {
        const found = this.merchants.find((x) => x.id === m![1])!
        found.status = body.status
        return json(found)
      }
      if ((m = path.match(/^\/v1\/backoffice\/merchants\/([^/]+)$/))) {
        const found = this.merchants.find((x) => x.id === m![1])
        return found ? json({ merchant: found, payments: this.merchantPayments[found.id] ?? [], payouts: this.merchantPayouts[found.id] ?? [] }) : problem(404, 'MERCHANT_NOT_FOUND', 'Marchand introuvable')
      }
    }
    if (path.startsWith('/v1/backoffice/promotions')) {
      if (method !== 'GET' && !role('MARKETING', 'ADMIN')) return problem(403, 'MARKETING_ONLY', 'Réservé au marketing')
      if (path === '/v1/backoffice/promotions' && method === 'GET') return json(this.promotions)
      if ((m = path.match(/^\/v1\/backoffice\/promotions\/([^/]+)\/image$/))) {
        const p = this.promotions.find((x) => x.id === m![1])!
        if (method === 'GET') {
          const f = this.images.get(p.id)
          return f ? new Response(new Uint8Array([0x89, 0x50]), { headers: { 'Content-Type': f.type } }) : problem(404, 'PROMOTION_IMAGE_NOT_FOUND', 'Pas de visuel pour cette promotion')
        }
        const f = form!.get('file') as File
        this.images.set(p.id, f)
        Object.assign(p, { imageType: f.type, imageUpdatedAt: '2026-11-19T10:00:00Z' })
        return json(p)
      }
      if (body?.ctaLabel && !body.action) return problem(422, 'PROMOTION_INVALID', "Un bouton a besoin d'une action")
      if (method === 'POST') {
        const p = {
          ...body, id: `p-${this.promotions.length + 1}`, startsAt: body.startsAt ?? '2026-11-19T00:00:00Z', live: body.active,
          imageType: null, imageUpdatedAt: null,
        }
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
    if (/^\/v1\/backoffice\/(reconciliation|topups|withdrawals)/.test(path)) {
      if (!role('ADMIN', 'COMPLIANCE')) return problem(403, 'COMPLIANCE_ONLY', 'Réservé à la conformité et aux administrateurs')
      if (path === '/v1/backoffice/topups/review') return json(this.review)
      if ((m = path.match(/^\/v1\/backoffice\/topups\/([^/]+)\/resolve$/))) {
        this.review = this.review.filter((t) => t.id !== m![1])
        return json({ id: m[1], status: body.action === 'CREDIT' ? 'CONFIRMED' : 'FAILED' })
      }
      if ((m = path.match(/^\/v1\/backoffice\/withdrawals\/([^/]+)\/resolve$/))) {
        if (body.action === 'REFUND') {
          return problem(422, 'PROVIDER_NOT_FAILED', "L'opérateur ne confirme pas l'échec (PENDING) : rembourser risquerait de payer le client deux fois.")
        }
        this.review = this.review.filter((t) => t.id !== m![1])
        return json({ id: m[1], status: 'CONFIRMED' })
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
        if (body.action === 'CREDIT' && i.kind !== 'CONFIRMED_NOT_CREDITED' && i.kind !== 'MARKED_PAID_NOT_PAID') {
          return problem(422, 'CREDIT_NOT_ALLOWED', 'Créditer n\'est possible que pour un paiement encaissé et non crédité')
        }
        if (body.action === 'CREDIT' && i.kind === 'MARKED_PAID_NOT_PAID') i.ourStatus = 'FAILED'
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

function item(id: string, kind: HistoryItem['kind'], net: number, fee: number, extra: Partial<HistoryItem> = {}): HistoryItem {
  return {
    id, receiptNumber: `CI-20261118-${id.toUpperCase().padStart(6, '0')}`, kind, status: 'COMPLETED',
    amountMinor: Math.abs(net) - fee, feeMinor: fee, netMinor: net, currency: 'USD', createdAt: '2026-11-18T10:00:00Z', ...extra,
  }
}
