@Tags(['captures'])
library;


import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:capital_invest/screens/kyc_screen.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../test/fake_server.dart';

/// Captures d'écran des parcours principaux, écrites dans docs/captures-app :
///   flutter test integration_check/screenshots_test.dart --update-goldens
class _Photos implements PhotoSource {
  @override
  Future<Photo?> take({required bool selfie}) async => null;
}

void main() {
  late FakeServer server;
  late AppState app;
  late WidgetTester tester_;

  setUp(() {
    server = FakeServer()
      ..balance = 123750
      ..entries = [
        (1, 'DEBIT', 200, '2026-11-18T16:42:00Z'),
        (2, 'DEBIT', 20000, '2026-11-18T16:42:00Z'),
        (3, 'CREDIT', 4500, '2026-11-18T12:10:00Z'),
        (4, 'CREDIT', 100000, '2026-11-17T09:05:00Z'),
        (5, 'CREDIT', 40000, '2026-11-16T18:30:00Z'),
      ];
    app = AppState(ApiClient(baseUrl: 'http://test', httpClient: server.client), deviceId: 'test');
  });

  Future<void> start(WidgetTester tester) async {
    tester.view.physicalSize = const Size(780, 1688);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(CapitalInvestApp(app: app, photos: _Photos()));
    await tester.pumpAndSettle();
  }

  Future<void> shot(String name) async {
    await tester_.pump(const Duration(milliseconds: 400)); // fin des animations (étiquettes, indicateur)
    await expectLater(find.byType(MaterialApp), matchesGoldenFile('../../docs/captures-app/$name.png'));
  }

  Future<void> login(WidgetTester tester) async {
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
    await tester.tap(find.text('Se connecter'));
    await tester.pumpAndSettle();
  }

  testWidgets('captures', (tester) async {
    tester_ = tester;
    debugDisableShadows = false; // vraies ombres douces, comme sur un téléphone
    addTearDown(() => debugDisableShadows = true);
    await start(tester);
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970 000 001');
    await tester.pumpAndSettle();
    await shot('1-connexion');

    await login(tester);
    await shot('2-accueil');

    await tester.tap(find.byKey(const ValueKey('pay-button')));
    await tester.pumpAndSettle();
    await shot('2b-paiements');
    await tester.tap(find.text('Envoyer'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du destinataire')), '970000002');
    await tester.enterText(find.byKey(const ValueKey('amount')), '150');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '25');
    await tester.pump();
    await shot('3-envoi-verification');
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('pay-button')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Recharger'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('amount')), '25');
    await tester.tap(find.widgetWithText(FilledButton, 'Recharger'));
    await tester.pump();
    await tester.pump();
    await shot('4-recharge-attente');
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Plafonds'));
    await tester.pumpAndSettle();
    await shot('2c-plafonds');
    await tester.tap(find.text('Aperçu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Passez au niveau 2'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('idNumber')), 'CE 1234 5678');
    await tester.pump();
    await shot('5-niveau-2');
  });
}
