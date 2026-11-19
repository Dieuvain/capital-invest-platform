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
  /** Facultatif : sert seulement à la carte de secours, tant qu'aucun visuel n'est envoyé. */
  body: string | null
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
  imageType: string | null
  imageUpdatedAt: string | null
}

/** Visuel conseillé : 1200 x 520 px (le format du bandeau de l'app), 2 Mo au plus. */
export const PROMO_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
export const PROMO_IMAGE_MAX_BYTES = 2 * 1024 * 1024

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

/** Fiche client, pour répondre à une réclamation. */
/** Alerte de surveillance des opérations (lutte contre le blanchiment). */
export type MonitoringRule = 'VELOCITY' | 'STRUCTURING' | 'FAN_IN' | 'RAPID_CASH_OUT'
  | 'MERCHANT_REPEAT_PAYER' | 'MERCHANT_SPIKE' | 'MERCHANT_SELF_PAYMENT'
export interface MonitoringAlert {
  id: string
  rule: MonitoringRule
  ruleLabel: string
  /** Ce qui a déclenché l'alerte, en clair. */
  detail: string
  transactionId: string
  status: 'OPEN' | 'DISMISSED' | 'ESCALATED'
  createdAt: string
  /** Client surveillé ; null pour une alerte sur un marchand. */
  customerId: string | null
  customerName: string | null
  customerPhone: string | null
  customerStatus: string | null
  kycLevel: number | null
  /** Marchand surveillé ; null pour une alerte sur un client. */
  merchantId?: string | null
  merchantName?: string | null
  merchantCode?: string | null
  merchantStatus?: string | null
  resolvedByName?: string | null
  resolvedAt?: string | null
  resolutionNote?: string | null
}

/** Déclaration de soupçon à la CENAREF. */
export interface SuspicionReportSummary {
  id: string
  status: 'DRAFT' | 'SUBMITTED'
  customerId: string
  customerName: string
  customerPhone: string
  periodFrom: string
  periodTo: string
  createdByName: string
  createdAt: string
  submittedOn?: string | null
  cenarefReference?: string | null
}

export interface SuspicionReport {
  id: string
  status: 'DRAFT' | 'SUBMITTED'
  subject: {
    customerId: string
    fullName: string
    phone: string
    kycLevel: number
    status: string
    openedAt: string
    balanceMinor: number
    idType?: string | null
    idNumber?: string | null
    birthDate?: string | null
  }
  periodFrom: string
  periodTo: string
  facts: string
  analysis: string
  alerts: MonitoringAlert[]
  operations: HistoryItem[]
  totals: { count: number; inMinor: number; outMinor: number; truncated: boolean }
  createdByName: string
  createdAt: string
  updatedAt: string
  submittedByName?: string | null
  submittedOn?: string | null
  cenarefReference?: string | null
}

export interface CustomerSummary {
  id: string
  phone: string
  fullName: string
  kycLevel: number
  status: string
  balanceMinor: number
  /** Épargne du client, en plus du solde du portefeuille. */
  savingsMinor?: number
}

export type HistoryKind =
  | 'TOPUP' | 'TRANSFER_SENT' | 'TRANSFER_RECEIVED' | 'WITHDRAWAL' | 'WITHDRAWAL_REFUND' | 'MERCHANT_PAYMENT'
  | 'AIRTIME' | 'AIRTIME_REFUND' | 'BILL_PAYMENT' | 'BILL_REFUND' | 'SAVINGS_DEPOSIT' | 'SAVINGS_WITHDRAWAL' | 'ADJUSTMENT'
export type HistoryCategory = 'TOPUP' | 'TRANSFER' | 'WITHDRAWAL' | 'PAYMENT' | 'SAVINGS'

/** Une opération du client : une ligne par opération, montants en centimes. */
export interface HistoryItem {
  id: string
  receiptNumber: string
  kind: HistoryKind
  status: 'COMPLETED' | 'PENDING' | 'REVIEW' | 'FAILED'
  amountMinor: number
  feeMinor: number
  /** Effet sur le solde : positif quand l'argent entre. */
  netMinor: number
  currency: string
  createdAt: string
  counterpartyName?: string | null
  counterpartyPhone?: string | null
  operator?: Operator | null
  msisdn?: string | null
  /** Facture : n° de compteur ou d'abonné. */
  billReference?: string | null
  merchantCode?: string | null
  note?: string | null
  failureMessage?: string | null
}

export interface HistoryPage {
  items: HistoryItem[]
  nextCursor: string | null
}

export interface Receipt {
  item: HistoryItem
  holderName: string
  holderPhone: string
  issuedAt: string
}
export type MerchantStatus = 'ACTIVE' | 'SUSPENDED'

