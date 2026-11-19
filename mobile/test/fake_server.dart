import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// Faux serveur Capital Invest pour les tests : répond comme l'API réelle, sans réseau.
class FakeServer {
  final requests = <http.Request>[];
  int balance = 5000;
  int kycLevel = 1;
  String? kycStatus;
  bool sessionExpired = false;
  final uploads = <String>[];
  List<Map<String, dynamic>> promotions = [
    {
      'id': 'p-1',
      'title': 'Passez au niveau 2',
      'body': 'Envoyez jusqu\'à 4 000 \$ par opération.',
      'ctaLabel': 'Vérifier mon identité',
      'action': 'KYC',
      'style': 'OCHRE',
    },
    {
      'id': 'p-2',
      'title': 'Recharge M-Pesa offerte',
      'body': 'Aucun frais sur vos recharges ce mois-ci.',
      'ctaLabel': 'Recharger',
      'action': 'TOPUP',
      'style': 'GREEN',
    },
  ];

  /// Visuels servis par /v1/promotions/{id}/image (un PNG de 1 x 1 pixel).
  final images = <String, List<int>>{};
  static final png = base64Decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  );
  /// Réponse de l'opérateur à la recharge en cours.
  Map<String, dynamic> topupOutcome = {'status': 'CONFIRMED'};

  /// Réponse de l'opérateur au retrait en cours.
  Map<String, dynamic> withdrawalOutcome = {'status': 'CONFIRMED'};
  int _withdrawn = 0;
  /// Notifications servies par /v1/me/notifications ; « read » passe à vrai à la lecture.
  List<Map<String, dynamic>> notifications = [];

  /// PIN du client, changé par /v1/me/security/pin.
  String pin = '2580';

  /// Connexions servies par /v1/me/security/sessions, celle de ce téléphone en tête.
  List<Map<String, dynamic>> deviceSessions = [
    deviceSession('s-1', 'Samsung Galaxy A14', '2026-11-19T09:10:00Z', current: true),
    deviceSession('s-2', 'Tecno Spark 10', '2026-11-19T08:55:00Z', active: true),
    deviceSession('s-3', 'iPhone 12', '2026-11-12T18:20:00Z'),
  ];

  static Map<String, dynamic> deviceSession(String id, String? name, String at, {bool current = false, bool active = false}) => {
    'id': id, 'deviceName': name, 'createdAt': at, 'lastSeenAt': at, 'current': current, 'active': current || active,
  };

  /// Réclamations servies par /v1/me/complaints, la plus récente en tête.
  List<Map<String, dynamic>> complaints = [];

  /// Comptes servis par /v1/bills/recent.
  List<Map<String, dynamic>> recentBills = [];

  static const billers = [
    {'code': 'SNEL_PREPAID', 'label': 'SNEL prépayé', 'category': 'Électricité', 'kind': 'PREPAID', 'referenceLabel': 'Numéro de compteur'},
    {'code': 'REGIDESO', 'label': 'REGIDESO', 'category': 'Eau', 'kind': 'POSTPAID', 'referenceLabel': 'Numéro d\'abonné'},
    {'code': 'CANAL_PLUS', 'label': 'Canal+', 'category': 'Télévision', 'kind': 'POSTPAID', 'referenceLabel': 'Numéro d\'abonné'},
    {'code': 'DSTV', 'label': 'DStv', 'category': 'Télévision', 'kind': 'POSTPAID', 'referenceLabel': 'Numéro de smartcard'},
  ];

  /// Numéros proposés par /v1/airtime/recent.
  List<Map<String, dynamic>> airtimeRecent = [
    {'phone': '+243812345678', 'network': 'VODACOM'},
  ];

  /// Épargne servie par /v1/me/savings.
  int savings = 0;
  Map<String, dynamic>? savingsGoal;
  List<Map<String, dynamic>> savingsMoves = [];

  Map<String, dynamic> get _savings {
    final g = savingsGoal;
    return {
      'balanceMinor': savings,
      'walletBalanceMinor': balance,
      'currency': 'USD',
      'savedThisMonthMinor': savingsMoves.where((m) => (m['createdAt'] as String).startsWith('2026-11')).fold<int>(
        0,
        (sum, m) => sum + (m['kind'] == 'DEPOSIT' ? m['amountMinor'] as int : -(m['amountMinor'] as int)),
      ),
      'goal': g == null
          ? null
          : {
              ...g,
              'remainingMinor': ((g['targetMinor'] as int) - savings).clamp(0, 1 << 31),
              'progressPercent': (savings * 100 ~/ (g['targetMinor'] as int)).clamp(0, 100),
            },
      'moves': savingsMoves,
    };
  }

  /// Fichiers PDF servis par /v1/me/statements/{mois}/pdf.
  static final pdf = utf8.encode('%PDF-1.4 relevé');

  /// Demandes d'argent, plus récentes d'abord.
  List<Map<String, dynamic>> moneyRequests = [];

  static Map<String, dynamic> moneyRequest(String id, {required bool incoming, required String name, required String phone,
      required int amount, String status = 'PENDING', String? note, String at = '2026-11-19T08:00:00Z'}) => {
    'id': id, 'direction': incoming ? 'INCOMING' : 'OUTGOING', 'counterpartyName': name, 'counterpartyPhone': phone,
    'amountMinor': amount, 'currency': 'USD', 'note': note, 'status': status, 'transactionId': null,
    'createdAt': at, 'expiresAt': '2026-11-26T08:00:00Z', 'decidedAt': null,
  };

  /// Marchands connus, par code.
  Map<String, Map<String, dynamic>> merchants = {
    'K7P2QX': {'code': 'K7P2QX', 'name': 'Alimentation Mama Nzuzi', 'category': 'Alimentation', 'city': 'Lubumbashi'},
    'SUSP22': {'code': 'SUSP22', 'name': 'Boutique fermée', 'suspended': true},
  };

  /// Historique servi par /v1/me/history, du plus récent au plus ancien.
  List<Map<String, dynamic>> history = [historyItem('h-1', 'TOPUP', 5000, at: '2026-11-18T10:00:00Z', operator: 'MPESA')];

  static Map<String, dynamic> historyItem(
    String id,
    String kind,
    int net, {
    int fee = 0,
    String at = '2026-11-18T10:00:00Z',
    String status = 'COMPLETED',
    String? name,
    String? phone,
    String? operator,
    String? msisdn,
    String? note,
  }) => {
    'id': id,
    'receiptNumber': 'CI-20261118-${id.toUpperCase().replaceAll('-', '').padLeft(6, '0')}',
    'kind': kind,
    'status': status,
    'amountMinor': net.abs() - fee,
    'feeMinor': fee,
    'netMinor': net,
    'currency': 'USD',
    'createdAt': at,
    'counterpartyName': ?name,
    'counterpartyPhone': ?phone,
    'operator': ?operator,
    'msisdn': ?msisdn,
    'note': ?note,
  };

  static const token = 'jeton-test';

  Map<String, dynamic> get _customer => {
    'id': 'c-1',
    'phone': '+243970000001',
    'fullName': 'Amani Kabeya',
    'language': 'fr',
    'kycLevel': kycLevel,
    'status': 'ACTIVE',
  };

  late final client = MockClient((req) async {
    requests.add(req);
    final path = req.url.path;
    final authed = req.headers['Authorization'] == 'Bearer $token';
    Map<String, dynamic> body() => req.body.isEmpty ? {} : jsonDecode(req.body);

    if (path.startsWith('/v1/me') ||
        path.startsWith('/v1/promotions') ||
        path.startsWith('/v1/topups') ||
        path.startsWith('/v1/transfers') ||
        path.startsWith('/v1/withdrawals') ||
        path.startsWith('/v1/merchant') ||
        path.startsWith('/v1/money-requests')) {
      if (!authed || sessionExpired) return _error(401, 'SESSION_EXPIRED', 'Session expirée');
    }
    final image = RegExp(r'^/v1/promotions/([^/]+)/image$').firstMatch(path);
    if (image != null) {
      final bytes = images[image.group(1)];
      if (bytes == null) return _error(404, 'PROMOTION_IMAGE_NOT_FOUND', 'Pas de visuel');
      return http.Response.bytes(bytes, 200, headers: {'content-type': 'image/png'});
    }
    final receipt = RegExp(r'^/v1/me/history/([^/]+)$').firstMatch(path);
    if (receipt != null) {
      final item = history.where((h) => h['id'] == receipt.group(1)).firstOrNull;
      if (item == null) return _error(404, 'OPERATION_NOT_FOUND', 'Opération introuvable');
      return _json({'item': item, 'holderName': 'Amani Kabeya', 'holderPhone': '+243970000001', 'issuedAt': '2026-11-19T10:00:00Z'});
    }
    final shop = RegExp(r'^/v1/merchants/([^/]+)$').firstMatch(path);
    if (shop != null) {
      final m = merchants[shop.group(1)];
      if (m == null) return _error(404, 'MERCHANT_NOT_FOUND', 'Aucun marchand avec ce code');
      if (m['suspended'] == true) return _error(422, 'MERCHANT_NOT_ACTIVE', 'Ce marchand n\'accepte pas de paiement');
      return _json(m);
    }
    final requestAction = RegExp(r'^/v1/money-requests/([^/]+)/(pay|decline|cancel)$').firstMatch(path);
    if (requestAction != null) {
      final r = moneyRequests.firstWhere((x) => x['id'] == requestAction.group(1));
      if (r['status'] != 'PENDING') return _error(422, 'REQUEST_CLOSED', 'Demande déjà traitée');
      switch (requestAction.group(2)) {
        case 'pay':
          if (body()['pin'] != '2580') return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
          final a = r['amountMinor'] as int;
          final fee = ((a * 100 + 5000) ~/ 10000).clamp(10, 500);
          if (a + fee > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
          balance -= a + fee;
          r['status'] = 'PAID';
          r['transactionId'] = 'tx-${r['id']}';
          return _json({
            'request': r,
            'transfer': {'transaction': {}, 'replayed': false, 'amountMinor': a, 'feeMinor': fee, 'totalDebitedMinor': a + fee},
          });
        case 'decline':
          r['status'] = 'DECLINED';
        default:
          r['status'] = 'CANCELLED';
      }
      return _json(r);
    }
    if (path == '/v1/money-requests') {
      if (req.method == 'POST') {
        final b = body();
        if (b['payerPhone'] == '+243970000001') return _error(422, 'SAME_ACCOUNT', 'Impossible');
        final r = moneyRequest('rq-${moneyRequests.length + 1}', incoming: false, name: 'Béni Mukendi',
            phone: b['payerPhone'], amount: b['amountMinor'], note: b['note'], at: '2026-11-19T10:00:00Z');
        moneyRequests.insert(0, r);
        return _json(r, 201);
      }
      bool toPay(Map<String, dynamic> r) => r['direction'] == 'INCOMING' && r['status'] == 'PENDING';
      return _json({
        'toPay': moneyRequests.where(toPay).toList().reversed.toList(),
        'recent': moneyRequests.where((r) => !toPay(r)).toList(),
      });
    }
    if (path == '/v1/me/complaints' && req.method == 'POST') {
      final b = body();
      final tx = b['transactionId'] as String?;
      if (tx != null && complaints.any((c) => c['operation']?['id'] == tx && c['status'] == 'OPEN')) {
        return _error(409, 'COMPLAINT_ALREADY_OPEN', 'Déjà ouverte');
      }
      final c = {
        'id': 'cp-${complaints.length + 1}', 'reference': 'REC-20261119-3F9A2${complaints.length}', 'category': b['category'],
        'categoryLabel': {'NOT_RECEIVED': 'L\'argent n\'est pas arrivé', 'WRONG_AMOUNT': 'Mauvais montant ou frais',
          'NOT_ME': 'Je n\'ai pas fait cette opération', 'OTHER': 'Autre problème'}[b['category']],
        'message': b['message'], 'status': 'OPEN',
        'operation': tx == null ? null : history.firstWhere((h) => h['id'] == tx),
        'dueAt': '2026-11-26T10:00:00Z', 'answer': null, 'outcome': null, 'answeredAt': null, 'createdAt': '2026-11-19T10:00:00Z',
      };
      complaints.insert(0, c);
      return _json(c, 201);
    }
    if (path == '/v1/me/complaints') return _json(complaints);
    if (path == '/v1/bills/billers') return _json(billers);
    if (path == '/v1/bills/recent') return _json(recentBills);
    if (path == '/v1/bills/lookup') {
      final b = body();
      final ref = (b['reference'] as String).replaceAll(RegExp(r'[^0-9]'), '');
      if (ref.endsWith('0000')) return _error(404, 'BILL_ACCOUNT_NOT_FOUND', 'Inconnu');
      return _json({
        'biller': b['biller'], 'reference': ref, 'holderName': 'KABEYA AMANI',
        'amountDueMinor': b['biller'] == 'SNEL_PREPAID' ? null : 1850,
      });
    }
    if (path == '/v1/bills' && req.method == 'POST') {
      final b = body();
      if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
      final a = b['amountMinor'] as int;
      if (a > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
      balance -= a;
      final label = billers.firstWhere((x) => x['code'] == b['biller'])['label'];
      history.insert(0, historyItem('bp-1', 'BILL_PAYMENT', -a, at: '2026-11-19T10:40:00Z', name: label));
      return _json({
        'id': 'bp-1', 'biller': b['biller'], 'billerName': label, 'reference': b['reference'],
        'holderName': 'KABEYA AMANI', 'amountMinor': a, 'currency': 'USD', 'status': 'PAID',
        'token': b['biller'] == 'SNEL_PREPAID' ? '4821 0937 5512 8064 3379' : null,
        'failureMessage': null, 'transactionId': 'bp-1', 'createdAt': '2026-11-19T10:40:00Z',
      }, 201);
    }
    if (path == '/v1/airtime/recent') return _json(airtimeRecent);
    if (path == '/v1/airtime' && req.method == 'POST') {
      final b = body();
      if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
      final a = b['amountMinor'] as int;
      if (a > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
      final failed = (b['phone'] as String).endsWith('0000');
      if (!failed) balance -= a;
      final id = 'at-${history.length + 1}';
      history.insert(0, historyItem(id, 'AIRTIME', -a, at: '2026-11-19T10:30:00Z', msisdn: b['phone'],
          status: failed ? 'FAILED' : 'COMPLETED'));
      return _json({
        'id': id, 'network': b['network'], 'phone': b['phone'], 'amountMinor': a, 'currency': 'USD',
        'status': failed ? 'FAILED' : 'DELIVERED',
        'failureMessage': failed ? 'Ce numéro n\'est pas actif chez Airtel' : null,
        'transactionId': id, 'createdAt': '2026-11-19T10:30:00Z',
      }, 201);
    }
    if (path == '/v1/me/savings') return _json(_savings);
    if (path == '/v1/me/savings/deposits' || path == '/v1/me/savings/withdrawals') {
      final a = body()['amountMinor'] as int;
      final deposit = path.endsWith('deposits');
      if (a > (deposit ? balance : savings)) {
        return _error(422, deposit ? 'INSUFFICIENT_FUNDS' : 'SAVINGS_INSUFFICIENT', 'Solde insuffisant');
      }
      balance += deposit ? -a : a;
      savings += deposit ? a : -a;
      final id = 'sv-${savingsMoves.length + 1}';
      savingsMoves.insert(0, {'id': id, 'kind': deposit ? 'DEPOSIT' : 'WITHDRAWAL', 'amountMinor': a, 'createdAt': '2026-11-19T10:00:00Z'});
      history.insert(0, historyItem(id, deposit ? 'SAVINGS_DEPOSIT' : 'SAVINGS_WITHDRAWAL', deposit ? -a : a, at: '2026-11-19T10:00:00Z'));
      return _json(_savings);
    }
    if (path == '/v1/me/savings/goal') {
      if (req.method == 'DELETE') {
        savingsGoal = null;
      } else {
        final b = body();
        savingsGoal = {'label': (b['label'] as String).trim(), 'targetMinor': b['targetMinor']};
      }
      return _json(_savings);
    }
    if (path == '/v1/me/statements') {
      return _json([
        {'month': '2026-11', 'partial': true},
        {'month': '2026-10', 'partial': false},
      ]);
    }
    final statement = RegExp(r'^/v1/me/statements/(\d{4}-\d{2})(/pdf)?$').firstMatch(path);
    if (statement != null) {
      if (statement.group(2) != null) {
        return http.Response.bytes(pdf, 200, headers: {'content-type': 'application/pdf'});
      }
      final items = [
        historyItem('o-1', 'TOPUP', 15000, at: '2026-10-02T07:14:00Z', operator: 'MPESA'),
        historyItem('o-2', 'TRANSFER_RECEIVED', 4500, at: '2026-10-04T16:40:00Z', name: 'Grâce Mbuyi', phone: '+243 81• ••• 210', note: 'Remboursement tontine'),
        historyItem('o-3', 'TRANSFER_SENT', -3030, fee: 30, at: '2026-10-05T06:05:00Z', name: 'Amani Kabeya', phone: '+243 97• ••• 001', note: 'Loyer octobre'),
        historyItem('o-4', 'WITHDRAWAL', -6090, fee: 90, at: '2026-10-18T08:47:00Z', operator: 'MPESA', msisdn: '+243970000001'),
        historyItem('o-5', 'TRANSFER_RECEIVED', 2500, at: '2026-10-23T17:31:00Z', name: 'Patrick Ilunga', phone: '+243 81• ••• 211', note: 'Part du marché'),
      ];
      return _json({
        'month': statement.group(1), 'from': '2026-10-01', 'to': '2026-10-31', 'partial': false,
        'openingBalanceMinor': 1200, 'totalInMinor': 22000, 'totalOutMinor': 9120, 'feesMinor': 120,
        'closingBalanceMinor': 14080, 'items': items,
      });
    }
    if (path == '/v1/me/security/pin') {
      final b = body();
      if (b['currentPin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
      if (b['newPin'] == pin) return _error(422, 'PIN_SAME', 'Choisissez un PIN différent');
      pin = b['newPin'];
      _signOutOthers();
      return http.Response('', 204);
    }
    if (path == '/v1/me/security/sessions') return _json(deviceSessions);
    if (path == '/v1/me/security/sessions/sign-out-others') return _json({'closed': _signOutOthers()});
    if (path == '/v1/me/notifications') {
      return _json({'items': notifications, 'unread': notifications.where((n) => n['read'] != true).length});
    }
    if (path == '/v1/me/notifications/read') {
      for (final n in notifications) {
        n['read'] = true;
      }
      return http.Response('', 204);
    }
    switch ((req.method, path)) {
      case ('POST', '/v1/auth/login'):
        final b = body();
        if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        return _json({
          'accessToken': token,
          'expiresInSeconds': 900,
          'customer': {'customer': _customer, 'walletAccountId': 'w-1'},
        });
      case ('GET', '/v1/me'):
        return _json({'customer': _customer, 'walletAccountId': 'w-1'});
      case ('GET', '/v1/me/balance'):
        return _json({'accountId': 'w-1', 'currency': 'USD', 'balanceMinor': balance});
      case ('GET', '/v1/me/limits'):
        return _json({
          'kycLevel': kycLevel,
          'perTransactionMinor': kycLevel == 1 ? 20000 : 400000,
          'dailyOutMinor': 60000,
          'dailyOutUsedMinor': 10000,
          'dailyOutRemainingMinor': 50000,
          'maxBalanceMinor': 100000,
        });
      case ('GET', '/v1/me/history'):
        final category = req.url.queryParameters['category'];
        final kinds = switch (category) {
          'TOPUP' => ['TOPUP'],
          'TRANSFER' => ['TRANSFER_SENT', 'TRANSFER_RECEIVED'],
          'WITHDRAWAL' => ['WITHDRAWAL', 'WITHDRAWAL_REFUND'],
          'PAYMENT' => ['MERCHANT_PAYMENT', 'AIRTIME', 'AIRTIME_REFUND'],
          'SAVINGS' => ['SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAWAL'],
          _ => null,
        };
        final all = history.where((h) => kinds == null || kinds.contains(h['kind'])).toList();
        final limit = int.parse(req.url.queryParameters['limit'] ?? '30');
        final start = int.parse(req.url.queryParameters['cursor'] ?? '0');
        final end = (start + limit).clamp(0, all.length);
        return _json({'items': all.sublist(start, end), 'nextCursor': end < all.length ? '$end' : null});
      case ('GET', '/v1/transfers/fee'):
        final a = int.parse(req.url.queryParameters['amountMinor']!);
        final fee = ((a * 100 + 5000) ~/ 10000).clamp(10, 500);
        return _json({'amountMinor': a, 'feeMinor': fee, 'totalMinor': a + fee});
      case ('POST', '/v1/transfers'):
        final b = body();
        if (b['pin'] != '2580') return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        final a = b['amountMinor'] as int;
        final fee = ((a * 100 + 5000) ~/ 10000).clamp(10, 500);
        if (a + fee > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
        balance -= a + fee;
        return _json({
          'transaction': {},
          'replayed': false,
          'amountMinor': a,
          'feeMinor': fee,
          'totalDebitedMinor': a + fee,
        }, 201);
      case ('POST', '/v1/merchant-payments'):
        final b = body();
        if (b['pin'] != '2580') return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        final a = b['amountMinor'] as int;
        if (a > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
        balance -= a;
        final m = merchants[b['merchantCode']]!;
        history.insert(0, historyItem('mp-1', 'MERCHANT_PAYMENT', -a, at: '2026-11-19T09:30:00Z', name: m['name']));
        return _json({
          'transactionId': 'mp-1',
          'receiptNumber': 'CI-20261119-00MP01',
          'merchantCode': m['code'],
          'merchantName': m['name'],
          'amountMinor': a,
          'currency': 'USD',
          'createdAt': '2026-11-19T09:30:00Z',
          'replayed': false,
        }, 201);
      case ('GET', '/v1/withdrawals/fee'):
        final a = int.parse(req.url.queryParameters['amountMinor']!);
        final fee = ((a * 150 + 5000) ~/ 10000).clamp(25, 750);
        return _json({'amountMinor': a, 'feeMinor': fee, 'totalMinor': a + fee});
      case ('POST', '/v1/withdrawals'):
        final b = body();
        if (b['pin'] != '2580') return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        final a = b['amountMinor'] as int;
        final fee = ((a * 150 + 5000) ~/ 10000).clamp(25, 750);
        if (a + fee > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
        balance -= a + fee;
        _withdrawn = a;
        return _json({'id': 'wd-1', 'status': 'PENDING', 'amountMinor': a, 'feeMinor': fee, 'msisdn': '243970000001'}, 202);
      case ('GET', '/v1/withdrawals/wd-1'):
        return _json({'id': 'wd-1', 'amountMinor': _withdrawn, 'feeMinor': 38, 'msisdn': '243970000001', ...withdrawalOutcome});
      case ('POST', '/v1/topups'):
        return _json({'id': 'top-1', 'status': 'PENDING', 'amountMinor': body()['amountMinor']}, 202);
      case ('GET', '/v1/topups/top-1'):
        return _json({'id': 'top-1', 'amountMinor': 2500, ...topupOutcome});
      case ('GET', '/v1/promotions'):
        return _json(promotions);
      case ('GET', '/v1/me/kyc'):
        return _json({
          'kycLevel': kycLevel,
          if (kycStatus != null) 'request': {'id': 'k-1', 'status': kycStatus, 'createdAt': '2026-11-18T10:00:00Z'},
        });
      case ('POST', '/v1/me/kyc/submission'):
        kycStatus = 'PENDING';
        return _json({
          'kycLevel': kycLevel,
          'request': {'id': 'k-1', 'status': 'PENDING', 'createdAt': '2026-11-18T10:00:00Z'},
        }, 201);
    }
    if (req.method == 'POST' && path.startsWith('/v1/me/kyc/documents/')) {
      uploads.add(path.split('/').last);
      return _json({
        'id': 'd-${uploads.length}',
        'kind': path.split('/').last,
        'contentType': 'image/jpeg',
        'sizeBytes': 10,
        'createdAt': '2026-11-18T10:00:00Z',
      }, 201);
    }
    return _error(404, 'NOT_FOUND', 'Route inconnue ${req.method} $path');
  });

  int _signOutOthers() {
    var closed = 0;
    for (final d in deviceSessions) {
      if (d['current'] != true && d['active'] == true) {
        d['active'] = false;
        closed++;
      }
    }
    return closed;
  }

  static http.Response _json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

  static http.Response _error(int status, String code, String detail) =>
      _json({'status': status, 'title': code, 'code': code, 'detail': detail}, status);
}
