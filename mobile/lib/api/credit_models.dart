// Crédit express : offre, simulation, crédit en cours et échéances (routes /v1/me/credit).

DateTime? _date(Object? v) => v == null ? null : DateTime.parse(v as String);

/// Ce qui explique le score : ce qui aide, ou ce qui freine.
class CreditFactor {
  CreditFactor({required this.label, required this.positive});

  final String label;
  final bool positive;

  factory CreditFactor.fromJson(Map<String, dynamic> j) => CreditFactor(label: j['label'], positive: j['positive'] ?? false);
}

List<CreditFactor> _factors(Object? v) => [for (final f in (v as List? ?? const [])) CreditFactor.fromJson(f)];
List<String> _strings(Object? v) => [for (final s in (v as List? ?? const [])) s as String];

class CreditOffer {
  CreditOffer({
    required this.score,
    required this.ceilingMinor,
    required this.minAmountMinor,
    required this.durationsMonths,
    required this.monthlyRateBp,
    required this.aprBp,
    required this.reviewThresholdMinor,
    required this.factors,
    required this.levers,
  });

  final int score;
  final int ceilingMinor;
  final int minAmountMinor;
  final List<int> durationsMonths;
  final int monthlyRateBp;

  /// Taux effectif global annuel, en points de base.
  final int aprBp;

  /// Au-dessus, un agent de crédit examine la demande avant le versement.
  final int reviewThresholdMinor;
  final List<CreditFactor> factors;
  final List<String> levers;

  factory CreditOffer.fromJson(Map<String, dynamic> j) => CreditOffer(
    score: j['score'],
    ceilingMinor: j['ceilingMinor'],
    minAmountMinor: j['minAmountMinor'],
    durationsMonths: [for (final d in j['durationsMonths'] as List) d as int],
    monthlyRateBp: j['monthlyRateBp'],
    aprBp: j['aprBp'],
    reviewThresholdMinor: j['reviewThresholdMinor'],
    factors: _factors(j['factors']),
    levers: _strings(j['levers']),
  );
}

/// Pourquoi il n'y a pas d'offre, et ce que le client peut faire.
class CreditRefusal {
  CreditRefusal({required this.code, required this.message, this.score, required this.factors, required this.levers});

  final String code;
  final String message;
  final int? score;
  final List<CreditFactor> factors;
  final List<String> levers;

  bool get needsKyc => code == 'KYC_LEVEL_REQUIRED';

  factory CreditRefusal.fromJson(Map<String, dynamic> j) => CreditRefusal(
    code: j['code'],
    message: j['message'],
    score: j['score'],
    factors: _factors(j['factors']),
    levers: _strings(j['levers']),
  );
}

class CreditInstalment {
  CreditInstalment({
    required this.number,
    required this.dueDate,
    required this.amountMinor,
    required this.principalMinor,
    required this.interestMinor,
    required this.paidMinor,
    required this.remainingMinor,
    required this.status,
  });

  final int number;
  final DateTime dueDate;
  final int amountMinor;
  final int principalMinor;
  final int interestMinor;
  final int paidMinor;
  final int remainingMinor;

  /// UPCOMING, LATE, PAID, CANCELLED (remplacée par un nouvel échéancier).
  final String status;

  bool get isPaid => status == 'PAID';
  bool get isLate => status == 'LATE';

  factory CreditInstalment.fromJson(Map<String, dynamic> j) => CreditInstalment(
    number: j['number'],
    dueDate: DateTime.parse(j['dueDate']),
    amountMinor: j['amountMinor'],
    principalMinor: j['principalMinor'],
    interestMinor: j['interestMinor'],
    paidMinor: j['paidMinor'] ?? 0,
    remainingMinor: j['remainingMinor'] ?? j['amountMinor'],
    status: j['status'] ?? 'UPCOMING',
  );
}

class Credit {
  Credit({
    required this.id,
    required this.status,
    required this.principalMinor,
    required this.durationMonths,
    required this.monthlyRateBp,
    required this.aprBp,
    required this.totalInterestMinor,
    required this.totalPaidMinor,
    required this.outstandingPrincipalMinor,
    required this.dueNowMinor,
    required this.payoffMinor,
    this.nextInstalment,
    required this.daysLate,
    required this.instalments,
    this.decisionNote,
    required this.createdAt,
    this.disbursedAt,
    this.closedAt,
  });

  final String id;

