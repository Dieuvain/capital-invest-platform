import 'dart:convert';
import 'dart:math';

import 'package:http/http.dart' as http;

/// Crédit express du faux serveur : mêmes règles que l'API (niveau 2, plafond, PIN, un crédit à la fois).
/// Taux de 2,5 % par mois : un crédit de 100 $ sur 3 mois a trois échéances de 35,02 $, comme sur le serveur.
class FakeCredit {
  FakeCredit({required this.kycLevel, required this.pin, required this.balance, required this.moveMoney});

  final int Function() kycLevel;
  final String Function() pin;
  final int Function() balance;

  /// Crédite (positif) ou débite (négatif) le solde.
  final void Function(int minor) moveMoney;

  int score = 60;
  int ceilingMinor = 15000;
  int reviewThresholdMinor = 30000;
  Map<String, dynamic>? current;
  final past = <Map<String, dynamic>>[];
  final _keys = <String, Map<String, dynamic>>{};

  static const factors = [
    {'label': 'Compte ouvert depuis 100 jours', 'positive': true},
    {'label': '300,00 \$ reçus par mois en moyenne sur 3 mois', 'positive': true},
    {'label': 'De l\'argent reçu 3 mois sur 3', 'positive': true},
  ];
  static const levers = ['Faites verser votre paie ou rechargez votre compte Capital Invest chaque mois.'];

  /// Échéancier à mensualités constantes, comme le serveur : 2,5 % par mois sur le capital restant.
  static List<Map<String, dynamic>> plan(int principal, int months, {DateTime? start}) {
    final from = start ?? DateTime.utc(2026, 11, 25);
    const r = 0.025;
    final payment = (principal * r / (1 - pow(1 + r, -months))).ceil();
    var remaining = principal;
    return [
      for (var k = 1; k <= months; k++)
        () {
          final interest = (remaining * r).round();
          final p = k == months ? remaining : payment - interest;
          remaining -= p;
          final due = DateTime.utc(from.year, from.month + k, from.day).toIso8601String().substring(0, 10);
          return {
            'number': k,
            'dueDate': due,
            'principalMinor': p,
            'interestMinor': interest,
            'amountMinor': p + interest,
            'paidMinor': 0,
            'remainingMinor': p + interest,
            'status': 'UPCOMING',
          };
        }(),
    ];
  }

  Map<String, dynamic> credit(String status, int principal, int months) {
    final instalments = plan(principal, months);
    return {
      'id': 'cr-${past.length + 1}',
      'status': status,
      'principalMinor': principal,
      'durationMonths': months,
      'monthlyRateBp': 250,
      'aprBp': 3449,
      'totalInterestMinor': instalments.fold<int>(0, (s, i) => s + (i['interestMinor'] as int)),
      'totalPaidMinor': 0,
      'outstandingPrincipalMinor': principal,
      'dueNowMinor': 0,
      'payoffMinor': principal,
      'nextInstalment': status == 'PENDING_REVIEW' ? null : instalments.first,
      'daysLate': 0,
      'instalments': status == 'PENDING_REVIEW' ? [] : instalments,
      'score': score,
      'rescheduleCount': 0,
      'createdAt': '2026-11-25T08:00:00Z',
    };
  }

  /// Met le crédit en cours en retard sur sa première échéance.
  void makeLate({int days = 6}) {
    final c = current!;
    final first = (c['instalments'] as List).first as Map<String, dynamic>;
    first['status'] = 'LATE';
    c['status'] = 'LATE';
    c['dueNowMinor'] = first['remainingMinor'];
    c['payoffMinor'] = (c['outstandingPrincipalMinor'] as int) + (first['interestMinor'] as int);
    c['daysLate'] = days;
  }

  Map<String, dynamic> get _offer => {
    'score': score,
    'ceilingMinor': ceilingMinor,
    'minAmountMinor': 1000,
    'durationsMonths': [1, 2, 3, 6],
    'monthlyRateBp': 250,
    'aprBp': 3449,
    'reviewThresholdMinor': reviewThresholdMinor,
    'factors': factors,
    'levers': levers,
  };

  Map<String, dynamic> get overview {
    if (current != null) return {'current': current, 'past': past};
    if (kycLevel() < 2) {
      return {
        'refusal': {
          'code': 'KYC_LEVEL_REQUIRED',
          'message': 'Le crédit express demande le niveau 2.',
          'factors': [],
          'levers': ['Envoyez votre pièce d\'identité depuis le Profil pour passer au niveau 2.'],
        },
        'past': past,
      };
    }
    if (score < 40) {
      return {
        'refusal': {
          'code': 'SCORE_TOO_LOW',
          'message': 'Votre score ($score sur 100) ne permet pas encore de crédit.',
          'score': score,
          'factors': [
            {'label': 'Compte ouvert depuis 12 jours', 'positive': false},
            {'label': 'Aucune recharge ni aucun envoi reçu ces 3 derniers mois', 'positive': false},
          ],
          'levers': levers,
        },
        'past': past,
      };
    }
    return {'offer': _offer, 'past': past};
  }