/** Un commerce qui accepte le paiement par QR. */
export interface Merchant {
  id: string
  code: string
  name: string
  category?: string | null
  city?: string | null
  /** Numéro qui reçoit un SMS à chaque paiement. */
  phone: string
  /** Points de base : 100 = 1 %. */
  commissionBps: number
  status: MerchantStatus
  /** Ce que Capital Invest doit au marchand. */
  balanceMinor: number
  /** Contenu du QR à imprimer. */
  qrPayload: string
  createdAt: string
  /** Compte mobile money des reversements, s'il est renseigné. */
  payoutOperator?: Operator | null
  payoutPhone?: string | null
  /** Reversements bloqués jusqu'à cette heure, après un changement du compte. */
  payoutBlockedUntil?: string | null
  /** Ce qui est dû part tout seul chaque nuit vers le compte de reversement. */
  autoPayout: boolean
}

export interface MerchantInput {
  name: string
  category: string | null
  city: string | null
  phone: string
  commissionBps: number
  payoutOperator: Operator | null
  payoutPhone: string | null
}

/** Un reversement au marchand. */
export interface MerchantPayout {
  id: string
  amountMinor: number
  operator: Operator
  phone: string
  status: 'PENDING' | 'CONFIRMED' | 'FAILED' | 'REVIEW'
  failureMessage?: string | null
  reviewReason?: string | null
  createdAt: string
  /** Parti avec la tâche de nuit, sans demande d'un agent. */
  automatic: boolean
}

export interface MerchantPayment {
  transactionId: string
  receiptNumber: string
  customerName: string
  customerPhone: string
  amountMinor: number
  commissionMinor: number
  createdAt: string
}

export interface MerchantDetail {
  merchant: Merchant
  payments: MerchantPayment[]
  payouts: MerchantPayout[]
}

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

