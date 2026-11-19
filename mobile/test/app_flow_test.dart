import 'dart:typed_data';

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:capital_invest/screens/kyc_screen.dart';
import 'package:capital_invest/ui/chrome.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'fake_server.dart';

class FakePhotos implements PhotoSource {
  @override
  Future<Photo?> take({required bool selfie}) async => Photo(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3]), png: false);
}

void main() {
  late FakeServer server;
  late AppState app;

  setUp(() {
    server = FakeServer();
    app = AppState(ApiClient(baseUrl: 'http://test', httpClient: server.client), deviceId: 'test');
  });

  Future<void> start(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(CapitalInvestApp(app: app, photos: FakePhotos()));
  }

  Future<void> pay(WidgetTester tester, String action) async {
    await tester.tap(find.byKey(const ValueKey('pay-button')));
    await tester.pumpAndSettle();
    await tester.tap(find.text(action));
    await tester.pumpAndSettle();
  }

  Future<void> login(WidgetTester tester, {String pin = '2580'}) async {
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), pin);
    await tester.tap(find.text('Se connecter'));
    await tester.pumpAndSettle();
  }

  testWidgets('connexion puis accueil avec solde et plafond du jour', (tester) async {
    await start(tester);
    await login(tester);
    expect(find.text('Bonjour Amani'), findsOneWidget);
    expect(tester.widget<Amount>(find.byKey(const ValueKey('balance'))).formatted, '50,00\u00A0\$');
    expect(find.byWidgetPredicate((w) => w is Amount && w.formatted == '500,00\u00A0\$'), findsOneWidget); // reste à envoyer aujourd'hui
    expect(find.text('Argent reçu'), findsOneWidget);
    expect(find.textContaining('Passez au niveau 2'), findsOneWidget);
  });

  testWidgets('un mauvais PIN affiche un message clair', (tester) async {
    await start(tester);
    await login(tester, pin: '9999');
    expect(find.text('Numéro ou PIN incorrect.'), findsOneWidget);
    expect(find.text('Se connecter'), findsOneWidget);
  });

  testWidgets('envoi : les frais sont montrés avant la confirmation par PIN', (tester) async {
    await start(tester);
    await login(tester);
    await pay(tester, 'Envoyer');
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du destinataire')), '970000002');
    await tester.enterText(find.byKey(const ValueKey('amount')), '12,50');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();

    expect(find.text('Vérifier l\'envoi'), findsOneWidget);
    expect(find.text('0,13 \$'), findsOneWidget);
    expect(find.text('12,63 \$'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Envoyer 12,50 \$'));
    await tester.pumpAndSettle();
    expect(find.text('12,50 \$ envoyés'), findsOneWidget);
    expect(server.balance, 5000 - 1263);
    expect(server.requests.last.headers['Idempotency-Key'], isNotNull);
  });

  testWidgets('un montant au-dessus du plafond est arrêté avant l\'envoi', (tester) async {
    await start(tester);
    await login(tester);
    await pay(tester, 'Envoyer');
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du destinataire')), '970000002');
    await tester.enterText(find.byKey(const ValueKey('amount')), '250');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.textContaining('plafond par opération est de 200,00'), findsOneWidget);
  });

  testWidgets('recharge : attente de la validation puis confirmation', (tester) async {
    await start(tester);
    await login(tester);
    await pay(tester, 'Recharger');
    await tester.enterText(find.byKey(const ValueKey('amount')), '25');
    await tester.tap(find.widgetWithText(FilledButton, 'Recharger'));
    await tester.pump();
    await tester.pump();
    expect(find.text('Validez sur votre téléphone'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(find.text('Recharge reçue'), findsOneWidget);
  });

  testWidgets('une session expirée ramène à l\'écran de connexion', (tester) async {
    await start(tester);
    await login(tester);
    server.sessionExpired = true;
    await pay(tester, 'Recharger');
    await tester.enterText(find.byKey(const ValueKey('amount')), '25');
    await tester.tap(find.widgetWithText(FilledButton, 'Recharger'));
    await tester.pumpAndSettle();
    expect(find.text('Se connecter'), findsOneWidget);
  });

  testWidgets('niveau 2 : photos, pièce, date, puis demande en attente', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.text('Passez au niveau 2'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Envoyer ma demande'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Photo manquante'), findsOneWidget);

    for (final label in ['Recto de la pièce', 'Verso de la carte', 'Selfie, visage bien éclairé']) {
      await tester.tap(find.text(label));
      await tester.pumpAndSettle();
    }
    await tester.enterText(find.byKey(const ValueKey('idNumber')), 'CE123456');
    await tester.tap(find.byKey(const ValueKey('birthDate')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Envoyer ma demande'));
    await tester.pumpAndSettle();

    expect(server.uploads, ['ID_FRONT', 'ID_BACK', 'SELFIE']);
    expect(find.text('Demande en cours d\'examen'), findsOneWidget);
  });
}
