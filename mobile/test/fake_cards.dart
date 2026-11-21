import 'dart:convert';

import 'package:http/http.dart' as http;

/// Cartes du faux serveur : mêmes règles que l'API (niveau 2, une carte de chaque sorte, PIN, cycle de vie).
class FakeCards {
  FakeCards({required this.kycLevel, required this.pin, required this.onFee});

  /// Niveau KYC et PIN courants du faux serveur.
  final int Function() kycLevel;
  final String Function() pin;

  /// Frais de remplacement pris sur le solde.
  final void Function(int minor) onFee;

  static const branch = 'Kolwezi - Agence principale';
  static const pan = '4000001234567899';
  static const activationCode = '482913';
  int replacementFeeMinor = 500;
  int physicalCardFeeMinor = 100;

  final cards = <Map<String, dynamic>>[];
  final payments = <String, List<Map<String, dynamic>>>{};
  final _orders = <String, Map<String, dynamic>>{};
  int _next = 0;

  static Map<String, dynamic> card(String id, String kind, String status, {String last4 = '7899', bool? online}) => {
    'id': id,
    'kind': kind,
    'status': status,
    'network': 'VISA',
    'last4': last4,
    'expiryMonth': 11,
    'expiryYear': 2029,
    'embossedName': 'AMANI KABEYA',
    'dailyLimitMinor': 50000,
    'spentTodayMinor': 0,
    'onlinePayments': online ?? kind == 'VIRTUAL',
    'pickupBranch': kind == 'PHYSICAL' ? branch : null,
    'blockReason': null,
    'replaceable': false,
    'createdAt': '2026-11-20T10:00:00Z',
  };

  static const _live = {'ORDERED', 'IN_PRODUCTION', 'READY_FOR_PICKUP', 'ACTIVE', 'FROZEN'};

  Map<String, dynamic> get overview => {
    'eligible': kycLevel() >= 2,
    'kycLevel': kycLevel(),
    'cards': cards.where((c) => c['status'] != 'DELETED' && c['_replaced'] != true).map(_public).toList(),
    'branches': [branch, 'Lubumbashi', 'Kalemie', 'Kinshasa'],
    'physicalCardFeeMinor': physicalCardFeeMinor,
    'replacementFeeMinor': replacementFeeMinor,
    'maxDailyLimitMinor': 1000000,
  };

  Map<String, dynamic> _public(Map<String, dynamic> c) => Map.of(c)..remove('_replaced');

  bool _hasLive(String kind) => cards.any((c) => c['kind'] == kind && _live.contains(c['status']));

  /// Répond aux routes /v1/me/cards ; null si la route n'est pas une route de carte.
  http.Response? handle(http.Request req) {
    final path = req.url.path;
    if (!path.startsWith('/v1/me/cards')) return null;
    final body = req.body.isEmpty || req.body == 'null'
        ? <String, dynamic>{}
        : jsonDecode(req.body) as Map<String, dynamic>;
    bool badPin() => body['pin'] != pin();
    if (path == '/v1/me/cards' && req.method == 'GET') return _json(overview);
    if (path == '/v1/me/cards/virtual') {
      if (kycLevel() < 2) return _error(403, 'KYC_LEVEL_REQUIRED', 'Les cartes demandent le niveau 2');
      if (_hasLive('VIRTUAL')) return _error(409, 'CARD_ALREADY_EXISTS', 'Vous avez déjà une carte virtuelle');
      final c = card('v-${++_next}', 'VIRTUAL', 'ACTIVE', last4: '42${_next.toString().padLeft(2, '0')}');
      cards.insert(0, c);
      return _json(c, 201);
    }
    if (path == '/v1/me/cards/physical') {
      final key = req.headers['Idempotency-Key']!;
      if (_orders[key] != null) return _json(_orders[key]!, 201);
      if (badPin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
      if (_hasLive('PHYSICAL')) return _error(409, 'CARD_ALREADY_EXISTS', 'Vous avez déjà une carte physique');
      onFee(physicalCardFeeMinor);
      final c = card('p-${++_next}', 'PHYSICAL', 'ORDERED', last4: '55${_next.toString().padLeft(2, '0')}');
      cards.add(c);
      return _json(_orders[key] = c, 201);
    }
    final m = RegExp(r'^/v1/me/cards/([^/]+)(?:/([a-z]+))?$').firstMatch(path);
    if (m == null) return _error(404, 'NOT_FOUND', 'Route inconnue');
    final c = cards.where((c) => c['id'] == m.group(1) && c['status'] != 'DELETED').firstOrNull;
    if (c == null) return _error(404, 'CARD_NOT_FOUND', 'Carte introuvable');
    switch (m.group(2)) {
      case null:
        return _json(_public(c));
      case 'authorizations':
        return _json(payments[c['id']] ?? const []);
      case 'details':
        if (badPin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
        return _json({
          'cardId': c['id'],
          'pan': pan,
          'cvv': '123',
          'expiryMonth': 11,
          'expiryYear': 2029,
          'embossedName': c['embossedName'],
        });
      case 'freeze':
        c['status'] = 'FROZEN';
        return _json(_public(c));
      case 'unfreeze':
        if (badPin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
        c['status'] = 'ACTIVE';
        return _json(_public(c));
      case 'controls':
        c['dailyLimitMinor'] = body['dailyLimitMinor'];
        c['onlinePayments'] = c['kind'] == 'VIRTUAL' ? true : body['onlinePayments'];
        return _json(_public(c));
      case 'block':
        if (badPin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
        c['status'] = 'BLOCKED';
        c['blockReason'] = body['reason'];
        c['replaceable'] = true;
        return _json(_public(c));
      case 'delete':
        if (badPin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
        c['status'] = 'DELETED';
        return http.Response('', 204);
      case 'activate':
        if (c['status'] != 'READY_FOR_PICKUP') {
          return _error(422, 'CARD_NOT_READY', 'Votre carte n\'est pas encore arrivée');
        }
        if (body['activationCode'] != activationCode) {
          return _error(
            422,
            'INVALID_ACTIVATION_CODE',
            'Code d\'activation incorrect. Il est imprimé sur le pli remis avec la carte.',
          );
        }
        c['status'] = 'ACTIVE';
        return _json(_public(c));
      case 'replace':
        final key = req.headers['Idempotency-Key']!;
        if (_orders[key] != null) return _json(_orders[key]!, 201);
        if (badPin()) return _error(401, 'INVALID_CREDENTIALS', 'PIN incorrect');
        if (c['replaceable'] != true) return _error(422, 'CARD_NOT_REPLACEABLE', 'Seule une carte bloquée se remplace');
        c['_replaced'] = true;
        c['replaceable'] = false;
        final physical = c['kind'] == 'PHYSICAL';
        if (physical) onFee(replacementFeeMinor);
        final n = card(
          '${physical ? 'p' : 'v'}-${++_next}',
          c['kind'],
          physical ? 'ORDERED' : 'ACTIVE',
          last4: '66${_next.toString().padLeft(2, '0')}',
        );
        cards.add(n);
        return _json(_orders[key] = n, 201);
    }
    return _error(404, 'NOT_FOUND', 'Route inconnue');
  }

  static http.Response _json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

  static http.Response _error(int status, String code, String detail) =>
      _json({'status': status, 'title': code, 'code': code, 'detail': detail}, status);
}
