@Tags(['serveur'])
library;

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/api/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

/// Vérifie le client de l'app contre le vrai serveur, simulateurs activés :
///   API_URL=http://localhost:8080 flutter test integration_check
/// Ces tests ne tournent pas dans `flutter test` seul : ils ont besoin du serveur.
const base = String.fromEnvironment('API_URL', defaultValue: 'http://localhost:8080');

String randomPhone() => '+24397${(1000000 + Random().nextInt(8999999))}';

Future<String> lastSmsCode(String phone) async {
  final res = await http.get(Uri.parse('$base/v1/simulators/sms/$phone'));
  return RegExp(r'[0-9]{6}').firstMatch(jsonDecode(res.body)['text'])!.group(0)!;
}

Future<ApiClient> newCustomer(String phone, String name) async {
  final api = ApiClient(baseUrl: base);
  await api.requestSignupCode(phone);
  await api.register(phone: phone, code: await lastSmsCode(phone), fullName: name, pin: '2580', deviceId: 'controle');
  return api;
}

Future<void> confirmTopup(String id) =>
    http.post(Uri.parse('$base/v1/simulators/mobile-money/callbacks'),
        headers: {'Content-Type': 'application/json'}, body: jsonEncode({'topupId': id, 'success': true}));

void main() {
  test('parcours complet de l\'app contre le vrai serveur', () async {
    final alicePhone = randomPhone();
    final bobPhone = randomPhone();
    final alice = await newCustomer(alicePhone, 'Amani Kabeya');
    await newCustomer(bobPhone, 'Bob Ilunga');

    final me = await alice.me();
    expect(me.kycLevel, 1);
    expect((await alice.limits()).perTransactionMinor, 20000);

    final topup = await alice.startTopup(Operator.mpesa, 5000, newIdempotencyKey());
    expect(topup.status, 'PENDING');
    await confirmTopup(topup.id);
    expect((await alice.topup(topup.id)).status, 'CONFIRMED');
    expect(await alice.balance(), 5000);

    final quote = await alice.transferFee(1250);
    expect(quote.feeMinor, 13);
    final sent = await alice.transfer(toPhone: bobPhone, amountMinor: 1250, pin: '2580', idempotencyKey: newIdempotencyKey());
    expect(sent.totalDebitedMinor, 1263);
    expect(await alice.balance(), 3737);
    final entries = await alice.entries();
    expect(entries.where((e) => e.isCredit).length, 1);
    expect(entries.where((e) => !e.isCredit).length, 2); // montant + frais

    final jpeg = Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, ...List.filled(500, 7)]);
    for (final kind in ['ID_FRONT', 'ID_BACK', 'SELFIE']) {
      await alice.uploadKycDocument(kind, jpeg, png: false);
    }
    final kyc = await alice.submitKyc(idType: 'VOTER_CARD', idNumber: 'CE123456', birthDate: DateTime(1990, 5, 12));
    expect(kyc.requestStatus, 'PENDING');
    expect((await alice.kyc()).requestStatus, 'PENDING');

    await expectLater(
      alice.transfer(toPhone: bobPhone, amountMinor: 1250, pin: '9999', idempotencyKey: newIdempotencyKey()),
      throwsA(isA<ApiException>().having((e) => e.code, 'code', 'INVALID_CREDENTIALS')),
    );
    await alice.logout();
    await expectLater(alice.balance(), throwsA(isA<ApiException>().having((e) => e.status, 'status', 401)));
  });
}
