import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/api/credit_models.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
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

  Future<void> openCredit(WidgetTester tester) async {
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Crédit express'));
    await tester.pumpAndSettle();
  }

  Future<void> confirmPin(WidgetTester tester, [String pin = '2580']) async {
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')).last, pin);
    await tester.tap(find.byKey(const ValueKey('pin-confirm')));
    await tester.pumpAndSettle();
  }

  Future<void> tapVisible(WidgetTester tester, Finder f) async {
    await tester.scrollUntilVisible(f, 200, scrollable: find.byType(Scrollable).last);
    await tester.tap(f);
    await tester.pumpAndSettle();
  }

  test('taux affichés à la française', () {
    expect(formatPercentBp(250), '2,5 %');
    expect(formatPercentBp(3449), '34,49 %');
    expect(formatPercentBp(300), '3 %');
    expect(formatPercentBp(305), '3,05 %');
  });

  testWidgets('niveau 1 : pas de crédit, on explique pourquoi', (tester) async {
    await start(tester);
    await openCredit(tester);
    expect(find.text('Le crédit express demande le niveau 2'), findsOneWidget);
    expect(find.text('Passer au niveau 2'), findsOneWidget);
    expect(find.byKey(const ValueKey('credit-choose')), findsNothing);
  });

  testWidgets('score trop bas : les raisons et les leviers pour progresser', (tester) async {
    server.kycLevel = 2;
    server.credit.score = 22;
    await start(tester);
    await openCredit(tester);
    expect(find.text('Pas encore de crédit pour vous'), findsOneWidget);
    expect(find.text('22 sur 100'), findsOneWidget);
    expect(find.text('Aucune recharge ni aucun envoi reçu ces 3 derniers mois'), findsOneWidget);
    expect(find.text('Pour progresser'), findsOneWidget);
  });

  testWidgets('offre, conditions, PIN, argent versé, échéancier', (tester) async {
    server.kycLevel = 2;
    await start(tester);
    await openCredit(tester);
    expect(find.text('150,00\u00A0\$', findRichText: true), findsOneWidget);
    expect(find.text('60 sur 100'), findsOneWidget);
    expect(find.text('300,00 \$ reçus par mois en moyenne sur 3 mois'), findsOneWidget);
    await tapVisible(tester, find.byKey(const ValueKey('credit-choose')));

    // Hors plafond : refusé avant d'appeler le serveur.
    await tester.enterText(find.byKey(const ValueKey('credit-amount')), '200');
    await tester.tap(find.byKey(const ValueKey('credit-quote')));
    await tester.pumpAndSettle();
    expect(find.text('Choisissez un montant entre 10,00\u00A0\$ et 150,00\u00A0\$.'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('credit-amount')), '100');
    await tester.tap(find.byKey(const ValueKey('credit-months-3')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('credit-quote')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('credit-terms')), findsOneWidget);
    expect(find.text('3 échéances de'), findsOneWidget);
    expect(find.text('34,49 % par an'), findsOneWidget);
    expect(find.byKey(const ValueKey('credit-review-note')), findsNothing);

    await tapVisible(tester, find.byKey(const ValueKey('credit-accept')));
    await confirmPin(tester, '1111');
    expect(find.text('Numéro ou PIN incorrect.'), findsOneWidget);
    expect(server.credit.current, isNull);
    await tapVisible(tester, find.byKey(const ValueKey('credit-accept')));
    await confirmPin(tester);

    expect(find.text('100,00\u00A0\$ sont sur votre compte et votre carte.'), findsOneWidget);
    expect(server.balance, 5000 + 10000);
    expect(find.byKey(const ValueKey('credit-current')), findsOneWidget);
    expect(find.text('À jour'), findsOneWidget);
    expect(find.textContaining('Prochaine échéance : 35,02\u00A0\$'), findsOneWidget);
    expect(find.text('Échéancier'), findsOneWidget);
  });

  testWidgets('au-dessus du seuil : demande en examen, que le client peut retirer', (tester) async {
    server.kycLevel = 2;
    server.credit.reviewThresholdMinor = 5000;
    await start(tester);
    await openCredit(tester);
    await tapVisible(tester, find.byKey(const ValueKey('credit-choose')));
    await tester.enterText(find.byKey(const ValueKey('credit-amount')), '100');
    await tester.tap(find.byKey(const ValueKey('credit-quote')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('credit-review-note')), findsOneWidget);
    expect(find.text('Envoyer ma demande'), findsOneWidget);
    await tapVisible(tester, find.byKey(const ValueKey('credit-accept')));
    await confirmPin(tester);

    expect(find.textContaining('Un agent de crédit l\'examine'), findsOneWidget);
    expect(find.text('En examen'), findsOneWidget);
    expect(server.balance, 5000);
    await tapVisible(tester, find.byKey(const ValueKey('credit-cancel')));
    expect(find.text('Demande retirée.'), findsOneWidget);
    expect(find.text('Non accordé'), findsOneWidget);
    expect(find.byKey(const ValueKey('credit-offer')), findsOneWidget);
  });

  testWidgets('en retard : le montant dû, payer le retard, puis tout rembourser', (tester) async {
    server.kycLevel = 2;
    server.balance = 20000;
    server.credit.current = server.credit.credit('ACTIVE', 10000, 3);
    server.credit.makeLate();
    await start(tester);
    await openCredit(tester);
    expect(find.text('En retard'), findsWidgets);
    await tester.scrollUntilVisible(find.byKey(const ValueKey('credit-late')), 200, scrollable: find.byType(Scrollable).last);
    expect(find.textContaining('35,02\u00A0\$ en retard depuis 6 jours'), findsOneWidget);

    await tapVisible(tester, find.byKey(const ValueKey('credit-repay')));
    await confirmPin(tester);
    expect(find.text('Remboursement reçu.'), findsOneWidget);
    expect(server.balance, 20000 - 3502);
    expect(find.text('À jour'), findsOneWidget);

    await tapVisible(tester, find.byKey(const ValueKey('credit-payoff')));
    expect(find.textContaining('Les intérêts des mois à venir ne sont pas dus'), findsOneWidget);
    await confirmPin(tester);
    expect(find.text('Crédit remboursé. Merci !'), findsOneWidget);
    expect(server.balance, 20000 - 3502 - 6748);
    expect(find.text('Remboursé'), findsOneWidget);
  });

  testWidgets('accès depuis le profil', (tester) async {
    server.kycLevel = 2;
    await start(tester);
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mon profil'));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('Crédit express'));
    expect(find.byKey(const ValueKey('credit-offer')), findsOneWidget);
  });
}
