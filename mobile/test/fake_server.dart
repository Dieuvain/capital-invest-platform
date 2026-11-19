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
          'PAYMENT' => ['MERCHANT_PAYMENT'],
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
