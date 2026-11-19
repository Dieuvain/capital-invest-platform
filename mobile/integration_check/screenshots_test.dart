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
      ..history = [
        FakeServer.historyItem('a1b2c3', 'TRANSFER_SENT', -20200, fee: 200, at: '2026-11-18T16:42:00Z',
            name: 'Grâce Mbuyi', phone: '+243 81• ••• 214', note: 'Frais scolaires'),
        FakeServer.historyItem('d4e5f6', 'TRANSFER_RECEIVED', 4500, at: '2026-11-18T12:10:00Z',
            name: 'Patrick Ilunga', phone: '+243 99• ••• 870'),
        FakeServer.historyItem('a7b8c9', 'TOPUP', 100000, at: '2026-11-17T09:05:00Z', operator: 'MPESA'),
        FakeServer.historyItem('d1e2f3', 'WITHDRAWAL', -40600, fee: 600, at: '2026-11-16T18:30:00Z',
            operator: 'AIRTEL', msisdn: '+243970000001'),
      ];
    app = AppState(
      ApiClient(baseUrl: 'http://test', httpClient: server.client),
      deviceId: 'test',
    );
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

  Future<void> nav(WidgetTester tester, String tab) async {
    await tester.tap(find.byTooltip(tab));
    await tester.pumpAndSettle();
  }

  for (final dark in [false, true]) {
    final suffix = dark ? '-sombre' : '';
    testWidgets('captures${dark ? ' (mode sombre)' : ''}', (tester) async {
      tester_ = tester;
      debugDisableShadows = false; // vraies ombres douces, comme sur un téléphone
      addTearDown(() => debugDisableShadows = true);
      if (dark) app.setThemeMode(ThemeMode.dark);
      await start(tester);
      await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970 000 001');
      await tester.pumpAndSettle();
      await shot('1-connexion$suffix');

      await login(tester);
      await shot('2-accueil$suffix');
      await tester.drag(find.byType(ListView).first, const Offset(0, -420));
      await tester.pumpAndSettle();
      await shot('2a-accueil-promotions$suffix');
      await tester.drag(find.byType(ListView).first, const Offset(0, 420));
      await tester.pumpAndSettle();

      await nav(tester, 'Historique');
      await shot('2b-historique$suffix');
      await tester.tap(find.text('Envoi à Grâce Mbuyi'));
      await tester.pumpAndSettle();
      await shot('2e-recu$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await nav(tester, 'Plafonds');
      await shot('2c-plafonds$suffix');
      await nav(tester, 'Profil');
      await shot('2d-profil$suffix');
      await nav(tester, 'Accueil');

      await tester.tap(find.byKey(const ValueKey('quick-Envoyer')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('phone-Numéro du destinataire')), '970000002');
      await tester.enterText(find.byKey(const ValueKey('amount')), '150');
      await tester.tap(find.text('Continuer'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '25');
      await tester.pump();
      await shot('3-envoi-verification$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('quick-Recharger')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('amount')), '25');
      await tester.tap(find.widgetWithText(FilledButton, 'Recharger'));
      await tester.pump();
      await tester.pump();
      await shot('4-recharge-attente$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('quick-Plus')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Passer au niveau 2'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('idNumber')), 'CE 1234 5678');
      await tester.pump();
      await shot('5-niveau-2$suffix');
    });
  }
}