  /// PENDING_REVIEW, REJECTED, ACTIVE, LATE, DEFAULTED, WRITTEN_OFF, REPAID.
  final String status;
  final int principalMinor;
  final int durationMonths;
  final int monthlyRateBp;
  final int aprBp;
  final int totalInterestMinor;
  final int totalPaidMinor;
  final int outstandingPrincipalMinor;
  final int dueNowMinor;
  final int payoffMinor;
  final CreditInstalment? nextInstalment;
  final int daysLate;
  final List<CreditInstalment> instalments;
  final String? decisionNote;
  final DateTime createdAt;
  final DateTime? disbursedAt;
  final DateTime? closedAt;

  bool get pendingReview => status == 'PENDING_REVIEW';
  bool get running => status == 'ACTIVE' || status == 'LATE' || status == 'DEFAULTED';
  bool get late => status == 'LATE' || status == 'DEFAULTED';

  factory Credit.fromJson(Map<String, dynamic> j) => Credit(
    id: j['id'],
    status: j['status'],
    principalMinor: j['principalMinor'],
    durationMonths: j['durationMonths'],
    monthlyRateBp: j['monthlyRateBp'],
    aprBp: j['aprBp'],
    totalInterestMinor: j['totalInterestMinor'] ?? 0,
    totalPaidMinor: j['totalPaidMinor'] ?? 0,
    outstandingPrincipalMinor: j['outstandingPrincipalMinor'] ?? 0,
    dueNowMinor: j['dueNowMinor'] ?? 0,
    payoffMinor: j['payoffMinor'] ?? 0,
    nextInstalment: j['nextInstalment'] == null ? null : CreditInstalment.fromJson(j['nextInstalment']),
    daysLate: j['daysLate'] ?? 0,
    instalments: [for (final i in (j['instalments'] as List? ?? const [])) CreditInstalment.fromJson(i)],
    decisionNote: j['decisionNote'],
    createdAt: DateTime.parse(j['createdAt']),
    disbursedAt: _date(j['disbursedAt']),
    closedAt: _date(j['closedAt']),
  );
}

class CreditOverview {
  CreditOverview({this.offer, this.refusal, this.current, required this.past});

  final CreditOffer? offer;
  final CreditRefusal? refusal;

  /// Crédit en cours, ou demande en examen.
  final Credit? current;
  final List<Credit> past;

  factory CreditOverview.fromJson(Map<String, dynamic> j) => CreditOverview(
    offer: j['offer'] == null ? null : CreditOffer.fromJson(j['offer']),
    refusal: j['refusal'] == null ? null : CreditRefusal.fromJson(j['refusal']),
    current: j['current'] == null ? null : Credit.fromJson(j['current']),
    past: [for (final c in (j['past'] as List? ?? const [])) Credit.fromJson(c)],
  );
}

class PlannedInstalment {
  PlannedInstalment({required this.number, required this.dueDate, required this.principalMinor, required this.interestMinor});

  final int number;
  final DateTime dueDate;
  final int principalMinor;
  final int interestMinor;

  int get amountMinor => principalMinor + interestMinor;

  factory PlannedInstalment.fromJson(Map<String, dynamic> j) => PlannedInstalment(
    number: j['number'],
    dueDate: DateTime.parse(j['dueDate']),
    principalMinor: j['principalMinor'],
    interestMinor: j['interestMinor'],
  );
}

/// Simulation avant d'accepter : mensualité, coût total, TEG, échéancier.
class CreditQuote {
  CreditQuote({
    required this.amountMinor,
    required this.durationMonths,
    required this.monthlyRateBp,
    required this.aprBp,
    required this.instalmentMinor,
    required this.totalInterestMinor,
    required this.totalDueMinor,
    required this.needsReview,
    required this.schedule,
  });

  final int amountMinor;
  final int durationMonths;
  final int monthlyRateBp;
  final int aprBp;
  final int instalmentMinor;
  final int totalInterestMinor;
  final int totalDueMinor;
  final bool needsReview;
  final List<PlannedInstalment> schedule;

  factory CreditQuote.fromJson(Map<String, dynamic> j) => CreditQuote(
    amountMinor: j['amountMinor'],
    durationMonths: j['durationMonths'],
    monthlyRateBp: j['monthlyRateBp'],
    aprBp: j['aprBp'],
    instalmentMinor: j['instalmentMinor'],
    totalInterestMinor: j['totalInterestMinor'],
    totalDueMinor: j['totalDueMinor'],
    needsReview: j['needsReview'] ?? false,
    schedule: [for (final s in j['schedule'] as List) PlannedInstalment.fromJson(s)],
  );
}

/// 250 → « 2,5 % », 3449 → « 34,49 % ».
String formatRate(int bp) {
  final whole = bp ~/ 100;
  final cents = bp % 100;
  if (cents == 0) return '$whole %';
  final dec = cents % 10 == 0 ? '${cents ~/ 10}' : cents.toString().padLeft(2, '0');
  return '$whole,$dec %';
}
