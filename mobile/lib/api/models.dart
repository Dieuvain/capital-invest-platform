/// Modèles renvoyés par l'API Capital Invest. Les montants sont en centimes de dollar.
library;

class Customer {
  Customer({required this.id, required this.phone, required this.fullName, required this.kycLevel});

  final String id;
  final String phone;
  final String fullName;
  final int kycLevel;

  factory Customer.fromJson(Map<String, dynamic> j) =>
      Customer(id: j['id'], phone: j['phone'], fullName: j['fullName'], kycLevel: j['kycLevel']);
}

class SessionTokens {
  SessionTokens({required this.accessToken, required this.customer});

  final String accessToken;
  final Customer customer;

  factory SessionTokens.fromJson(Map<String, dynamic> j) =>
      SessionTokens(accessToken: j['accessToken'], customer: Customer.fromJson(j['customer']['customer']));
}

/// Un mois de relevé disponible. [partial] : le mois en cours, arrêté à aujourd'hui.
class StatementMonth {
  StatementMonth(this.month, this.partial);

  /// « 2026-11 ».
  final String month;
  final bool partial;

  factory StatementMonth.fromJson(Map<String, dynamic> j) => StatementMonth(j['month'], j['partial'] == true);
}

/// Relevé d'un mois : solde d'ouverture, opérations du plus ancien au plus récent, solde de clôture.
class Statement {
  Statement({
    required this.month,
    required this.from,
    required this.to,
    required this.partial,
    required this.openingBalanceMinor,
    required this.totalInMinor,
    required this.totalOutMinor,
    required this.feesMinor,
    required this.closingBalanceMinor,
    required this.items,
  });

  final String month;
  final DateTime from;
  final DateTime to;
  final bool partial;
  final int openingBalanceMinor;
  final int totalInMinor;

  /// Sorties, frais compris (nombre positif).
  final int totalOutMinor;
  final int feesMinor;
  final int closingBalanceMinor;
  final List<HistoryItem> items;

  factory Statement.fromJson(Map<String, dynamic> j) => Statement(
    month: j['month'],
    from: DateTime.parse(j['from']),
    to: DateTime.parse(j['to']),
    partial: j['partial'] == true,
    openingBalanceMinor: j['openingBalanceMinor'],
    totalInMinor: j['totalInMinor'],
    totalOutMinor: j['totalOutMinor'],
    feesMinor: j['feesMinor'],
    closingBalanceMinor: j['closingBalanceMinor'],
    items: (j['items'] as List).map((e) => HistoryItem.fromJson(e)).toList(),
  );
}

/// Une connexion à ce compte, pour l'écran Sécurité.
class DeviceSession {
  DeviceSession({
    required this.id,
    required this.deviceName,
    required this.createdAt,
    required this.lastSeenAt,
    required this.current,
    required this.active,
  });

  final String id;
  final String? deviceName;
  final DateTime createdAt;
  final DateTime lastSeenAt;

  /// La session de ce téléphone.
  final bool current;

  /// Encore utilisable : ni fermée, ni expirée.
  final bool active;

  factory DeviceSession.fromJson(Map<String, dynamic> j) => DeviceSession(
    id: j['id'],
    deviceName: j['deviceName'],
    createdAt: DateTime.parse(j['createdAt']),
    lastSeenAt: DateTime.parse(j['lastSeenAt']),
    current: j['current'] == true,
    active: j['active'] == true,
  );
}

class Limits {
  Limits({
    required this.kycLevel,
    required this.perTransactionMinor,
    required this.dailyOutMinor,
    required this.dailyOutRemainingMinor,
    required this.maxBalanceMinor,
    this.outgoingBlockedUntil,
  });

  final int kycLevel;
  final int perTransactionMinor;
  final int dailyOutMinor;
  final int dailyOutRemainingMinor;
  final int maxBalanceMinor;
  final DateTime? outgoingBlockedUntil;

  factory Limits.fromJson(Map<String, dynamic> j) => Limits(
    kycLevel: j['kycLevel'],
    perTransactionMinor: j['perTransactionMinor'],
    dailyOutMinor: j['dailyOutMinor'],
    dailyOutRemainingMinor: j['dailyOutRemainingMinor'],
    maxBalanceMinor: j['maxBalanceMinor'],
    outgoingBlockedUntil: j['outgoingBlockedUntil'] == null ? null : DateTime.parse(j['outgoingBlockedUntil']),
  );
}

