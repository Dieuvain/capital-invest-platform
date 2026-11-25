import 'dart:convert';

import 'package:http/http.dart' as http;

/// Compte en francs et change du faux serveur : mêmes calculs que l'API (2 850 FC pour 1 \$, 1 % de frais).
class FakeExchange {
  FakeExchange({required this.usd, required this.onUsd, required this.addHistory});

  /// Solde USD du faux serveur, et son changement.
  final int Function() usd;
  final void Function(int delta) onUsd;

  /// Ajoute une ligne à l'historique USD du faux serveur (le plus récent en premier).
  final void Function(Map<String, dynamic> item) addHistory;

  num rate = 2850;
  int feeBps = 100;
  int francs = 0;

  /// Historique du compte CDF, du plus récent au plus ancien.
  final cdfHistory = <Map<String, dynamic>>[];
  final _done = <String, Map<String, dynamic>>{};
  int _next = 0;

  /// Le taux change côté serveur : le prochain change avec l'ancien taux est refusé.
  void moveRate(num to) => rate = to;

  List<Map<String, dynamic>> get _accounts => [
    {'accountId': 'w-usd', 'currency': 'USD', 'balanceMinor': usd()},
    {'accountId': 'w-cdf', 'currency': 'CDF', 'balanceMinor': francs},
  ];

  Map<String, dynamic> quote(String from, int amount) {
    final fee = (amount * feeBps + 5000) ~/ 10000;
    final net = amount - fee;
    final received = from == 'USD' ? (net * rate / 100).floor() : (net * 100 / rate).floor();
    return {
      'from': from,
      'to': from == 'USD' ? 'CDF' : 'USD',
      'amountMinor': amount,
      'feeMinor': fee,
      'receivedMinor': received,
      'rate': rate,
    };
  }

  http.Response? handle(http.Request req) {
    final path = req.url.path;
    if (path == '/v1/me/accounts') return _json({'accounts': _accounts});
    if (path == '/v1/me/exchange' && req.method == 'GET') {
      return _json({'cdfPerUsd': rate, 'feeBps': feeBps, 'minUsdMinor': 100, 'minCdfMinor': 1000});
    }
    final cdf = req.url.queryParameters['currency'] == 'CDF';
    if (path == '/v1/me/history' && cdf) return _json({'items': cdfHistory, 'nextCursor': null});
    if (path.startsWith('/v1/me/history/') && cdf) {
      final item = cdfHistory.where((h) => h['id'] == path.split('/').last).firstOrNull;
      if (item == null) return _error(404, 'OPERATION_NOT_FOUND', 'Opération introuvable');
      return _json({
        'item': item,
        'holderName': 'Amani Kabeya',
        'holderPhone': '+243970000001',
        'issuedAt': '2026-11-21T10:00:00Z',
      });
    }
    if (path == '/v1/me/exchange' && req.method == 'POST') {
      final key = req.headers['Idempotency-Key']!;
      if (_done[key] != null) return _json({..._done[key]!, 'accounts': _accounts});
      final b = jsonDecode(req.body) as Map<String, dynamic>;
      final from = b['from'] as String;
      final amount = b['amountMinor'] as int;
      if (b['rate'] != null && b['rate'] != rate) {
        return _error(409, 'RATE_CHANGED', 'Le taux a changé : 1 \$ = $rate FC. Vérifiez le nouveau montant.');
      }
      if (amount > (from == 'USD' ? usd() : francs)) {
        return _error(422, 'INSUFFICIENT_FUNDS', 'Solde $from insuffisant');
      }
      final q = quote(from, amount);
      final received = q['receivedMinor'] as int;
      if (from == 'USD') {
        onUsd(-amount);
        francs += received;
      } else {
        francs -= amount;
        onUsd(received);
      }
      final id = 'x-${++_next}';
      final details = {
        'from': from,
        'to': q['to'],
        'fromMinor': amount,
        'feeMinor': q['feeMinor'],
        'toMinor': received,
        'rate': rate,
      };
      Map<String, dynamic> item(String currency, int net, int fee) => {
        'id': id,
        'receiptNumber': 'CI-20261121-${id.toUpperCase().replaceAll('-', '').padLeft(6, '0')}',
        'kind': 'EXCHANGE',
        'status': 'COMPLETED',
        'amountMinor': net.abs() - fee,
        'feeMinor': fee,
        'netMinor': net,
        'currency': currency,
        'createdAt': '2026-11-21T09:30:00Z',
        'exchange': details,
      };
      final fee = q['feeMinor'] as int;
      addHistory(item('USD', from == 'USD' ? -amount : received, from == 'USD' ? fee : 0));
      cdfHistory.insert(0, item('CDF', from == 'CDF' ? -amount : received, from == 'CDF' ? fee : 0));
      _done[key] = {'exchangeId': id, 'transactionId': id, 'quote': q, 'createdAt': '2026-11-21T09:30:00Z'};
      return _json({..._done[key]!, 'accounts': _accounts});
    }
    return null;
  }

  static http.Response _json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

  static http.Response _error(int status, String code, String detail) =>
      _json({'status': status, 'title': code, 'code': code, 'detail': detail}, status);
}
