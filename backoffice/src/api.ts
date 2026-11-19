// Client de l'API back-office. Le jeton agent est distinct des jetons clients.

export type Role = 'KYC_AGENT' | 'COMPLIANCE' | 'ADMIN' | 'MARKETING'

export interface Agent {
  id: string
  email: string
  fullName: string
  role: Role
  active: boolean
}

export type KycStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface KycRequest {
  id: string
  customerId: string
  customerName: string
  customerPhone: string
  idType: 'VOTER_CARD' | 'PASSPORT'
  idNumber: string
  birthDate: string
  idFrontDoc: string
  idBackDoc: string | null
  selfieDoc: string
  screeningResult: 'CLEAR' | 'HIT'
  screeningDetail: string | null
  status: KycStatus
  decidedBy: string | null
  decidedAt: string | null
  rejectionReason: string | null
  createdAt: string
}

export type PromoAction = 'KYC' | 'TOPUP' | 'TRANSFER' | 'LIMITS'
export type PromoStyle = 'OCHRE' | 'INK' | 'GREEN' | 'SKY'
export type PromoAudience = 'ALL' | 'KYC1' | 'KYC2'

export interface PromotionInput {
  title: string
  body: string
  ctaLabel: string | null
  action: PromoAction | null
  style: PromoStyle
  audience: PromoAudience
  priority: number
  startsAt: string | null
  endsAt: string | null
  active: boolean
}

export interface Promotion extends PromotionInput {
  id: string
  startsAt: string
  live: boolean
}

export interface AuditEntry {
  id: string
  actorType: 'AGENT' | 'CUSTOMER' | 'SYSTEM'
  actorId: string | null
  actorName: string | null
  action: string
  targetType: string
  targetId: string
  detail: string | null
  createdAt: string
}

export type Operator = 'MPESA' | 'AIRTEL' | 'ORANGE'
export type DiscrepancyKind =
  | 'CONFIRMED_NOT_CREDITED'
  | 'CREDITED_NOT_CONFIRMED'
  | 'PAID_BUT_REFUNDED'
  | 'MARKED_PAID_NOT_PAID'
  | 'AMOUNT_MISMATCH'
  | 'STILL_PENDING'
/** Recharge (l'argent entre) ou retrait (il sort vers le mobile money). */
export type MovementType = 'TOPUP' | 'WITHDRAWAL'
export type WithdrawalDecision = 'PAID' | 'REFUND'
export type ResolveAction = 'CREDIT' | 'CLOSE'

export interface ReconciliationRun {
  id: string
  businessDate: string
  startedAt: string
  finishedAt: string | null
  checked: number
  matched: number
  discrepancies: number
}

export interface ReconciliationItem {
  id: string
  businessDate: string
  type: MovementType
  targetId: string
  kind: DiscrepancyKind
  ourStatus: string
  providerStatus: string
  ourAmountMinor: number
  /** Absent quand l'opérateur n'a pas donné de montant. */
  providerAmountMinor?: number | null
  operator: Operator
  provider: string
  customerName: string
  customerPhone: string
  topupCreatedAt: string
  resolvedAt: string | null
  resolvedByName: string | null
  resolution: string | null
}

export interface ReviewTopup {
  id: string
  type: MovementType
  operator: Operator
  provider: string
  amountMinor: number
  currency: string
  reason: string | null
  customerName: string
  customerPhone: string
  checks: number
  createdAt: string
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

type Fetch = (input: string, init?: RequestInit) => Promise<Response>

export class Api {
  token: string | null = null
  /** Appelé quand le serveur refuse la session (expirée, révoquée, agent désactivé). */
  onExpired: () => void = () => {}

  constructor(
    private readonly base = '',
    private readonly fetcher: Fetch = (i, init) => fetch(i, init),
  ) {}