class FeeQuote {
  FeeQuote({required this.amountMinor, required this.feeMinor, required this.totalMinor});

  final int amountMinor;
  final int feeMinor;
  final int totalMinor;

  factory FeeQuote.fromJson(Map<String, dynamic> j) =>
      FeeQuote(amountMinor: j['amountMinor'], feeMinor: j['feeMinor'], totalMinor: j['totalMinor']);
}

enum Operator {
  mpesa('MPESA', 'M-Pesa'),
  airtel('AIRTEL', 'Airtel Money'),
  orange('ORANGE', 'Orange Money');

  const Operator(this.apiName, this.label);

  /// Nom attendu par le serveur.
  final String apiName;
  final String label;
}

class Topup {
  Topup({required this.id, required this.status, required this.amountMinor, this.failureCode});

  final String id;

  /// PENDING, CONFIRMED, FAILED, ou REVIEW (paiement en vérification par l'équipe).
  final String status;
  final int amountMinor;

  /// Motif d'un refus, tel que l'opérateur le donne (INSUFFICIENT_BALANCE…).
  final String? failureCode;

  factory Topup.fromJson(Map<String, dynamic> j) =>
      Topup(id: j['id'], status: j['status'], amountMinor: j['amountMinor'], failureCode: j['failureCode']);
}

/// Retrait vers le compte mobile money du client.
class Withdrawal {
  Withdrawal({
    required this.id,
    required this.status,
    required this.amountMinor,
    required this.feeMinor,
    required this.msisdn,
    this.failureCode,
  });

  final String id;

  /// PENDING, CONFIRMED (argent versé), FAILED (remboursé, frais compris) ou REVIEW (en vérification).
  final String status;
  final int amountMinor;
  final int feeMinor;

  /// Numéro qui reçoit l'argent, sans « + » : 243970000001.
  final String msisdn;
  final String? failureCode;

  factory Withdrawal.fromJson(Map<String, dynamic> j) => Withdrawal(
    id: j['id'],
    status: j['status'],
    amountMinor: j['amountMinor'],
    feeMinor: j['feeMinor'],
    msisdn: j['msisdn'],
    failureCode: j['failureCode'],
  );
}

/// Opérateur le plus probable d'après le préfixe du numéro (+243 97… Airtel, 81… Vodacom M-Pesa, 84… Orange).
Operator? operatorForPhone(String phone) {
  final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
  final local = digits.startsWith('243') ? digits.substring(3) : digits;
  if (local.length < 2) return null;
  return switch (local.substring(0, 2)) {
    '81' || '82' || '83' => Operator.mpesa,
    '97' || '98' || '99' => Operator.airtel,
    '80' || '84' || '85' || '89' => Operator.orange,
    _ => null,
  };
}

class TransferResult {
  TransferResult({required this.amountMinor, required this.feeMinor, required this.totalDebitedMinor});

  final int amountMinor;
  final int feeMinor;
  final int totalDebitedMinor;

  factory TransferResult.fromJson(Map<String, dynamic> j) =>
      TransferResult(amountMinor: j['amountMinor'], feeMinor: j['feeMinor'], totalDebitedMinor: j['totalDebitedMinor']);
}

/// Le commerce derrière un QR scanné : ce que le client vérifie avant de payer.
class MerchantInfo {
  MerchantInfo({required this.code, required this.name, this.category, this.city});

  final String code;
  final String name;
  final String? category;
  final String? city;

  factory MerchantInfo.fromJson(Map<String, dynamic> j) =>
      MerchantInfo(code: j['code'], name: j['name'], category: j['category'], city: j['city']);
}

class MerchantPaymentResult {
  MerchantPaymentResult({
    required this.transactionId,
    required this.receiptNumber,
    required this.merchantName,
    required this.amountMinor,
    required this.createdAt,
  });

  final String transactionId;
  final String receiptNumber;
  final String merchantName;
  final int amountMinor;
  final DateTime createdAt;

  factory MerchantPaymentResult.fromJson(Map<String, dynamic> j) => MerchantPaymentResult(
    transactionId: j['transactionId'],
    receiptNumber: j['receiptNumber'],
    merchantName: j['merchantName'],
    amountMinor: j['amountMinor'],
    createdAt: DateTime.parse(j['createdAt']),
  );
}

