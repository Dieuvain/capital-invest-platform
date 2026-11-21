import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';

import 'models.dart';

/// Erreur renvoyée par le serveur (RFC 7807), avec un code stable à traduire.
class ApiException implements Exception {
  ApiException(this.status, this.code, this.detail);

  final int status;
  final String code;
  final String detail;

  /// La session n'est plus valable : il faut redemander le PIN.
  bool get needsLogin => status == 401 && code != 'INVALID_CREDENTIALS';

  @override
  String toString() => 'ApiException($status, $code, $detail)';
}

/// Clé d'idempotence : la même clé est renvoyée si l'utilisateur réessaie, pour ne jamais payer deux fois.
String newIdempotencyKey() {
  final r = Random.secure();
  return List.generate(16, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
}

class ApiClient {
  ApiClient({required this.baseUrl, http.Client? httpClient}) : _http = httpClient ?? http.Client();

  final String baseUrl;
  final http.Client _http;
  String? token;

  Map<String, String> _headers({String? idempotencyKey, bool json = true}) => {
    if (json) 'Content-Type': 'application/json',
    'Accept': 'application/json',
    'Authorization': ?(token == null ? null : 'Bearer $token'),
    'Idempotency-Key': ?idempotencyKey,
  };

  Uri _uri(String path, [Map<String, String>? query]) => Uri.parse('$baseUrl$path').replace(queryParameters: query);

  Future<dynamic> _send(Future<http.Response> request) async {
    final http.Response res;
    try {
      res = await request.timeout(const Duration(seconds: 30));
    } on Exception {
      throw ApiException(0, 'NETWORK', 'Pas de connexion. Vérifiez votre réseau et réessayez.');
    }
    final body = res.body.isEmpty ? null : jsonDecode(utf8.decode(res.bodyBytes));
    if (res.statusCode >= 400) {
      final map = body is Map<String, dynamic> ? body : const <String, dynamic>{};
      throw ApiException(
        res.statusCode,
        map['code'] ?? 'HTTP_${res.statusCode}',
        map['detail'] ?? 'Erreur ${res.statusCode}',
      );
    }
    return body;
  }

  Future<dynamic> _get(String path, [Map<String, String>? query]) =>
      _send(_http.get(_uri(path, query), headers: _headers()));

  Future<dynamic> _post(String path, Object? body, {String? idempotencyKey}) => _send(
    _http.post(
      _uri(path),
      headers: _headers(idempotencyKey: idempotencyKey),
      body: jsonEncode(body),
    ),
  );

  Future<dynamic> _put(String path, Object? body) =>
      _send(_http.put(_uri(path), headers: _headers(), body: jsonEncode(body)));

  Future<dynamic> _delete(String path) => _send(_http.delete(_uri(path), headers: _headers()));

  // ---- Connexion -----------------------------------------------------------------------------

  Future<void> requestSignupCode(String phone) => _post('/v1/auth/otp', {'phone': phone});

  Future<SessionTokens> register({
    required String phone,
    required String code,
    required String fullName,
    required String pin,
    required String deviceId,
    String? deviceName,
    String language = 'fr',
  }) async {
    final j = await _post('/v1/auth/register', {
      'phone': phone,
      'code': code,
      'fullName': fullName,
      'language': language,
      'pin': pin,
      'deviceId': deviceId,
      'deviceName': ?deviceName,
    });
    return _keep(SessionTokens.fromJson(j));
  }

  Future<SessionTokens> login({
    required String phone,
    required String pin,
    required String deviceId,
    String? deviceName,
  }) async => _keep(
    SessionTokens.fromJson(
      await _post('/v1/auth/login', {'phone': phone, 'pin': pin, 'deviceId': deviceId, 'deviceName': ?deviceName}),
    ),
  );

  /// Connexion par empreinte, avec la clé reçue à l'activation sur ce téléphone.
  Future<SessionTokens> biometricLogin({
    required String phone,
    required String key,
    required String deviceId,
    String? deviceName,
  }) async => _keep(
    SessionTokens.fromJson(
      await _post('/v1/auth/biometric', {'phone': phone, 'key': key, 'deviceId': deviceId, 'deviceName': ?deviceName}),
    ),
  );

  Future<void> requestPinResetCode(String phone) => _post('/v1/auth/pin-reset/otp', {'phone': phone});

  Future<SessionTokens> resetPin({
    required String phone,
    required String code,
    required String newPin,
    required String deviceId,
    String? deviceName,
  }) async => _keep(
    SessionTokens.fromJson(
      await _post('/v1/auth/pin-reset', {
        'phone': phone,
        'code': code,
        'newPin': newPin,
        'deviceId': deviceId,
        'deviceName': ?deviceName,
      }),
    ),
  );

  Future<void> logout() async {
    if (token == null) return;
    try {
      await _post('/v1/auth/logout', null);
    } finally {
      token = null;
    }
  }

  // ---- Relevés de compte -------------------------------------------------------------------

  /// « Mes dépenses » : [month] au format 2026-11, le mois en cours par défaut.
  Future<SpendingSummary> spending([String? month]) async =>
      SpendingSummary.fromJson(await _get(month == null ? '/v1/me/spending' : '/v1/me/spending?month=$month'));

  Future<List<StatementMonth>> statementMonths() async =>
      (await _get('/v1/me/statements') as List).map((j) => StatementMonth.fromJson(j)).toList();

  Future<Statement> statement(String month) async => Statement.fromJson(await _get('/v1/me/statements/$month'));

  /// Le relevé en PDF, à partager ou enregistrer.
  Future<Uint8List> statementPdf(String month) async {
    final http.Response res;
    try {
      res = await _http
          .get(_uri('/v1/me/statements/$month/pdf'), headers: _headers(json: false))
          .timeout(const Duration(seconds: 30));
    } on Exception {
      throw ApiException(0, 'NETWORK', 'Pas de connexion. Vérifiez votre réseau et réessayez.');
    }
    if (res.statusCode != 200) await _send(Future.value(res));
    return res.bodyBytes;
  }

  // ---- Sécurité ------------------------------------------------------------------------------

  /// Change le PIN : les autres téléphones sont déconnectés, celui-ci reste connecté.
  Future<void> changePin(String currentPin, String newPin) =>
      _post('/v1/me/security/pin', {'currentPin': currentPin, 'newPin': newPin});

  /// Active l'empreinte sur ce téléphone, PIN redemandé. Renvoie la clé, à garder dans le coffre du téléphone.
  Future<String> enableBiometric(String pin) async =>
      (await _post('/v1/me/security/biometric', {'pin': pin}))['key'] as String;

  Future<void> disableBiometric() => _delete('/v1/me/security/biometric');

  Future<List<DeviceSession>> deviceSessions() async =>
      (await _get('/v1/me/security/sessions') as List).map((j) => DeviceSession.fromJson(j)).toList();

  /// Seuil d'alerte de solde bas ; null : pas d'alerte.
  Future<int?> lowBalanceThreshold() async => (await _get('/v1/me/notifications/low-balance'))['thresholdMinor'] as int?;

  Future<void> setLowBalanceThreshold(int? minor) => _put('/v1/me/notifications/low-balance', {'thresholdMinor': minor});

  Future<ClosureCheck> closureCheck() async => ClosureCheck.fromJson(await _get('/v1/me/closure'));

  /// Ferme le compte : toutes les sessions tombent, celle-ci comprise.
  Future<void> closeAccount(String pin, String reason, String? comment) =>
      _post('/v1/me/closure', {'pin': pin, 'reason': reason, 'comment': ?comment});

  /// « Bloquer mon compte » : toutes les sessions tombent, celle-ci comprise. Déblocage en agence seulement.
  Future<void> blockAccount(String pin) => _post('/v1/me/security/block', {'pin': pin});

  /// Même blocage, depuis un autre téléphone, sans se connecter.
  Future<void> blockAccountFromPhone(String phone, String pin) => _post('/v1/auth/block', {'phone': phone, 'pin': pin});

  /// Déconnecte tous les autres téléphones. Renvoie le nombre de sessions fermées.
  Future<int> signOutOtherDevices() async =>
      (await _post('/v1/me/security/sessions/sign-out-others', null))['closed'] as int;

  SessionTokens _keep(SessionTokens t) {
    token = t.accessToken;
    return t;
  }

  // ---- Compte --------------------------------------------------------------------------------

  // ---- Crédit téléphone -------------------------------------------------------------------------

  /// Achète du crédit pour [phone] (+243…), confirmé par le PIN. Le réseau est deviné par le serveur s'il manque.
  Future<AirtimePurchase> buyAirtime({
    required String phone,
    required MobileNetwork? network,
    required int amountMinor,
    required String pin,
    required String idempotencyKey,
  }) async => AirtimePurchase.fromJson(
    await _post('/v1/airtime', {
      'phone': phone,
      'network': ?network?.apiName,
      'amountMinor': amountMinor,
      'pin': pin,
    }, idempotencyKey: idempotencyKey),
  );

  Future<List<RecentAirtimeNumber>> recentAirtimeNumbers() async =>
      (await _get('/v1/airtime/recent') as List).map((j) => RecentAirtimeNumber.fromJson(j)).toList();

  // ---- Factures ----------------------------------------------------------------------------------

  Future<List<Biller>> billers() async =>
      (await _get('/v1/bills/billers') as List).map((j) => Biller.fromJson(j)).toList();

  /// Nom du titulaire chez le facturier (et montant dû pour une facture), avant de payer.
  Future<BillAccount> lookupBill(String biller, String reference) async =>
      BillAccount.fromJson(await _post('/v1/bills/lookup', {'biller': biller, 'reference': reference}));

  Future<BillPayment> payBill({
    required String biller,
    required String reference,
    required int amountMinor,
    required String pin,
    required String idempotencyKey,
  }) async => BillPayment.fromJson(
    await _post('/v1/bills', {
      'biller': biller,
      'reference': reference,
      'amountMinor': amountMinor,
      'pin': pin,
    }, idempotencyKey: idempotencyKey),
  );

  Future<List<RecentBill>> recentBills() async =>
      (await _get('/v1/bills/recent') as List).map((j) => RecentBill.fromJson(j)).toList();

  // ---- Envois programmés -------------------------------------------------------------------------

  Future<List<ScheduledTransfer>> scheduledTransfers() async =>
      (await _get('/v1/me/scheduled-transfers') as List).map((j) => ScheduledTransfer.fromJson(j)).toList();

  /// Le PIN autorise toutes les échéances à venir.
  Future<ScheduledTransfer> scheduleTransfer({
    required String toPhone,
    required int amountMinor,
    required String frequency,
    required int day,
    required String pin,
    String? note,
  }) async => ScheduledTransfer.fromJson(
    await _post('/v1/me/scheduled-transfers', {
      'toPhone': toPhone,
      'amountMinor': amountMinor,
      'frequency': frequency,
      'day': day,
      'pin': pin,
      if (note != null && note.isNotEmpty) 'note': note,
    }),
  );

  Future<ScheduledTransfer> pauseScheduledTransfer(String id) async =>
      ScheduledTransfer.fromJson(await _post('/v1/me/scheduled-transfers/$id/pause', null));

  Future<ScheduledTransfer> resumeScheduledTransfer(String id) async =>
      ScheduledTransfer.fromJson(await _post('/v1/me/scheduled-transfers/$id/resume', null));

  Future<void> cancelScheduledTransfer(String id) => _delete('/v1/me/scheduled-transfers/$id');

  // ---- Réclamations -----------------------------------------------------------------------------

  /// Sur une opération (depuis son reçu) ou, sans [transactionId], sur le compte.
  Future<Complaint> openComplaint({String? transactionId, required String category, required String message}) async =>
      Complaint.fromJson(
        await _post('/v1/me/complaints', {'transactionId': ?transactionId, 'category': category, 'message': message}),
      );

  Future<List<Complaint>> complaints() async =>
      (await _get('/v1/me/complaints') as List).map((j) => Complaint.fromJson(j)).toList();

  // ---- Épargne ---------------------------------------------------------------------------------

  Future<Savings> savings() async => Savings.fromJson(await _get('/v1/me/savings'));

  /// Mettre de côté : du solde vers l'épargne, sans frais.
  Future<Savings> saveMoney(int amountMinor, String idempotencyKey) async =>
      Savings.fromJson(await _post('/v1/me/savings/deposits', {'amountMinor': amountMinor}, idempotencyKey: idempotencyKey));

  /// Reprendre : de l'épargne vers le solde, à tout moment.
  Future<Savings> takeBackSavings(int amountMinor, String idempotencyKey) async =>
      Savings.fromJson(await _post('/v1/me/savings/withdrawals', {'amountMinor': amountMinor}, idempotencyKey: idempotencyKey));

  Future<Savings> setSavingsGoal(String label, int targetMinor) async =>
      Savings.fromJson(await _put('/v1/me/savings/goal', {'label': label, 'targetMinor': targetMinor}));

  Future<Savings> clearSavingsGoal() async => Savings.fromJson(await _delete('/v1/me/savings/goal'));

  Future<Customer> me() async => Customer.fromJson((await _get('/v1/me'))['customer']);

  Future<int> balance() async => (await _get('/v1/me/balance'))['balanceMinor'] as int;

  Future<Limits> limits() async => Limits.fromJson(await _get('/v1/me/limits'));

  /// Plafond d'envoi par jour choisi par le client ([minor] null : celui du niveau). Une hausse attend 24 h.
  Future<Limits> setDailyLimit(int? minor, String pin) async =>
      Limits.fromJson(await _put('/v1/me/limits/daily', {'dailyOutMinor': minor, 'pin': pin}));

  /// Annule la hausse en attente : le plafond actuel reste.
  Future<Limits> cancelDailyLimitRaise() async => Limits.fromJson(await _delete('/v1/me/limits/daily/pending'));

  /// Historique des opérations. [category] : TOPUP, TRANSFER ou WITHDRAWAL ; [cursor] : page suivante.
  /// [search] : nom, numéro, motif, montant ou n° de reçu.
  Future<HistoryPage> history({String? category, String? cursor, int limit = 30, String? search}) async => HistoryPage.fromJson(
    await _get('/v1/me/history', {'category': ?category, 'cursor': ?cursor, 'limit': '$limit', 'q': ?search}),
  );

  Future<Receipt> receipt(String id) async => Receipt.fromJson(await _get('/v1/me/history/$id'));

  Future<NotificationInbox> notifications() async => NotificationInbox.fromJson(await _get('/v1/me/notifications'));

  /// Marque toutes les notifications comme lues.
  Future<void> markNotificationsRead() => _post('/v1/me/notifications/read', const <String, dynamic>{});

  // ---- Argent --------------------------------------------------------------------------------

  Future<Topup> startTopup(Operator op, int amountMinor, String idempotencyKey) async => Topup.fromJson(
    await _post('/v1/topups', {'operator': op.apiName, 'amountMinor': amountMinor}, idempotencyKey: idempotencyKey),
  );

  Future<Topup> topup(String id) async => Topup.fromJson(await _get('/v1/topups/$id'));

  /// Nom du destinataire (prénom et initiale) avant de confirmer : évite d'envoyer au mauvais numéro.
  Future<Recipient> lookupRecipient(String phone) async =>
      Recipient.fromJson(await _get('/v1/transfers/recipient', {'phone': phone}));

  /// Derniers clients à qui l'argent a été envoyé.
  Future<List<Recipient>> recentRecipients() async =>
      (await _get('/v1/transfers/recent') as List).map((j) => Recipient.fromJson(j)).toList();

  /// Envoi à un numéro sans compte : l'argent attend que le proche ouvre son compte.
  Future<PendingTransfer> sendPendingTransfer({
    required String toPhone,
    required String recipientName,
    required int amountMinor,
    required String pin,
    required String idempotencyKey,
    String? note,
  }) async => PendingTransfer.fromJson(
    await _post('/v1/transfers/pending', {
      'toPhone': toPhone,
      'recipientName': recipientName,
      'amountMinor': amountMinor,
      'pin': pin,
      if (note != null && note.isNotEmpty) 'note': note,
    }, idempotencyKey: idempotencyKey),
  );

  Future<List<PendingTransfer>> pendingTransfers() async =>
      (await _get('/v1/transfers/pending') as List).map((j) => PendingTransfer.fromJson(j)).toList();

  /// Reprendre l'argent, frais compris, tant que le proche ne l'a pas reçu.
  Future<PendingTransfer> cancelPendingTransfer(String id) async =>
      PendingTransfer.fromJson(await _delete('/v1/transfers/pending/$id'));

  /// Mes cagnottes : ouvertes d'abord.
  Future<List<Pot>> myPots() async => (await _get('/v1/me/pots') as List).map((j) => Pot.fromJson(j)).toList();

  Future<Pot> createPot({required String title, String? description, int? targetMinor}) async => Pot.fromJson(
    await _post('/v1/me/pots', {'title': title, 'description': description, 'targetMinor': targetMinor}),
  );

  /// Clôture : tout l'argent collecté passe sur mon solde.
  Future<Pot> closePot(String id) async => Pot.fromJson(await _post('/v1/me/pots/$id/close', null));

  /// Une cagnotte par son code, la mienne ou celle d'un proche.
  Future<Pot> pot(String code) async => Pot.fromJson(await _get('/v1/pots/$code'));

  Future<Pot> contributeToPot({
    required String code,
    required int amountMinor,
    required String pin,
    required String idempotencyKey,
    String? message,
    bool hideName = false,
  }) async => Pot.fromJson(
    await _post('/v1/pots/$code/contributions', {
      'amountMinor': amountMinor,
      'pin': pin,
      'message': message,
      'hideName': hideName,
    }, idempotencyKey: idempotencyKey),
  );

  /// Favoris : destinataires enregistrés avec un surnom, triés par surnom.
  Future<List<Favorite>> favorites() async =>
      (await _get('/v1/me/favorites') as List).map((j) => Favorite.fromJson(j)).toList();

  /// Ajoute un favori, ou change le surnom s'il existe déjà.
  Future<Favorite> saveFavorite(String phone, String nickname) async =>
      Favorite.fromJson(await _post('/v1/me/favorites', {'phone': phone, 'nickname': nickname}));

  Future<void> deleteFavorite(String id) => _delete('/v1/me/favorites/$id');

  Future<FeeQuote> transferFee(int amountMinor) async =>
      FeeQuote.fromJson(await _get('/v1/transfers/fee', {'amountMinor': '$amountMinor'}));

  Future<TransferResult> transfer({
    required String toPhone,
    required int amountMinor,
    required String pin,
    required String idempotencyKey,
    String? note,
  }) async => TransferResult.fromJson(
    await _post('/v1/transfers', {
      'toPhone': toPhone,
      'amountMinor': amountMinor,
      'pin': pin,
      if (note != null && note.isNotEmpty) 'note': note,
    }, idempotencyKey: idempotencyKey),
  );

  // ---- Demandes d'argent ----------------------------------------------------------------------

  Future<MoneyRequestList> moneyRequests() async => MoneyRequestList.fromJson(await _get('/v1/money-requests'));

  Future<MoneyRequest> requestMoney({
    required String payerPhone,
    required int amountMinor,
    required String idempotencyKey,
    String? note,
  }) async => MoneyRequest.fromJson(
    await _post('/v1/money-requests', {
      'payerPhone': payerPhone,
      'amountMinor': amountMinor,
      if (note != null && note.isNotEmpty) 'note': note,
    }, idempotencyKey: idempotencyKey),
  );

  /// Payer une demande reçue : un envoi ordinaire, confirmé par le PIN.
  Future<TransferResult> payMoneyRequest(String id, String pin) async =>
      TransferResult.fromJson((await _post('/v1/money-requests/$id/pay', {'pin': pin}))['transfer']);

  Future<MoneyRequest> declineMoneyRequest(String id) async =>
      MoneyRequest.fromJson(await _post('/v1/money-requests/$id/decline', const <String, dynamic>{}));

  Future<MoneyRequest> cancelMoneyRequest(String id) async =>
      MoneyRequest.fromJson(await _post('/v1/money-requests/$id/cancel', const <String, dynamic>{}));

  // ---- Paiement marchand ----------------------------------------------------------------------

  Future<MerchantInfo> merchant(String code) async => MerchantInfo.fromJson(await _get('/v1/merchants/$code'));

  Future<MerchantPaymentResult> payMerchant({
    required String code,
    required int amountMinor,
    required String pin,
    required String idempotencyKey,
    String? note,
  }) async => MerchantPaymentResult.fromJson(
    await _post('/v1/merchant-payments', {
      'merchantCode': code,
      'amountMinor': amountMinor,
      'pin': pin,
      if (note != null && note.isNotEmpty) 'note': note,
    }, idempotencyKey: idempotencyKey),
  );

  // ---- Retraits ------------------------------------------------------------------------------

  Future<FeeQuote> withdrawalFee(int amountMinor) async =>
      FeeQuote.fromJson(await _get('/v1/withdrawals/fee', {'amountMinor': '$amountMinor'}));

  Future<Withdrawal> withdraw({
    required Operator op,
    required int amountMinor,
    required String pin,
    required String idempotencyKey,
  }) async => Withdrawal.fromJson(
    await _post('/v1/withdrawals', {
      'operator': op.apiName,
      'amountMinor': amountMinor,
      'pin': pin,
    }, idempotencyKey: idempotencyKey),
  );

  Future<Withdrawal> withdrawal(String id) async => Withdrawal.fromJson(await _get('/v1/withdrawals/$id'));

  // ---- Promotions ---------------------------------------------------------------------------

  Future<List<Promotion>> promotions() async =>
      ((await _get('/v1/promotions')) as List).map((e) => Promotion.fromJson(e)).toList();

  /// Visuels déjà chargés. L'adresse change à chaque nouveau visuel (?v=…), on peut donc la garder telle quelle.
  final _images = <String, Uint8List>{};

  /// Charge le visuel d'une promotion, une seule fois par adresse.
  Future<Uint8List> promotionImage(String url) async {
    final cached = _images[url];
    if (cached != null) return cached;
    final http.Response res;
    try {
      res = await _http
          .get(Uri.parse('$baseUrl$url'), headers: _headers(json: false))
          .timeout(const Duration(seconds: 30));
    } on Exception {
      throw ApiException(0, 'NETWORK', 'Pas de connexion. Vérifiez votre réseau et réessayez.');
    }
    if (res.statusCode != 200 || !(res.headers['content-type'] ?? '').startsWith('image/')) {
      throw ApiException(res.statusCode, 'PROMOTION_IMAGE', 'Visuel indisponible');
    }
    if (_images.length >= 10) _images.remove(_images.keys.first);
    return _images[url] = res.bodyBytes;
  }

  // ---- KYC 2 ---------------------------------------------------------------------------------

  Future<KycState> kyc() async => KycState.fromJson(await _get('/v1/me/kyc'));

  Future<void> uploadKycDocument(String kind, Uint8List bytes, {required bool png}) async {
    final req = http.MultipartRequest('POST', _uri('/v1/me/kyc/documents/$kind'))
      ..headers.addAll(_headers(json: false))
      ..files.add(
        http.MultipartFile.fromBytes(
          'file',
          bytes,
          filename: png ? 'photo.png' : 'photo.jpg',
          contentType: MediaType('image', png ? 'png' : 'jpeg'),
        ),
      );
    await _send(_http.send(req).then(http.Response.fromStream));
  }

  Future<KycState> submitKyc({required String idType, required String idNumber, required DateTime birthDate}) async =>
      KycState.fromJson(
        await _post('/v1/me/kyc/submission', {
          'idType': idType,
          'idNumber': idNumber,
          'birthDate': birthDate.toIso8601String().substring(0, 10),
        }),
      );
}