/** Crédit téléphone ou facture sans réponse sûre du prestataire. */
export interface PendingPurchase {
  type: 'AIRTIME' | 'BILL'
  id: string
  customerName: string
  customerPhone: string
  /** « Crédit Vodacom », « SNEL prépayé »… */
  label: string
  /** Numéro rechargé, ou n° de compteur ou d'abonné. */
  target: string
  holderName: string | null
  /** Compteur prépayé : « payé » demande le code du compteur. */
  needsToken: boolean
  amountMinor: number
  provider: string
  status: 'PENDING' | 'REVIEW'
  reason: string | null
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
    // Un formulaire (envoi de fichier) part tel quel : le navigateur pose lui-même son Content-Type.
    const form = body instanceof FormData
    try {
      res = await this.fetcher(this.base + path, {
        method,
        headers: {
          Accept: 'application/json',
          ...(body !== undefined && !form ? { 'Content-Type': 'application/json' } : {}),
          ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
        },
        body: body === undefined ? undefined : form ? body : JSON.stringify(body),
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
  uploadPromotionImage = (id: string, file: Blob) => {
    const form = new FormData()
    form.append('file', file)
    return this.json<Promotion>('POST', `/v1/backoffice/promotions/${id}/image`, form)
  }
  async promotionImage(id: string): Promise<Blob> {
    return (await this.send('GET', `/v1/backoffice/promotions/${id}/image`)).blob()
  }

  // ---- Agents et journal ----
  agents = () => this.json<Agent[]>('GET', '/v1/backoffice/agents')
  createAgent = (a: { email: string; fullName: string; password: string; role: Role }) =>
    this.json<Agent>('POST', '/v1/backoffice/agents', a)
  setAgentActive = (id: string, active: boolean) =>
    this.json<Agent>('POST', `/v1/backoffice/agents/${id}/${active ? 'enable' : 'disable'}`)
  // ---- Clients ----
  findCustomer = (phone: string) => this.json<CustomerSummary>('GET', `/v1/backoffice/customers?phone=${encodeURIComponent(phone)}`)
  customer = (id: string) => this.json<CustomerSummary>('GET', `/v1/backoffice/customers/${id}`)
  /** Suspendre ou réactiver : un client suspendu ne peut plus se connecter ni faire bouger son argent. */
  setCustomerStatus = (id: string, status: 'ACTIVE' | 'SUSPENDED', reason: string) =>
    this.json<{ customerId: string; status: string }>('POST', `/v1/backoffice/customers/${id}/status`, { status, reason })
  // ---- Surveillance des opérations ----
  alerts = (status: 'OPEN' | 'CLOSED' | 'ALL' = 'OPEN', customerId?: string, merchantId?: string) =>
    this.json<MonitoringAlert[]>('GET', `/v1/backoffice/monitoring/alerts?status=${status}`
      + `${customerId ? `&customerId=${customerId}` : ''}${merchantId ? `&merchantId=${merchantId}` : ''}`)
  suspicionReports = () => this.json<SuspicionReportSummary[]>('GET', '/v1/backoffice/suspicion-reports')
  suspicionReport = (id: string) => this.json<SuspicionReport>('GET', `/v1/backoffice/suspicion-reports/${id}`)
  draftSuspicionReport = (customerId: string, alertId?: string) =>
    this.json<SuspicionReport>('POST', '/v1/backoffice/suspicion-reports', { customerId, alertId })
  saveSuspicionReport = (id: string, r: { periodFrom: string; periodTo: string; facts: string; analysis: string }) =>
    this.json<SuspicionReport>('PUT', `/v1/backoffice/suspicion-reports/${id}`, r)
  submitSuspicionReport = (id: string, submittedOn: string, cenarefReference: string) =>
    this.json<SuspicionReport>('POST', `/v1/backoffice/suspicion-reports/${id}/submit`, { submittedOn, cenarefReference })
  resolveAlert = (id: string, action: 'DISMISS' | 'SUSPEND', note: string) =>
    this.json<MonitoringAlert>('POST', `/v1/backoffice/monitoring/alerts/${id}/resolve`, { action, note })
  customerHistory = (id: string, category: HistoryCategory | null, cursor: string | null = null) => {
    const q = new URLSearchParams({ limit: '25' })
    if (category) q.set('category', category)
    if (cursor) q.set('cursor', cursor)
    return this.json<HistoryPage>('GET', `/v1/backoffice/customers/${id}/history?${q}`)
  }
  customerReceipt = (id: string, transactionId: string) =>
    this.json<Receipt>('GET', `/v1/backoffice/customers/${id}/history/${transactionId}`)
  // ---- Marchands ----
  merchants = () => this.json<Merchant[]>('GET', '/v1/backoffice/merchants')
  merchant = (id: string) => this.json<MerchantDetail>('GET', `/v1/backoffice/merchants/${id}`)
  createMerchant = (m: MerchantInput) => this.json<Merchant>('POST', '/v1/backoffice/merchants', m)
  setPayoutAccount = (id: string, operator: Operator, phone: string) =>
    this.json<Merchant>('POST', `/v1/backoffice/merchants/${id}/payout-account`, { operator, phone })
  /** requestId : un double clic ne reverse pas deux fois. */
  payoutMerchant = (id: string, amountMinor: number, requestId: string) =>
    this.json<MerchantPayout>('POST', `/v1/backoffice/merchants/${id}/payouts`, { amountMinor, requestId })
  setAutoPayout = (id: string, enabled: boolean) =>
    this.json<Merchant>('POST', `/v1/backoffice/merchants/${id}/auto-payout`, { enabled })
  setMerchantStatus = (id: string, status: MerchantStatus, reason: string) =>
    this.json<Merchant>('POST', `/v1/backoffice/merchants/${id}/status`, { status, reason })
  // ---- Recharges et rapprochement ----
  reviewTopups = () => this.json<ReviewTopup[]>('GET', '/v1/backoffice/topups/review')
  resolveTopup = (id: string, action: ResolveAction, note: string) =>
    this.json<unknown>('POST', `/v1/backoffice/topups/${id}/resolve`, { action, note })
  resolveWithdrawal = (id: string, action: WithdrawalDecision, note: string) =>
    this.json<unknown>('POST', `/v1/backoffice/withdrawals/${id}/resolve`, { action, note })
  pendingPurchases = () => this.json<PendingPurchase[]>('GET', '/v1/backoffice/purchases/review')
  resolvePurchase = (p: Pick<PendingPurchase, 'type' | 'id'>, action: 'DONE' | 'REFUND', note: string, token?: string) =>
    this.json<unknown>('POST', `/v1/backoffice/${p.type === 'AIRTIME' ? 'airtime' : 'bills'}/${p.id}/resolve`,
      token ? { action, note, token } : { action, note })
  reconciliationRuns = (limit = 14) => this.json<ReconciliationRun[]>('GET', `/v1/backoffice/reconciliation/runs?limit=${limit}`)
  runReconciliation = (businessDate?: string) =>
    this.json<ReconciliationRun>('POST', '/v1/backoffice/reconciliation/runs', businessDate ? { businessDate } : {})
  reconciliationItems = (open = true) =>
    this.json<ReconciliationItem[]>('GET', `/v1/backoffice/reconciliation/items?open=${open}`)
  resolveItem = (id: string, action: ResolveAction, note: string) =>
    this.json<ReconciliationItem>('POST', `/v1/backoffice/reconciliation/items/${id}/resolve`, { action, note })

  audit = (limit = 200) => this.json<AuditEntry[]>('GET', `/v1/backoffice/audit?limit=${limit}`)
}
