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
  List<(int, String, int, String)> entries = [(1, 'CREDIT', 5000, '2026-11-18T10:00:00Z')];

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

    if (path.startsWith('/v1/me') || path.startsWith('/v1/topups') || path.startsWith('/v1/transfers')) {
      if (!authed || sessionExpired) return _error(401, 'SESSION_EXPIRED', 'Session expirée');
    }
    switch ((req.method, path)) {
      case ('POST', '/v1/auth/login'):
        final b = body();
        if (b['pin'] != '2580') return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        return _json({'accessToken': token, 'expiresInSeconds': 900, 'customer': {'customer': _customer, 'walletAccountId': 'w-1'}});
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
      case ('GET', '/v1/me/entries'):
        return _json([
          for (final (i, d, a, at) in entries)
            {'id': 'e-$i', 'transactionId': 't-$i', 'accountId': 'w-1', 'direction': d, 'amountMinor': a, 'currency': 'USD', 'createdAt': at},
        ]);
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
        return _json({'transaction': {}, 'replayed': false, 'amountMinor': a, 'feeMinor': fee, 'totalDebitedMinor': a + fee}, 201);
      case ('POST', '/v1/topups'):
        return _json({'id': 'top-1', 'status': 'PENDING', 'amountMinor': body()['amountMinor']}, 202);
      case ('GET', '/v1/topups/top-1'):
        return _json({'id': 'top-1', 'status': 'CONFIRMED', 'amountMinor': 2500});
      case ('GET', '/v1/me/kyc'):
        return _json({'kycLevel': kycLevel, if (kycStatus != null) 'request': {'id': 'k-1', 'status': kycStatus, 'createdAt': '2026-11-18T10:00:00Z'}});
      case ('POST', '/v1/me/kyc/submission'):
        kycStatus = 'PENDING';
        return _json({'kycLevel': kycLevel, 'request': {'id': 'k-1', 'status': 'PENDING', 'createdAt': '2026-11-18T10:00:00Z'}}, 201);
    }
    if (req.method == 'POST' && path.startsWith('/v1/me/kyc/documents/')) {
      uploads.add(path.split('/').last);
      return _json({'id': 'd-${uploads.length}', 'kind': path.split('/').last, 'contentType': 'image/jpeg', 'sizeBytes': 10, 'createdAt': '2026-11-18T10:00:00Z'}, 201);
    }
    return _error(404, 'NOT_FOUND', 'Route inconnue ${req.method} $path');
  });

  static http.Response _json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

  static http.Response _error(int status, String code, String detail) =>
      _json({'status': status, 'title': code, 'code': code, 'detail': detail}, status);
}
