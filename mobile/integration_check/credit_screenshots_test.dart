@Tags(['captures'])
library;

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../test/fake_server.dart';

/// Captures du crédit express, écrites dans docs/captures-app :
///   flutter test integration_check/credit_screenshots_test.dart --update-goldens
void main() {
  late FakeServer server;
  late AppState app;

  setUp(() {
    server = FakeServer()
      ..balance = 42350
      ..kycLevel = 2;
    app = AppState(ApiClient(baseUrl: 'http://test', httpClient: server.client), deviceId: 'test');
  });

  for (final dark in [false, true]) {
    final suffix = dark ? '-sombre' : '';
    testWidgets('captures du crédit express${dark ? ' (mode sombre)' : ''}', (tester) async {
      Future<void> shot(String name) async {
        await tester.pump(const Duration(milliseconds: 400));
        await expectLater(find.byType(MaterialApp), matchesGoldenFile('../../docs/captures-app/$name.png'));
      }

      debugDisableShadows = false;
      addTearDown(() => debugDisableShadows = true);
      if (dark) app.setThemeMode(ThemeMode.dark);
      tester.view.physicalSize = const Size(780, 1688);
      tester.view.devicePixelRatio = 2;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(CapitalInvestApp(app: app));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
      await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
      await tester.tap(find.text('Se connecter'));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Crédit express'));
      await tester.pumpAndSettle();
      await shot('28-credit-offre$suffix');

      await tester.scrollUntilVisible(find.byKey(const ValueKey('credit-choose')), 200, scrollable: find.byType(Scrollable).last);
      await tester.tap(find.byKey(const ValueKey('credit-choose')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('credit-amount')), '100');
      await tester.tap(find.byKey(const ValueKey('credit-months-3')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('credit-quote')));
      await tester.pumpAndSettle();
      await shot('28b-credit-conditions$suffix');

      await tester.scrollUntilVisible(find.byKey(const ValueKey('credit-accept')), 200, scrollable: find.byType(Scrollable).last);
      await tester.tap(find.byKey(const ValueKey('credit-accept')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('pin-PIN')).last, '2580');
      await tester.tap(find.byKey(const ValueKey('pin-confirm')));
      await tester.pumpAndSettle();
      await shot('28c-credit-en-cours$suffix');
    });
  }
}