  private async send(method: string, path: string, body?: unknown): Promise<Response> {
    let res: Response
    try {
      res = await this.fetcher(this.base + path, {
        method,
        headers: {
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw new ApiError(0, 'NETWORK', 'Serveur injoignable. Vérifiez la connexion et réessayez.')
    }
    if (res.ok) return res
    let code = `HTTP_${res.status}`
    let detail = `Erreur ${res.status}`
    try {
      const p = await res.json()
      code = p.code ?? code
      detail = p.detail ?? detail
    } catch {
      /* pas de corps JSON */
    }
    if (res.status === 401 && this.token && path !== '/v1/backoffice/auth/login') {
      this.token = null
      this.onExpired()
    }
    throw new ApiError(res.status, code, detail)
  }

  private async json<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.send(method, path, body)
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
  }

  // ---- Session ----
  async login(email: string, password: string): Promise<Agent> {
    const t = await this.json<{ accessToken: string; agent: Agent }>('POST', '/v1/backoffice/auth/login', { email, password })
    this.token = t.accessToken
    return t.agent
  }
  me = () => this.json<Agent>('GET', '/v1/backoffice/me')
  async logout() {
    if (!this.token) return
    try {
      await this.send('POST', '/v1/backoffice/auth/logout')
    } finally {
      this.token = null
    }
  }

  // ---- KYC 2 ----
  kycList = (status: KycStatus) => this.json<KycRequest[]>('GET', `/v1/backoffice/kyc?status=${status}&limit=200`)
  kyc = (id: string) => this.json<KycRequest>('GET', `/v1/backoffice/kyc/${id}`)
  approve = (id: string) => this.json<KycRequest>('POST', `/v1/backoffice/kyc/${id}/approve`)
  reject = (id: string, reason: string) => this.json<KycRequest>('POST', `/v1/backoffice/kyc/${id}/reject`, { reason })
  /** Photo d'une pièce, en mémoire seulement (jamais en cache disque). */
  async document(requestId: string, documentId: string): Promise<Blob> {
    return (await this.send('GET', `/v1/backoffice/kyc/${requestId}/documents/${documentId}`)).blob()
  }

  // ---- Promotions ----
  promotions = () => this.json<Promotion[]>('GET', '/v1/backoffice/promotions')
  createPromotion = (p: PromotionInput) => this.json<Promotion>('POST', '/v1/backoffice/promotions', p)
  updatePromotion = (id: string, p: PromotionInput) => this.json<Promotion>('PUT', `/v1/backoffice/promotions/${id}`, p)

  // ---- Agents et journal ----
  agents = () => this.json<Agent[]>('GET', '/v1/backoffice/agents')
  createAgent = (a: { email: string; fullName: string; password: string; role: Role }) =>
    this.json<Agent>('POST', '/v1/backoffice/agents', a)
  setAgentActive = (id: string, active: boolean) =>
    this.json<Agent>('POST', `/v1/backoffice/agents/${id}/${active ? 'enable' : 'disable'}`)
  // ---- Recharges et rapprochement ----
  reviewTopups = () => this.json<ReviewTopup[]>('GET', '/v1/backoffice/topups/review')
  resolveTopup = (id: string, action: ResolveAction, note: string) =>
    this.json<unknown>('POST', `/v1/backoffice/topups/${id}/resolve`, { action, note })
  resolveWithdrawal = (id: string, action: WithdrawalDecision, note: string) =>
    this.json<unknown>('POST', `/v1/backoffice/withdrawals/${id}/resolve`, { action, note })
  reconciliationRuns = (limit = 14) => this.json<ReconciliationRun[]>('GET', `/v1/backoffice/reconciliation/runs?limit=${limit}`)
  runReconciliation = (businessDate?: string) =>
    this.json<ReconciliationRun>('POST', '/v1/backoffice/reconciliation/runs', businessDate ? { businessDate } : {})
  reconciliationItems = (open = true) =>
    this.json<ReconciliationItem[]>('GET', `/v1/backoffice/reconciliation/items?open=${open}`)
  resolveItem = (id: string, action: ResolveAction, note: string) =>
    this.json<ReconciliationItem>('POST', `/v1/backoffice/reconciliation/items/${id}/resolve`, { action, note })

  audit = (limit = 200) => this.json<AuditEntry[]>('GET', `/v1/backoffice/audit?limit=${limit}`)
}
