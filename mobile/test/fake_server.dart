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

  /// « Mes dépenses » servies par /v1/me/spending, par mois (« current » sans paramètre).
  Map<String, Map<String, dynamic>> spending = {
    'current': {
      'month': '2026-11', 'partial': true, 'from': '2026-11-01', 'to': '2026-11-19',
      'comparedFrom': '2026-10-01', 'comparedTo': '2026-10-19',
      'spentMinor': 38420, 'previousSpentMinor': 31200, 'receivedMinor': 104500, 'savedMinor': 3500,
      'categories': [
        {'category': 'TRANSFERS', 'amountMinor': 22000, 'previousMinor': 18000, 'count': 3},
        {'category': 'WITHDRAWALS', 'amountMinor': 10000, 'previousMinor': 8000, 'count': 1},
        {'category': 'BILLS', 'amountMinor': 4500, 'previousMinor': 4500, 'count': 2},
        {'category': 'AIRTIME', 'amountMinor': 1000, 'previousMinor': 500, 'count': 2},
        {'category': 'FEES', 'amountMinor': 920, 'previousMinor': 200, 'count': 0},
      ],
      'topRecipients': [
        {'name': 'Grâce Mbuyi', 'phone': '+243 81• ••• 214', 'amountMinor': 20000, 'count': 2},
        {'name': 'Josué Kalala', 'phone': '+243 97• ••• 002', 'amountMinor': 2000, 'count': 1},
      ],
    },
    '2026-10': {
      'month': '2026-10', 'partial': false, 'from': '2026-10-01', 'to': '2026-10-31',
      'comparedFrom': null, 'comparedTo': null,
      'spentMinor': 9120, 'previousSpentMinor': null, 'receivedMinor': 22000, 'savedMinor': 0,
      'categories': [
        {'category': 'TRANSFERS', 'amountMinor': 9000, 'previousMinor': null, 'count': 1},
        {'category': 'FEES', 'amountMinor': 120, 'previousMinor': null, 'count': 0},
      ],
      'topRecipients': [],
    },
  };

  /// Plafond d'envoi par jour choisi par le client (null : celui du niveau) et hausse en attente.
  int? personalDailyLimit;
  Map<String, dynamic>? pendingDailyLimit;

  Map<String, dynamic> _limits() {
    final daily = personalDailyLimit ?? 60000;
    return {
      'kycLevel': kycLevel,
      'perTransactionMinor': kycLevel == 1 ? 20000 : 400000,
      'dailyOutMinor': daily,
      'dailyOutUsedMinor': 10000,
      'dailyOutRemainingMinor': (daily - 10000).clamp(0, daily),
      'maxBalanceMinor': 100000,
      'levelDailyOutMinor': 60000,
      'personalDailyOutMinor': personalDailyLimit,
      'pendingDailyOut': pendingDailyLimit,
      'personalMinMinor': 1000,
    };
  }

  /// Réponse de /v1/me/closure, et compte fermé par /v1/me/closure (POST).
  Map<String, dynamic> closure = {
    'canClose': false, 'balanceMinor': 5000, 'savingsMinor': 1850,
    'blockers': [
      {'code': 'SAVINGS_NOT_EMPTY', 'message': 'Reprenez votre épargne (18,50 \$) sur votre solde.'},
      {'code': 'BALANCE_NOT_EMPTY', 'message': 'Retirez ou envoyez votre solde (50,00 \$) : un compte fermé ne peut plus rien rendre.'},
    ],
  };
  bool closed = false;

  /// Seuil d'alerte de solde bas (/v1/me/notifications/low-balance).
  int? lowBalance;

  /// Compte bloqué par le client (/v1/me/security/block ou /v1/auth/block).
  bool blocked = false;

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

  /// Clients connus de /v1/transfers/recipient (numéro → prénom et initiale).
  static const recipients = {'+243970000002': 'Josué K.', '+243970000402': 'Béni M.', '+243990000870': 'Patrick I.'};

  /// Envois à des proches sans compte, servis par /v1/transfers/pending.
  List<Map<String, dynamic>> pendingTransfers = [];

  static Map<String, dynamic> pendingTransfer(String id, String name, String phone, int amount,
      {String status = 'PENDING', String createdAt = '2026-11-18T09:00:00Z', String expiresAt = '2026-11-25T09:00:00Z', String? settledAt}) => {
        'id': id,
        'recipientName': name,
        'phone': phone,
        'amountMinor': amount,
        'feeMinor': ((amount * 100 + 5000) ~/ 10000).clamp(10, 500),
        'note': null,
        'status': status,
        'createdAt': createdAt,
        'expiresAt': expiresAt,
        'settledAt': settledAt,
      };

  /// Cagnottes servies par /v1/me/pots et /v1/pots/{code}. « mine » : celles du client connecté.
  List<Map<String, dynamic>> pots = [];

  static Map<String, dynamic> pot(String code, String title,
      {bool mine = true, String ownerName = 'Josué K.', String? description, int? target, String status = 'OPEN',
      List<Map<String, dynamic>> contributions = const [], int myTotal = 0}) {
    final collected = contributions.fold<int>(0, (s, c) => s + ((c['_amount'] ?? c['amountMinor'] ?? 0) as int));
    return {
      'id': 'pot-$code',
      'code': code,
      'title': title,
      'description': description,
      'targetMinor': target,
      'status': status,
      'ownerName': ownerName,
      'mine': mine,
      'collectedMinor': collected,
      'contributors': contributions.map((c) => c['name'] ?? c['id']).toSet().length,
      'myTotalMinor': myTotal,
      'progressPercent': target == null ? null : (collected * 100 ~/ target).clamp(0, 100),
      'shareLink': 'capitalinvest://pot?c=$code',
      'contributions': contributions,
      'createdAt': '2026-11-18T09:00:00Z',
      'closedAt': status == 'CLOSED' ? '2026-11-20T10:00:00Z' : null,
      'paidOutMinor': status == 'CLOSED' ? collected : null,
    };
  }

  static Map<String, dynamic> contribution(String id, String? name, int? amount, {String? message, int? hiddenAmount, String at = '2026-11-19T15:30:00Z'}) => {
        'id': id,
        'name': name,
        'amountMinor': amount,
        'message': message,
        'mine': false,
        'createdAt': at,
        '_amount': ?hiddenAmount,
      };

  /// Favoris servis par /v1/me/favorites.
  List<Map<String, dynamic>> favorites = [];
  int _favoriteSeq = 0;

  /// Destinataires servis par /v1/transfers/recent.
  List<Map<String, dynamic>> recentRecipients = [];

  /// Envois programmés servis par /v1/me/scheduled-transfers.
  List<Map<String, dynamic>> scheduled = [];

  static Map<String, dynamic> scheduledTransfer(String id, String name, int amount, String frequency, int day,
      {String status = 'ACTIVE', String? note, String? lastResult, String? lastFailure}) => {
    'id': id, 'recipientName': name, 'recipientPhone': '+243 97• ••• 402', 'amountMinor': amount,
    'feeMinor': ((amount * 100 + 5000) ~/ 10000).clamp(10, 500), 'note': note, 'frequency': frequency, 'day': day,
    'status': status, 'nextRunAt': status == 'ACTIVE' ? '2026-12-05T06:00:00Z' : null,
    'lastRunAt': lastResult == null ? null : '2026-11-05T06:00:00Z', 'lastResult': lastResult, 'lastFailure': lastFailure,
    'createdAt': '2026-10-01T10:00:00Z',
  };

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

  /// Code SMS accepté par /v1/auth/register et /v1/auth/pin-reset ; nom donné à l'inscription.
  static const smsCode = '123456';
  String fullName = 'Amani Kabeya';

  Map<String, dynamic> get _customer => {
    'id': 'c-1',
    'phone': '+243970000001',
    'fullName': fullName,
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
    if (path == '/v1/me/scheduled-transfers' && req.method == 'POST') {
      final b = body();
      if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
      final s = scheduledTransfer('st-${scheduled.length + 1}', 'Béni Mukendi', b['amountMinor'], b['frequency'], b['day'], note: b['note']);
      scheduled.add(s);
      return _json(s, 201);
    }
    if (path == '/v1/me/scheduled-transfers') return _json(scheduled);
    final pt = RegExp(r'^/v1/transfers/pending/([^/]+)$').firstMatch(path);
    if (pt != null && req.method == 'DELETE') {
      final i = pendingTransfers.indexWhere((t) => t['id'] == pt.group(1));
      if (i < 0) return _error(404, 'PENDING_TRANSFER_NOT_FOUND', 'Envoi introuvable');
      final t = pendingTransfers[i];
      if (t['status'] != 'PENDING') return _error(422, 'PENDING_TRANSFER_SETTLED', 'Déjà réglé');
      balance += (t['amountMinor'] as int) + (t['feeMinor'] as int);
      final updated = {...t, 'status': 'CANCELLED', 'settledAt': '2026-11-19T10:00:00Z'};
      pendingTransfers = [...pendingTransfers]..[i] = updated;
      return _json(updated);
    }
    final potClose = RegExp(r'^/v1/me/pots/([^/]+)/close$').firstMatch(path);
    if (potClose != null && req.method == 'POST') {
      final i = pots.indexWhere((p) => p['id'] == potClose.group(1) && p['mine'] == true);
      if (i < 0) return _error(404, 'POT_NOT_FOUND', 'Cagnotte introuvable');
      final p = pots[i];
      if (p['status'] == 'OPEN') balance += p['collectedMinor'] as int;
      final closed = {...p, 'status': 'CLOSED', 'closedAt': '2026-11-20T10:00:00Z', 'paidOutMinor': p['collectedMinor']};
      pots = [...pots]..[i] = closed;
      return _json(closed);
    }
    final potGive = RegExp(r'^/v1/pots/([^/]+)/contributions$').firstMatch(path);
    if (potGive != null && req.method == 'POST') {
      final i = pots.indexWhere((p) => p['code'] == potGive.group(1));
      if (i < 0) return _error(404, 'POT_NOT_FOUND', 'Aucune cagnotte avec ce code');
      final b = body();
      if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
      final p = pots[i];
      if (p['mine'] == true) return _error(422, 'OWN_POT', 'Votre cagnotte');
      if (p['status'] != 'OPEN') return _error(422, 'POT_CLOSED', 'Clôturée');
      final a = b['amountMinor'] as int;
      if (a > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
      balance -= a;
      final c = {
        'id': 'pc-${(p['contributions'] as List).length + 1}',
        'name': 'Josué Kabila',
        'amountMinor': a,
        'message': b['message'],
        'mine': true,
        'createdAt': '2026-11-20T11:00:00Z',
      };
      final updated = {
        ...p,
        'collectedMinor': (p['collectedMinor'] as int) + a,
        'contributors': (p['contributors'] as int) + ((p['myTotalMinor'] as int) == 0 ? 1 : 0),
        'myTotalMinor': (p['myTotalMinor'] as int) + a,
        'contributions': [c, ...(p['contributions'] as List)],
      };
      pots = [...pots]..[i] = updated;
      return _json(updated, 201);
    }
    final potGet = RegExp(r'^/v1/pots/([^/]+)$').firstMatch(path);
    if (potGet != null && req.method == 'GET') {
      final p = pots.where((p) => p['code'] == potGet.group(1)).firstOrNull;
      return p == null ? _error(404, 'POT_NOT_FOUND', 'Aucune cagnotte avec ce code') : _json(p);
    }
    if (path == '/v1/me/pots' && req.method == 'POST') {
      final b = body();
      final t = (b['title'] as String).trim();
      if (t.isEmpty) return _error(422, 'INVALID_POT_TITLE', 'Nom invalide');
      final p = pot('K7M4QX', t, description: b['description'], target: b['targetMinor']);
      pots = [p, ...pots];
      return _json(p, 201);
    }
    if (path == '/v1/me/pots') return _json(pots.where((p) => p['mine'] == true).toList());
    final fav = RegExp(r'^/v1/me/favorites/([^/]+)$').firstMatch(path);
    if (fav != null && req.method == 'DELETE') {
      final before = favorites.length;
      favorites = favorites.where((f) => f['id'] != fav.group(1)).toList();
      if (favorites.length == before) return _error(404, 'FAVORITE_NOT_FOUND', 'Favori introuvable');
      return http.Response('', 204);
    }
    final st = RegExp(r'^/v1/me/scheduled-transfers/([^/]+)(?:/(pause|resume))?$').firstMatch(path);
    if (st != null) {
      final s = scheduled.firstWhere((x) => x['id'] == st.group(1));
      if (req.method == 'DELETE') {
        scheduled.remove(s);
        return http.Response('', 204);
      }
      s['status'] = st.group(2) == 'pause' ? 'PAUSED' : 'ACTIVE';
      s['nextRunAt'] = s['status'] == 'ACTIVE' ? '2026-12-05T06:00:00Z' : null;
      return _json(s);
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
    if (path == '/v1/me/spending') {
      final m = req.url.queryParameters['month'];
      final found = m == null || m == '2026-11' ? spending['current'] : spending[m];
      return found == null ? _error(422, 'STATEMENT_UNAVAILABLE', 'Pas de relevé') : _json(found);
    }
    if (path == '/v1/me/security/sessions') return _json(deviceSessions);
    if (path == '/v1/me/closure') {
      if (req.method == 'GET') return _json(closure);
      final b = body();
      if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
      closed = true;
      return http.Response('', 204);
    }
    if (path == '/v1/me/security/block' || path == '/v1/auth/block') {
      if (body()['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
      blocked = true;
      return http.Response('', 204);
    }
    if (path == '/v1/me/security/sessions/sign-out-others') return _json({'closed': _signOutOthers()});
    if (path == '/v1/me/notifications/low-balance') {
      if (req.method == 'PUT') {
        final int? t = body()['thresholdMinor'];
        if (t != null && (t < 100 || t > 100000)) return _error(422, 'INVALID_THRESHOLD', 'Seuil invalide');
        lowBalance = t;
      }
      return _json({'thresholdMinor': lowBalance});
    }
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
      case ('POST', '/v1/auth/otp') || ('POST', '/v1/auth/pin-reset/otp'):
        return http.Response('', 204);
      case ('POST', '/v1/auth/register') || ('POST', '/v1/auth/pin-reset'):
        final b = body();
        if (b['code'] != smsCode) return _error(401, 'OTP_INVALID', 'Code invalide ou expiré');
        if (b['fullName'] != null) fullName = b['fullName'];
        pin = b['pin'] ?? b['newPin'];
        return _json({
          'accessToken': token,
          'expiresInSeconds': 900,
          'customer': {'customer': _customer, 'walletAccountId': 'w-1'},
        });
      case ('POST', '/v1/auth/login'):
        final b = body();
        if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        if (blocked) return _error(403, 'CUSTOMER_BLOCKED', 'Compte bloqué à votre demande');
        if (closed) return _error(403, 'CUSTOMER_CLOSED', 'Ce compte est fermé');
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
        return _json(_limits());
      case ('PUT', '/v1/me/limits/daily'):
        final b = body();
        if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        final int? v = b['dailyOutMinor'];
        if (v != null && (v < 1000 || v > 60000)) return _error(422, 'INVALID_DAILY_LIMIT', 'Choisissez entre 10,00 \$ et 600,00 \$');
        if ((v ?? 60000) <= (personalDailyLimit ?? 60000)) {
          personalDailyLimit = v != null && v < 60000 ? v : null;
          pendingDailyLimit = null;
        } else {
          pendingDailyLimit = {'dailyOutMinor': v, 'effectiveAt': '2026-11-20T13:05:00Z'};
        }
        return _json(_limits());
      case ('DELETE', '/v1/me/limits/daily/pending'):
        pendingDailyLimit = null;
        return _json(_limits());
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
        // Recherche simplifiée : nom, numéro ou motif contenant le texte.
        final q = req.url.queryParameters['q']?.toLowerCase();
        bool matches(Map<String, dynamic> h) =>
            q == null || [h['counterpartyName'], h['counterpartyPhone'], h['note'], h['receiptNumber']]
                .any((v) => v is String && v.toLowerCase().contains(q));
        final all = history.where((h) => (kinds == null || kinds.contains(h['kind'])) && matches(h)).toList();
        final limit = int.parse(req.url.queryParameters['limit'] ?? '30');
        final start = int.parse(req.url.queryParameters['cursor'] ?? '0');
        final end = (start + limit).clamp(0, all.length);
        return _json({'items': all.sublist(start, end), 'nextCursor': end < all.length ? '$end' : null});
      case ('GET', '/v1/transfers/recipient'):
        final p = req.url.queryParameters['phone']!;
        if (p == '+243970000001') return _error(422, 'SAME_ACCOUNT', 'Impossible');
        final name = recipients[p];
        if (name == null) return _error(404, 'RECIPIENT_NOT_FOUND', 'Aucun client');
        return _json({'phone': p, 'displayName': name});
      case ('GET', '/v1/transfers/pending'):
        return _json(pendingTransfers);
      case ('POST', '/v1/transfers/pending'):
        final b = body();
        if (b['pin'] != pin) return _error(401, 'INVALID_CREDENTIALS', 'Numéro ou PIN incorrect');
        final a = b['amountMinor'] as int;
        if (a > 10000) return _error(422, 'PENDING_TRANSFER_MAX', 'Au plus 100,00 \$ pour un proche sans compte');
        final t = pendingTransfer('pt-${pendingTransfers.length + 1}', b['recipientName'], b['toPhone'], a);
        if (a + (t['feeMinor'] as int) > balance) return _error(422, 'INSUFFICIENT_FUNDS', 'Solde insuffisant');
        balance -= a + (t['feeMinor'] as int);
        pendingTransfers = [t, ...pendingTransfers];
        return _json(t, 201);
      case ('GET', '/v1/me/favorites'):
        return _json(favorites);
      case ('POST', '/v1/me/favorites'):
        final b = body();
        final p = b['phone'] as String;
        final n = (b['nickname'] as String).trim();
        if (n.isEmpty || n.length > 30) return _error(422, 'INVALID_NICKNAME', 'Surnom invalide');
        final name = recipients[p];
        if (name == null) return _error(404, 'RECIPIENT_NOT_FOUND', 'Aucun client');
        favorites.removeWhere((f) => f['phone'] == p);
        if (favorites.length >= 20) return _error(422, 'FAVORITES_LIMIT', 'Trop de favoris');
        final f = {'id': 'fav-${++_favoriteSeq}', 'nickname': n, 'phone': p, 'displayName': name};
        favorites = [...favorites, f]..sort((a, b) => (a['nickname'] as String).compareTo(b['nickname'] as String));
        return _json(f);
      case ('GET', '/v1/transfers/recent'):
        return _json(recentRecipients);
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
