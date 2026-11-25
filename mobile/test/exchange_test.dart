import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/api/models.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:capital_invest/screens/history_screens.dart';
import 'package:capital_invest/ui/chrome.dart';
import 'package:capital_invest/ui/format.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'fake_server.dart';

void main() {
  late FakeServer server;
  late AppState app;

  setUp(() {
    server = FakeServer();
    app = AppState(
      ApiClient(baseUrl: 'http://test', httpClient: server.client),
      deviceId: 'test',
    )..savePreference = (key, value) async {};
  });

  Future<void> start(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(CapitalInvestApp(app: app));
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
    await tester.tap(find.text('Se connecter'));
    await tester.pumpAndSettle();
  }

  String shown(WidgetTester tester, String key) => tester.widget<Amount>(find.byKey(ValueKey(key))).formatted;

  String text(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(ValueKey(key))).data!;

  /// L'aperçu et le bouton sont sous le pli : on les fait défiler à l'écran.
  Finder page() => find.byWidgetPredicate((w) => w is Scrollable && w.axisDirection == AxisDirection.down).first;

  Future<void> reveal(WidgetTester tester, Finder f) => tester.scrollUntilVisible(f, 200, scrollable: page());

  Future<String> received(WidgetTester tester) async {
    await reveal(tester, find.byKey(const ValueKey('exchange-received')));
    return text(tester, 'exchange-received');
  }

  Future<void> submit(WidgetTester tester) async {
    await reveal(tester, find.text('Changer'));
    await tester.tap(find.text('Changer'));
    await tester.pumpAndSettle();
  }

  Future<void> top(WidgetTester tester) async {
    await tester.drag(page(), const Offset(0, 2000));
    await tester.pumpAndSettle();
  }

  Future<void> openExchange(WidgetTester tester) async {
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Dollars et francs'));
    await tester.pumpAndSettle();
  }

  test('formats des francs et du taux', () {
    expect(formatCdf(28215), '28 215 FC');
    expect(formatCdf(0), '0 FC');
    expect(formatMoney(1050, 'USD'), '10,50 \$');
    expect(formatRate(2850), '2 850');
    expect(formatRate(2850.5), '2 850,5');
    expect(formatRate(1.5), '1,5');
    expect(parseCdf('25 000'), 25000);
    expect(parseCdf('12,5'), isNull);
    expect(parseCdf('0'), isNull);
  });

  test('aperçu : même calcul que le serveur', () {
    final r = ExchangeRate(cdfPerUsd: 2850, feeBps: 100, minUsdMinor: 100, minCdfMinor: 1000);
    final usd = r.quote('USD', 1000);
    expect((usd.feeMinor, usd.receivedMinor, usd.to), (10, 28215, 'CDF'));
    final cdf = r.quote('CDF', 28215);
    expect((cdf.feeMinor, cdf.receivedMinor, cdf.to), (282, 980, 'USD'));
  });

  testWidgets('l\'accueil montre le compte en francs ; changer 10 \$ crédite 28 215 FC', (tester) async {
    await start(tester);
    await reveal(tester, find.byKey(const ValueKey('home-francs')));
    expect(find.text('Compte en francs'), findsOneWidget);
    expect(find.text('Changez des dollars en francs congolais'), findsOneWidget);
    await openExchange(tester);

    expect(shown(tester, 'usd-balance'), '50,00 \$');
    expect(shown(tester, 'cdf-balance'), '0 FC');
    expect(text(tester, 'exchange-rate'), '1 \$ = 2 850 FC · frais 1 %');

    await tester.enterText(find.byKey(const ValueKey('amount')), '10');
    await tester.pump();
    expect(await received(tester), '28 215 FC');
    expect(find.text('0,10 \$'), findsOneWidget);

    await submit(tester);
    await top(tester);
    expect(find.byKey(const ValueKey('exchange-done')), findsOneWidget);
    expect(shown(tester, 'usd-balance'), '40,00 \$');
    expect(shown(tester, 'cdf-balance'), '28 215 FC');
    final post = server.requests.lastWhere((r) => r.url.path == '/v1/me/exchange' && r.method == 'POST');
    expect(post.body, contains('"rate":2850'));
    expect(post.headers['Idempotency-Key'], isNotNull);

    // Le compte en francs liste le change ; son reçu donne les deux côtés et le taux.
    expect(find.text('Change en francs'), findsOneWidget);
    await tester.tap(find.text('Change en francs'));
    await tester.pumpAndSettle();
    expect(find.text('Reçu'), findsWidgets);
    expect(find.text('10,00 \$'), findsOneWidget);
    expect(find.text('1 \$ = 2 850 FC'), findsOneWidget);
    expect(find.byKey(const ValueKey('receipt-amount')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('receipt-amount'))).data, '+28 215 FC');
  });

  testWidgets('dans l\'autre sens, et refus si le solde ne suffit pas', (tester) async {
    server.exchange.francs = 60000;
    await start(tester);
    expect(find.text('60 000 FC'), findsOneWidget);
    await openExchange(tester);
    await tester.tap(find.text('FC vers \$'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('amount')), '70000');
    await submit(tester);
    await top(tester);
    expect(find.textContaining('Vous avez 60 000 FC'), findsOneWidget);

    // 28 215 FC : 282 FC de frais, 9,80 $ reçus.
    await tester.enterText(find.byKey(const ValueKey('amount')), '28 215');
    await tester.pump();
    expect(await received(tester), '9,80 \$');
    await submit(tester);
    await top(tester);
    expect(shown(tester, 'usd-balance'), '59,80 \$');
    expect(shown(tester, 'cdf-balance'), '31 785 FC');
    expect(find.text('Change en dollars'), findsOneWidget);
  });

  testWidgets('le taux a changé : rien n\'est changé, l\'aperçu se met à jour', (tester) async {
    await start(tester);
    await openExchange(tester);
    await tester.enterText(find.byKey(const ValueKey('amount')), '10');
    await tester.pump();
    server.exchange.moveRate(2900);
    await submit(tester);
    await top(tester);
    expect(find.textContaining('Le taux a changé'), findsOneWidget);
    expect(shown(tester, 'cdf-balance'), '0 FC');
    expect(text(tester, 'exchange-rate'), '1 \$ = 2 900 FC · frais 1 %');
    expect(await received(tester), '28 710 FC');
    await submit(tester);
    await top(tester);
    expect(shown(tester, 'cdf-balance'), '28 710 FC');
  });

  test('ligne d\'historique d\'un change', () {
    final item = HistoryItem.fromJson({
      'id': 'x-1',
      'receiptNumber': 'CI-1',
      'kind': 'EXCHANGE',
      'amountMinor': 990,
      'feeMinor': 10,
      'netMinor': -1000,
      'currency': 'USD',
      'createdAt': '2026-11-21T09:30:00Z',
      'exchange': {'from': 'USD', 'to': 'CDF', 'fromMinor': 1000, 'feeMinor': 10, 'toMinor': 28215, 'rate': 2850},
    });
    expect(historyTitle(item), 'Change en francs');
    expect(exchangeRows(item.exchange!).map((r) => r.$1), ['Changé', 'Frais', 'Reçu', 'Taux']);
  });
}
