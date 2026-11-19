import 'package:capital_invest/api/api_client.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fake_server.dart';

void main() {
  test('la connexion garde le jeton et les appels suivants l\'envoient', () async {
    final server = FakeServer();
    final api = ApiClient(baseUrl: 'http://test', httpClient: server.client);
    final t = await api.login(phone: '+243970000001', pin: '2580', deviceId: 'd');
    expect(t.customer.fullName, 'Amani Kabeya');
    expect(await api.balance(), 5000);
    expect(server.requests.last.headers['Authorization'], 'Bearer ${FakeServer.token}');
  });

  test('une erreur du serveur devient une ApiException avec son code', () async {
    final api = ApiClient(baseUrl: 'http://test', httpClient: FakeServer().client);
    await expectLater(
      api.login(phone: '+243970000001', pin: '9999', deviceId: 'd'),
      throwsA(
        isA<ApiException>()
            .having((e) => e.code, 'code', 'INVALID_CREDENTIALS')
            .having((e) => e.needsLogin, 'needsLogin', false),
      ),
    );
  });

  test('un envoi porte une clé d\'idempotence', () async {
    final server = FakeServer();
    final api = ApiClient(baseUrl: 'http://test', httpClient: server.client)..token = FakeServer.token;
    final key = newIdempotencyKey();
    final r = await api.transfer(toPhone: '+243970000002', amountMinor: 1250, pin: '2580', idempotencyKey: key);
    expect(r.feeMinor, 13);
    expect(server.requests.last.headers['Idempotency-Key'], key);
    expect(newIdempotencyKey(), isNot(key));
  });
}
