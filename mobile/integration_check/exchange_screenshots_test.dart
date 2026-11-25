@Tags(['captures'])
library;

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../test/fake_server.dart';

/// Captures du compte en francs et du change, écrites dans docs/captures-app :
///   flutter test integration_check/exchange_screenshots_test.dart --update-goldens
void main() {
  late FakeServer server;
  late AppState app;

  setUp(() {
    server = FakeServer()..balance = 123750;
    app = AppState(
      ApiClient(baseUrl: 'http://test', httpClient: server.client),
      deviceId: 'test',
    );
  });

  for (final dark in [false, true]) {
    final suffix = dark ? '-sombre' : '';
    testWidgets('captures du change${dark ? ' (mode sombre)' : ''}', (tester) async {
      Future<void> shot(String name) async {
        await tester.pump(const Duration(milliseconds: 400));
        await expectLater(find.byType(MaterialApp), matchesGoldenFile('../../docs/captures-app/$name.png'));
      }

      Finder page() => find.byWidgetPredicate((w) => w is Scrollable && w.axisDirection == AxisDirection.down).first;

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
      await tester.tap(find.text('Dollars et francs'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('amount')), '20');
      await tester.pump();
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();
      await shot('27-dollars-et-francs$suffix');

      await tester.scrollUntilVisible(find.text('Changer'), 200, scrollable: page());
      await tester.tap(find.text('Changer'));
      await tester.pumpAndSettle();
      await tester.drag(page(), const Offset(0, 2000));
      await tester.pumpAndSettle();
      await shot('27b-change-fait$suffix');

      await tester.scrollUntilVisible(find.text('Change en francs'), 200, scrollable: page());
      await tester.tap(find.text('Change en francs'));
      await tester.pumpAndSettle();
      await shot('27c-recu-change$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.drag(page(), const Offset(0, 2000));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();

      // Accueil : le compte en francs sous le solde en dollars.
      await tester.scrollUntilVisible(find.byKey(const ValueKey('home-francs')), 200, scrollable: page());
      await tester.drag(page(), const Offset(0, -150));
      await tester.pumpAndSettle();
      await shot('27d-accueil-francs$suffix');
    });
  }
}