  /// Répond aux routes /v1/me/credit ; null si la route n'est pas une route de crédit.
  http.Response? handle(http.Request req) {
    final path = req.url.path;
    if (!path.startsWith('/v1/me/credit')) return null;
    final body = req.body.isEmpty || req.body == 'null' ? <String, dynamic>{} : jsonDecode(req.body) as Map<String, dynamic>;
    if (path == '/v1/me/credit' && req.method == 'GET') return _json(overview);
    if (path == '/v1/me/credit/quote' || path == '/v1/me/credit') {
      if (kycLevel() < 2) return _error(403, 'KYC_LEVEL_REQUIRED', 'Le crédit express demande le niveau 2.');
      final amount = body['amountMinor'] as int;
      final months = body['durationMonths'] as int;
      if (amount < 1000 || amount > ceilingMinor) {
        return _error(422, 'INVALID_CREDIT_AMOUNT', 'Choisissez un montant entre 10,00 \$ et ${ceilingMinor ~/ 100},00 \$');
      }
      if (path.endsWith('/quote')) {
        final p = plan(amount, months);
        final interest = p.fold<int>(0, (s, i) => s + (i['interestMinor'] as int));
        return _json({
          'amountMinor': amount,
          'durationMonths': months,
          'monthlyRateBp': 250,
          'aprBp': 3449,
          'instalmentMinor': p.first['amountMinor'],
          'totalInterestMinor': interest,
          'totalDueMinor': amount + interest,
          'needsReview': amount > reviewThresholdMinor,
          'schedule': p,
        });
      }
      final key = req.headers['Idempotency-Key']!;
      if (_keys[key] != null) return _json(_keys[key]!, 201);
      if (body['pin'] != pin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
      if (current != null) return _error(409, 'CREDIT_ALREADY_OPEN', 'Vous avez déjà un crédit en cours');
      final review = amount > reviewThresholdMinor;
      current = credit(review ? 'PENDING_REVIEW' : 'ACTIVE', amount, months);
      if (!review) moveMoney(amount);
      return _json(_keys[key] = current!, 201);
    }
    final m = RegExp(r'^/v1/me/credit/([^/]+)(?:/([a-z]+))?$').firstMatch(path);
    final c = current;
    if (m == null || c == null || c['id'] != m.group(1)) return _error(404, 'CREDIT_NOT_FOUND', 'Crédit introuvable');
    switch (m.group(2)) {
      case null:
        return _json(c);
      case 'cancel':
        c['status'] = 'REJECTED';
        c['decisionNote'] = 'Demande retirée par le client';
        past.insert(0, c);
        current = null;
        return _json(c);
      case 'repayments':
        if (body['pin'] != pin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
        final instalments = (c['instalments'] as List).cast<Map<String, dynamic>>();
        final open = instalments.where((i) => i['status'] != 'PAID').toList();
        final full = body['mode'] == 'FULL';
        final amount = full ? c['payoffMinor'] as int : (c['dueNowMinor'] as int > 0 ? c['dueNowMinor'] as int : open.first['remainingMinor'] as int);
        if (balance() < amount) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant pour rembourser');
        moveMoney(-amount);
        for (final i in full ? open : [open.first]) {
          i['paidMinor'] = i['amountMinor'];
          i['remainingMinor'] = 0;
          i['status'] = 'PAID';
          c['outstandingPrincipalMinor'] = (c['outstandingPrincipalMinor'] as int) - (i['principalMinor'] as int);
        }
        c['totalPaidMinor'] = (c['totalPaidMinor'] as int) + amount;
        c['dueNowMinor'] = 0;
        c['daysLate'] = 0;
        final left = instalments.where((i) => i['status'] != 'PAID').toList();
        c['nextInstalment'] = left.firstOrNull;
        c['payoffMinor'] = c['outstandingPrincipalMinor'];
        c['status'] = left.isEmpty ? 'REPAID' : 'ACTIVE';
        if (left.isEmpty) {
          past.insert(0, c);
          current = null;
        }
        return _json(c);
    }
    return _error(404, 'NOT_FOUND', 'Route inconnue');
  }

  static http.Response _json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

  static http.Response _error(int status, String code, String detail) =>
      _json({'status': status, 'title': code, 'code': code, 'detail': detail}, status);
}
