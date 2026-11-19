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

  // ---- Connexion -----------------------------------------------------------------------------

  Future<void> requestSignupCode(String phone) => _post('/v1/auth/otp', {'phone': phone});

  Future<SessionTokens> register({
    required String phone,
    required String code,
    required String fullName,
    required String pin,
    required String deviceId,
    String language = 'fr',
  }) async {
    final j = await _post('/v1/auth/register', {
      'phone': phone,
      'code': code,
      'fullName': fullName,
      'language': language,
      'pin': pin,
      'deviceId': deviceId,
    });
    return _keep(SessionTokens.fromJson(j));
  }

  Future<SessionTokens> login({required String phone, required String pin, required String deviceId}) async =>
      _keep(SessionTokens.fromJson(await _post('/v1/auth/login', {'phone': phone, 'pin': pin, 'deviceId': deviceId})));

  Future<void> requestPinResetCode(String phone) => _post('/v1/auth/pin-reset/otp', {'phone': phone});

  Future<SessionTokens> resetPin({
    required String phone,
    required String code,
    required String newPin,
    required String deviceId,
  }) async => _keep(
    SessionTokens.fromJson(
      await _post('/v1/auth/pin-reset', {'phone': phone, 'code': code, 'newPin': newPin, 'deviceId': deviceId}),
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

  SessionTokens _keep(SessionTokens t) {
    token = t.accessToken;
    return t;
  }

  // ---- Compte --------------------------------------------------------------------------------

  Future<Customer> me() async => Customer.fromJson((await _get('/v1/me'))['customer']);

  Future<int> balance() async => (await _get('/v1/me/balance'))['balanceMinor'] as int;

  Future<Limits> limits() async => Limits.fromJson(await _get('/v1/me/limits'));

  Future<List<Entry>> entries({int limit = 20}) async =>
      ((await _get('/v1/me/entries', {'limit': '$limit'})) as List).map((e) => Entry.fromJson(e)).toList();

  // ---- Argent --------------------------------------------------------------------------------

  Future<Topup> startTopup(Operator op, int amountMinor, String idempotencyKey) async => Topup.fromJson(
    await _post('/v1/topups', {'operator': op.apiName, 'amountMinor': amountMinor}, idempotencyKey: idempotencyKey),
  );

  Future<Topup> topup(String id) async => Topup.fromJson(await _get('/v1/topups/$id'));

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
