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
        path.startsWith('/v1/withdrawals')) {
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
    switch ((req.method, path)) {
      case ('POST', '/v1/auth/login'):
        final b = body();
        if (b['pin'] != '2580') return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
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

  static http.Response _json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

  static http.Response _error(int status, String code, String detail) =>
      _json({'status': status, 'title': code, 'code': code, 'detail': detail}, status);
}
