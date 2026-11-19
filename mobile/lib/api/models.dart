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

class Entry {
  Entry({required this.id, required this.direction, required this.amountMinor, required this.createdAt});

  final String id;

  /// CREDIT : argent reçu ; DEBIT : argent sorti (envoi ou frais).
  final String direction;
  final int amountMinor;
  final DateTime createdAt;

  bool get isCredit => direction == 'CREDIT';

  factory Entry.fromJson(Map<String, dynamic> j) => Entry(
    id: j['id'],
    direction: j['direction'],
    amountMinor: j['amountMinor'],
    createdAt: DateTime.parse(j['createdAt']),
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
  Topup({required this.id, required this.status, required this.amountMinor});

  final String id;

  /// PENDING, CONFIRMED ou FAILED.
  final String status;
  final int amountMinor;

  factory Topup.fromJson(Map<String, dynamic> j) =>
      Topup(id: j['id'], status: j['status'], amountMinor: j['amountMinor']);
}

class TransferResult {
  TransferResult({required this.amountMinor, required this.feeMinor, required this.totalDebitedMinor});

  final int amountMinor;
  final int feeMinor;
  final int totalDebitedMinor;

  factory TransferResult.fromJson(Map<String, dynamic> j) =>
      TransferResult(amountMinor: j['amountMinor'], feeMinor: j['feeMinor'], totalDebitedMinor: j['totalDebitedMinor']);
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
    required this.body,
    this.ctaLabel,
    this.action,
    this.style = 'OCHRE',
  });

  final String id;
  final String title;
  final String body;
  final String? ctaLabel;

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
  );
}