/// Code marchand lu dans un QR Capital Invest (capitalinvest://pay?m=K7P2QX) ou tapé à la main
/// (« k7p 2qx »). Null si ce n'est pas un code marchand.
String? merchantCodeFrom(String raw) {
  final text = raw.trim();
  final uri = Uri.tryParse(text);
  final fromQr = (uri != null && uri.scheme == 'capitalinvest') ? uri.queryParameters['m'] : null;
  if (uri != null && uri.hasScheme && fromQr == null) return null; // un autre QR (site web, Wi-Fi…)
  final code = (fromQr ?? text).toUpperCase().replaceAll(RegExp(r'[^A-Z0-9]'), '');
  return RegExp(r'^[A-Z2-9]{6}$').hasMatch(code) ? code : null;
}

class KycState {
  KycState({required this.kycLevel, this.requestStatus, this.rejectionReason});

  final int kycLevel;

  /// PENDING, APPROVED, REJECTED, ou null si aucune demande.
  final String? requestStatus;
  final String? rejectionReason;

  factory KycState.fromJson(Map<String, dynamic> j) => KycState(
    kycLevel: j['kycLevel'],
    requestStatus: j['request']?['status'],
    rejectionReason: j['request']?['rejectionReason'],
  );
}

/// Promotion affichée dans le bandeau défilant du tableau de bord, gérée depuis le back-office.
class Promotion {
  Promotion({
    required this.id,
    required this.title,
    this.body,
    this.ctaLabel,
    this.action,
    this.style = 'OCHRE',
    this.imageUrl,
  });

  final String id;

  /// Toujours présent : c'est ce que lit le lecteur d'écran quand la promotion est un visuel.
  final String title;

  /// Texte de la carte de secours ; souvent vide quand la promotion a un visuel.
  final String? body;
  final String? ctaLabel;

  /// Visuel du graphiste (adresse relative, à charger avec le jeton). Null : carte de secours dessinée par l'app.
  final String? imageUrl;

  /// Action au toucher : KYC, TOPUP, TRANSFER, LIMITS, ou null.
  final String? action;

  /// Habillage : OCHRE, INK, GREEN ou SKY.
  final String style;

  factory Promotion.fromJson(Map<String, dynamic> j) => Promotion(
    id: j['id'],
    title: j['title'],
    body: j['body'],
    ctaLabel: j['ctaLabel'],
    action: j['action'],
    style: j['style'] ?? 'OCHRE',
    imageUrl: j['imageUrl'],
  );
}

/// Une opération de l'historique : une ligne par opération (pas par écriture comptable).
class HistoryItem {
  HistoryItem({
    required this.id,
    required this.receiptNumber,
    required this.kind,
    required this.status,
    required this.amountMinor,
    required this.feeMinor,
    required this.netMinor,
    required this.createdAt,
    this.counterpartyName,
    this.counterpartyPhone,
    this.operator,
    this.msisdn,
    this.merchantCode,
    this.note,
    this.failureMessage,
  });

  final String id;
  final String receiptNumber;

  /// TOPUP, TRANSFER_SENT, TRANSFER_RECEIVED, WITHDRAWAL, WITHDRAWAL_REFUND, MERCHANT_PAYMENT, ADJUSTMENT.
  final String kind;

  /// COMPLETED, PENDING, REVIEW ou FAILED.
  final String status;
  final int amountMinor;
  final int feeMinor;

  /// Effet sur le solde : positif quand l'argent entre.
  final int netMinor;
  final DateTime createdAt;
  final String? counterpartyName;
  final String? counterpartyPhone;
  final String? operator;
  final String? msisdn;

  /// Code du commerce payé (paiement marchand).
  final String? merchantCode;
  final String? note;
  final String? failureMessage;

  bool get isIn => netMinor > 0;

  /// Nom lisible de l'opérateur (M-Pesa…), si l'opération passe par le mobile money.
  String? get operatorLabel => Operator.values.where((o) => o.apiName == operator).firstOrNull?.label;

  factory HistoryItem.fromJson(Map<String, dynamic> j) => HistoryItem(
    id: j['id'],
    receiptNumber: j['receiptNumber'],
    kind: j['kind'],
    status: j['status'] ?? 'COMPLETED',
    amountMinor: j['amountMinor'],
    feeMinor: j['feeMinor'] ?? 0,
    netMinor: j['netMinor'],
    createdAt: DateTime.parse(j['createdAt']),
    counterpartyName: j['counterpartyName'],
    counterpartyPhone: j['counterpartyPhone'],
    operator: j['operator'],
    msisdn: j['msisdn'],
    merchantCode: j['merchantCode'],
    note: j['note'],
    failureMessage: j['failureMessage'],
  );
}

