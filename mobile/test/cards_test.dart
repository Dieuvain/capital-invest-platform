import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/api/models.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:capital_invest/screens/card_screens.dart';
import 'package:capital_invest/screens/history_screens.dart';
import 'package:capital_invest/ui/format.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'fake_cards.dart';
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

  Future<void> openCards(WidgetTester tester) async {
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mes cartes'));
    await tester.pumpAndSettle();
  }

  Future<void> confirmPin(WidgetTester tester, [String pin = '2580']) async {
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')).last, pin);
    await tester.tap(find.byKey(const ValueKey('pin-confirm')));
    await tester.pumpAndSettle();
  }

  Future<void> scrollTo(WidgetTester tester, Finder f) =>
      tester.scrollUntilVisible(f, 200, scrollable: find.byType(Scrollable).last);

  testWidgets('niveau 1 : pas de carte, on explique pourquoi', (tester) async {
    await start(tester);
    await openCards(tester);
    expect(find.text('Les cartes demandent le niveau 2'), findsOneWidget);
    expect(find.byKey(const ValueKey('card-create-virtual')), findsNothing);
    expect(find.text('Passer au niveau 2'), findsOneWidget);
  });

  testWidgets('carte virtuelle : créée en une touche, numéro avec le PIN puis recaché, gel, plafond', (tester) async {
    server.kycLevel = 2;
    await start(tester);
    // Sur l'accueil, la carte de derrière invite à obtenir sa carte.
    expect(find.text('Obtenir ma carte'), findsOneWidget);
    await openCards(tester);
    await tester.tap(find.byKey(const ValueKey('card-create-virtual')));
    await tester.pumpAndSettle();
    // La carte créée s'ouvre tout de suite.
    expect(find.text('Carte virtuelle'), findsWidgets);
    expect(find.text('••••  ••••  ••••  4201'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('card-show-number')));
    await tester.pumpAndSettle();
    await confirmPin(tester, '1111');
    expect(find.text('Numéro ou PIN incorrect.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('card-show-number')));
    await tester.pumpAndSettle();
    await confirmPin(tester);
    expect(find.text('4000 0012 3456 7899'), findsOneWidget);
    expect(find.text('Expire 11/29 · CVV 123'), findsOneWidget);
    // Recaché tout seul au bout d'une minute.
    await tester.pump(CardScreenTiming.showFor);
    await tester.pumpAndSettle();
    expect(find.text('4000 0012 3456 7899'), findsNothing);
    expect(find.text('••••  ••••  ••••  4201'), findsOneWidget);

    // Geler : sans PIN, tout de suite.
    await tester.tap(find.byKey(const ValueKey('card-freeze')));
    await tester.pumpAndSettle();
    expect(find.text('Gelée'), findsOneWidget);
    expect(server.cards.cards.single['status'], 'FROZEN');
    // Dégeler : avec le PIN.
    await tester.tap(find.byKey(const ValueKey('card-freeze')));
    await tester.pumpAndSettle();
    await confirmPin(tester);
    expect(find.text('Carte de nouveau active.'), findsOneWidget);
    expect(server.cards.cards.single['status'], 'ACTIVE');

    await tester.tap(find.byKey(const ValueKey('card-limit-row')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('card-limit')), '200');
    await tester.tap(find.byKey(const ValueKey('card-limit-save')));
    await tester.pumpAndSettle();
    expect(find.text('Plafond changé : ${formatUsd(20000)} par jour.'), findsOneWidget);
    expect(server.cards.cards.single['dailyLimitMinor'], 20000);

    // De retour sur l'accueil, la carte de derrière montre la carte virtuelle.
    Navigator.of(tester.element(find.byKey(const ValueKey('card-limit-row')))).pop();
    await tester.pumpAndSettle();
    Navigator.of(tester.element(find.text('Cartes'))).pop();
    await tester.pumpAndSettle();
    expect(find.text('•••• 4201'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('back-card')));
    await tester.pumpAndSettle();
    expect(find.text('Cartes'), findsOneWidget);
  });

  testWidgets('carte physique : commande avec le PIN, suivi, activation avec le code du pli', (tester) async {
    server.kycLevel = 2;
    await start(tester);
    await openCards(tester);
    await tester.tap(find.byKey(const ValueKey('card-order-physical')));
    await tester.pumpAndSettle();
    expect(find.text('Prix de la carte : ${formatUsd(100)}, pris sur votre solde à la commande.'), findsOneWidget);
    // Plusieurs agences : le client choisit la sienne.
    await tester.tap(find.byKey(const ValueKey('card-order')));
    await tester.pumpAndSettle();
    expect(find.text('Choisissez l\'agence où retirer votre carte.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('card-branch')));
    await tester.pumpAndSettle();
    for (final city in ['Lubumbashi', 'Kalemie', 'Kinshasa']) {
      expect(find.text(city), findsWidgets);
    }
    await tester.tap(find.text(FakeCards.branch).last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('card-order')));
    await tester.pumpAndSettle();
    expect(find.text('Entrez votre PIN pour confirmer.'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
    await tester.tap(find.byKey(const ValueKey('card-order')));
    await tester.pumpAndSettle();
    final order = server.requests.lastWhere((r) => r.url.path == '/v1/me/cards/physical');
    expect(order.body, contains(FakeCards.branch));
    expect(order.headers['Idempotency-Key'], isNotEmpty);
    expect(server.balance, 4900);
    expect(find.textContaining('En fabrication. Nous vous prévenons par SMS'), findsOneWidget);
    // Déjà commandée : plus de bouton de commande.
    expect(find.byKey(const ValueKey('card-order-physical')), findsNothing);

    // La carte arrive à l'agence.
    server.cards.cards.single['status'] = 'READY_FOR_PICKUP';
    await tester.tap(find.byKey(ValueKey('card-${server.cards.cards.single['id']}')));
    await tester.pumpAndSettle();
    expect(find.text('À l\'agence'), findsOneWidget);
    await scrollTo(tester, find.byKey(const ValueKey('card-activate')));
    await tester.tap(find.byKey(const ValueKey('card-activate')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('activation-code')), '111111');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN de la carte')), '1234');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN de la carte, encore')), '1234');
    await tester.tap(find.byKey(const ValueKey('card-activate-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('PIN trop simple (suite de chiffres)'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('pin-PIN de la carte')), '4826');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN de la carte, encore')), '4826');
    await tester.tap(find.byKey(const ValueKey('card-activate-confirm')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Code d\'activation incorrect'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('activation-code')), FakeCards.activationCode);
    await tester.tap(find.byKey(const ValueKey('card-activate-confirm')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Carte activée'), findsOneWidget);
    expect(find.text('Active'), findsOneWidget);
    // Le PIN de la carte part au serveur pour le processeur ; les paiements en ligne restent coupés.
    expect(server.requests.lastWhere((r) => r.url.path.endsWith('/activate')).body, contains('"cardPin":"4826"'));
    final online = tester.widget<SwitchListTile>(find.byKey(const ValueKey('card-online')));
    expect(online.value, isFalse);
  });

  testWidgets('carte volée : blocage définitif avec le PIN, puis remplacement à 5 \$', (tester) async {
    server.kycLevel = 2;
    server.cards.cards.add(FakeCards.card('p-9', 'PHYSICAL', 'ACTIVE', last4: '5509'));
    server.cards.payments['p-9'] = [
      {
        'id': 'a1',
        'cardId': 'p-9',
        'amountMinor': 1250,
        'currency': 'USD',
        'merchantName': 'Shoprite Kolwezi',
        'online': false,
        'status': 'APPROVED',
        'createdAt': '2026-11-21T08:00:00Z',
        'transactionId': 't1',
      },
      {
        'id': 'a2',
        'cardId': 'p-9',
        'amountMinor': 9900,
        'currency': 'USD',
        'merchantName': 'Site inconnu',
        'online': true,
        'status': 'DECLINED',
        'declineCode': 'ONLINE_PAYMENTS_DISABLED',
        'declineMessage': 'Paiements en ligne désactivés pour cette carte',
        'createdAt': '2026-11-21T09:00:00Z',
      },
    ];
    await start(tester);
    await openCards(tester);
    await tester.tap(find.byKey(const ValueKey('card-p-9')));
    await tester.pumpAndSettle();
    await scrollTo(tester, find.text('Refusé : Paiements en ligne désactivés pour cette carte'));
    expect(find.text('Shoprite Kolwezi'), findsOneWidget);

    await scrollTo(tester, find.byKey(const ValueKey('card-block')));
    await tester.tap(find.byKey(const ValueKey('card-block')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('block-STOLEN')));
    await tester.pumpAndSettle();
    await confirmPin(tester);
    expect(find.text('Cette carte est bloquée définitivement.'), findsOneWidget);
    expect(server.cards.cards.first['blockReason'], 'STOLEN');
    // Plus de gel ni de numéro sur une carte bloquée.
    expect(find.byKey(const ValueKey('card-freeze')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('card-replace')));
    await tester.pumpAndSettle();
    expect(find.textContaining('${formatUsd(500)} sont pris sur votre solde'), findsOneWidget);
    await confirmPin(tester);
    expect(server.balance, 4500); // carte déjà là : seuls les 5 $ du remplacement
    // Retour à la liste : la nouvelle carte, commandée ; plus l'ancienne.
    expect(find.text('Cartes'), findsOneWidget);
    expect(find.byKey(const ValueKey('card-p-9')), findsNothing);
    expect(find.text('Commandée'), findsOneWidget);
  });

  testWidgets('carte virtuelle supprimée avec le PIN : on peut en recréer une', (tester) async {
    server.kycLevel = 2;
    server.cards.cards.add(FakeCards.card('v-9', 'VIRTUAL', 'ACTIVE', last4: '4299'));
    await start(tester);
    await openCards(tester);
    expect(find.byKey(const ValueKey('card-create-virtual')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('card-v-9')));
    await tester.pumpAndSettle();
    await scrollTo(tester, find.byKey(const ValueKey('card-delete')));
    await tester.tap(find.byKey(const ValueKey('card-delete')));
    await tester.pumpAndSettle();
    await confirmPin(tester);
    expect(find.byKey(const ValueKey('card-v-9')), findsNothing);
    expect(find.byKey(const ValueKey('card-create-virtual')), findsOneWidget);
  });

  test('historique : paiements et frais de carte nommés pour le client', () {
    HistoryItem item(String kind, {String? name}) => HistoryItem.fromJson({
      'id': 't',
      'receiptNumber': 'CI-1',
      'kind': kind,
      'amountMinor': 1250,
      'netMinor': -1250,
      'createdAt': '2026-11-21T08:00:00Z',
      'counterpartyName': name,
    });
    expect(historyTitle(item('CARD_PAYMENT', name: 'Jumia RDC')), 'Carte · Jumia RDC');
    expect(historyTitle(item('CARD_REFUND', name: 'Jumia RDC')), 'Carte · paiement annulé par Jumia RDC');
    expect(historyTitle(item('CARD_FEE')), 'Frais de carte');
    expect(historyIcon(item('CARD_PAYMENT')), Icons.credit_card_rounded);
  });

  test('numéro de carte groupé par 4 et date au format MM/AA', () {
    final d = CardDetails.fromJson({'pan': FakeCards.pan, 'cvv': '123', 'expiryMonth': 3, 'expiryYear': 2029});
    expect(d.groupedPan, '4000 0012 3456 7899');
    expect(d.expiry, '03/29');
  });
}
