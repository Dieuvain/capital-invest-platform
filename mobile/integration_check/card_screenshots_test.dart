@Tags(['captures'])
library;

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../test/fake_cards.dart';
import '../test/fake_server.dart';

/// Captures des écrans Cartes, écrites dans docs/captures-app :
///   flutter test integration_check/card_screenshots_test.dart --update-goldens
void main() {
  late FakeServer server;
  late AppState app;

  setUp(() {
    server = FakeServer()
      ..balance = 123750
      ..kycLevel = 2;
    server.cards.cards
      ..add(FakeCards.card('v-1', 'VIRTUAL', 'ACTIVE', last4: '7899')..['spentTodayMinor'] = 4250)
      ..add(FakeCards.card('p-1', 'PHYSICAL', 'READY_FOR_PICKUP', last4: '3156'));
    server.cards.payments['v-1'] = [
      {
        'id': 'a3', 'cardId': 'v-1', 'amountMinor': 2999, 'currency': 'USD', 'merchantName': 'Netflix', 'online': true,
        'status': 'APPROVED', 'createdAt': '2026-11-21T09:12:00Z', 'transactionId': 't3',
      },
      {
        'id': 'a2', 'cardId': 'v-1', 'amountMinor': 1251, 'currency': 'USD', 'merchantName': 'Jumia RDC', 'online': true,
        'status': 'APPROVED', 'createdAt': '2026-11-20T18:40:00Z', 'transactionId': 't2',
      },
      {
        'id': 'a1', 'cardId': 'v-1', 'amountMinor': 60000, 'currency': 'USD', 'merchantName': 'Site inconnu', 'online': true,
        'status': 'DECLINED', 'declineCode': 'CARD_DAILY_LIMIT', 'declineMessage': 'Plafond journalier de la carte atteint',
        'createdAt': '2026-11-20T02:05:00Z',
      },
    ];
    app = AppState(ApiClient(baseUrl: 'http://test', httpClient: server.client), deviceId: 'test');
  });

  for (final dark in [false, true]) {
    final suffix = dark ? '-sombre' : '';
    testWidgets('captures des cartes${dark ? ' (mode sombre)' : ''}', (tester) async {
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

      await tester.tap(find.byKey(const ValueKey('back-card')));
      await tester.pumpAndSettle();
      await shot('26-cartes$suffix');

      await tester.tap(find.byKey(const ValueKey('card-v-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('card-show-number')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('pin-PIN')).last, '2580');
      await tester.tap(find.byKey(const ValueKey('pin-confirm')));
      await tester.pumpAndSettle();
      await shot('26b-carte-virtuelle$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('card-p-1')));
      await tester.pumpAndSettle();
      await shot('26c-carte-a-l-agence$suffix');
      await tester.scrollUntilVisible(find.byKey(const ValueKey('card-activate')), 200, scrollable: find.byType(Scrollable).last);
      await tester.tap(find.byKey(const ValueKey('card-activate')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('activation-code')), FakeCards.activationCode);
      await tester.pump();
      await shot('26d-activer-carte$suffix');
      // Le minuteur qui recache le numéro ne doit pas survivre au test.
      await tester.pump(const Duration(minutes: 2));
    });
  }
}