class HistoryPage {
  HistoryPage(this.items, this.nextCursor);

  final List<HistoryItem> items;

  /// Null : il n'y a plus rien à charger.
  final String? nextCursor;

  factory HistoryPage.fromJson(Map<String, dynamic> j) =>
      HistoryPage((j['items'] as List).map((e) => HistoryItem.fromJson(e)).toList(), j['nextCursor']);
}

/// Reçu d'une opération, à montrer ou à partager.
class Receipt {
  Receipt({required this.item, required this.holderName, required this.holderPhone});

  final HistoryItem item;
  final String holderName;
  final String holderPhone;

  factory Receipt.fromJson(Map<String, dynamic> j) =>
      Receipt(item: HistoryItem.fromJson(j['item']), holderName: j['holderName'], holderPhone: j['holderPhone']);
}

/// Notification reçue par le client (aussi envoyée par SMS).
class AppNotification {
  AppNotification({
    required this.id,
    required this.kind,
    required this.title,
    required this.body,
    required this.read,
    required this.createdAt,
    this.transactionId,
  });

  final String id;

  /// TOPUP, TRANSFER_RECEIVED, TRANSFER_SENT, WITHDRAWAL_PAID, WITHDRAWAL_REFUNDED, KYC_APPROVED, KYC_REJECTED, PIN_CHANGED…
  final String kind;
  final String title;
  final String body;
  final bool read;
  final DateTime createdAt;

  /// Opération liée, dont on peut ouvrir le reçu.
  final String? transactionId;

  factory AppNotification.fromJson(Map<String, dynamic> j) => AppNotification(
    id: j['id'],
    kind: j['kind'],
    title: j['title'],
    body: j['body'],
    read: j['read'] ?? false,
    createdAt: DateTime.parse(j['createdAt']),
    transactionId: j['transactionId'],
  );
}

class NotificationInbox {
  NotificationInbox(this.items, this.unread);

  final List<AppNotification> items;
  final int unread;

  factory NotificationInbox.fromJson(Map<String, dynamic> j) =>
      NotificationInbox((j['items'] as List).map((e) => AppNotification.fromJson(e)).toList(), j['unread'] ?? 0);
}

/// Demande d'argent entre deux clients. [incoming] : on vous demande de payer.
class MoneyRequest {
  MoneyRequest({
    required this.id,
    required this.incoming,
    required this.counterpartyName,
    required this.counterpartyPhone,
    required this.amountMinor,
    required this.status,
    required this.createdAt,
    required this.expiresAt,
    this.note,
    this.transactionId,
  });

  final String id;
  final bool incoming;

  /// Le demandeur pour une demande reçue, le client sollicité pour une demande faite.
  final String counterpartyName;
  final String counterpartyPhone;
  final int amountMinor;
  final String? note;

  /// PENDING, PAID, DECLINED, CANCELLED ou EXPIRED.
  final String status;

  /// Envoi qui a payé la demande.
  final String? transactionId;
  final DateTime createdAt;
  final DateTime expiresAt;

  bool get pending => status == 'PENDING';

  factory MoneyRequest.fromJson(Map<String, dynamic> j) => MoneyRequest(
    id: j['id'],
    incoming: j['direction'] == 'INCOMING',
    counterpartyName: j['counterpartyName'],
    counterpartyPhone: j['counterpartyPhone'],
    amountMinor: j['amountMinor'],
    note: j['note'],
    status: j['status'],
    transactionId: j['transactionId'],
    createdAt: DateTime.parse(j['createdAt']),
    expiresAt: DateTime.parse(j['expiresAt']),
  );
}

class MoneyRequestList {
  MoneyRequestList({required this.toPay, required this.recent});

  /// Demandes reçues à payer ou refuser, plus anciennes d'abord.
  final List<MoneyRequest> toPay;
  final List<MoneyRequest> recent;

  factory MoneyRequestList.fromJson(Map<String, dynamic> j) => MoneyRequestList(
    toPay: [for (final r in j['toPay'] as List) MoneyRequest.fromJson(r)],
    recent: [for (final r in j['recent'] as List) MoneyRequest.fromJson(r)],
  );
}
