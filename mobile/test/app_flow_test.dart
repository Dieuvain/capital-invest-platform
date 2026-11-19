import 'dart:convert';
import 'dart:typed_data';

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/api/models.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:capital_invest/screens/notifications_screen.dart';
import 'package:capital_invest/screens/complaint_screens.dart';
import 'package:capital_invest/screens/history_screens.dart';
import 'package:capital_invest/screens/kyc_screen.dart';
import 'package:capital_invest/screens/money_screens.dart';
import 'package:capital_invest/screens/scheduled_screens.dart';
import 'package:capital_invest/ui/chrome.dart';
import 'package:capital_invest/ui/format.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'fake_server.dart';

class FakePhotos implements PhotoSource {
  @override
  Future<Photo?> take({required bool selfie}) async =>
      Photo(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3]), png: false);
}

void main() {
  late FakeServer server;
  late AppState app;

  setUp(() {
    server = FakeServer();
    app = AppState(
      ApiClient(baseUrl: 'http://test', httpClient: server.client),
      deviceId: 'test',
    );
  });

  Future<void> start(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(CapitalInvestApp(app: app, photos: FakePhotos()));
  }

  Future<void> pay(WidgetTester tester, String action) async {
    await tester.tap(find.byKey(ValueKey('quick-$action')));
    await tester.pumpAndSettle();
  }

  Future<void> login(WidgetTester tester, {String pin = '2580'}) async {
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), pin);
    await tester.tap(find.text('Se connecter'));
    await tester.pumpAndSettle();
  }

  testWidgets('connexion puis accueil avec solde, actions rapides et promotions', (tester) async {
    await start(tester);
    await login(tester);
    expect(find.text('Bonjour Amani !'), findsOneWidget);
    expect(tester.widget<Amount>(find.byKey(const ValueKey('balance'))).formatted, '50,00\u00A0\$');
    for (final a in ['Envoyer', 'Recharger', 'Retirer', 'Payer']) {
      expect(find.byKey(ValueKey('quick-$a')), findsOneWidget);
    }
    expect(find.text('Recharge M-Pesa'), findsOneWidget);
    expect(find.text('Passez au niveau 2'), findsOneWidget);
  });

  testWidgets('les promotions défilent seules et ouvrent la bonne action', (tester) async {
    await start(tester);
    await login(tester);
    final pages = find.byKey(const ValueKey('promos'));
    await tester.ensureVisible(pages);
    expect(tester.widget<PageView>(pages).controller!.page, 0);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    expect(tester.widget<PageView>(pages).controller!.page, 1);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    expect(tester.widget<PageView>(pages).controller!.page, 0); // retour au début
    await tester.tap(find.text('Passez au niveau 2'));
    await tester.pumpAndSettle();
    expect(find.byType(KycScreen), findsOneWidget);
  });

  testWidgets('une promotion en visuel : l\'image du graphiste, lue par son titre, mène à son action', (tester) async {
    server.promotions = [
      {'id': 'p-v', 'title': 'Recharge offerte', 'action': 'TOPUP', 'style': 'OCHRE', 'imageUrl': '/v1/promotions/p-v/image?v=1'},
      {'id': 'p-x', 'title': 'Visuel cassé', 'body': 'Carte de secours.', 'style': 'INK', 'imageUrl': '/v1/promotions/p-x/image?v=1'},
    ];
    server.images['p-v'] = FakeServer.png;
    await start(tester);
    await login(tester);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('promo-image-p-v')), findsOneWidget);
    expect(find.bySemanticsLabel('Recharge offerte'), findsOneWidget);
    final calls = server.requests.where((r) => r.url.path == '/v1/promotions/p-v/image').toList();
    expect(calls.single.headers['Authorization'], 'Bearer ${FakeServer.token}');

    // Visuel introuvable : la carte de secours reste, sans erreur.
    final pages = find.byKey(const ValueKey('promos'));
    tester.widget<PageView>(pages).controller!.jumpToPage(1);
    await tester.pumpAndSettle();
    expect(find.text('Carte de secours.'), findsOneWidget);
    expect(find.byKey(const ValueKey('promo-image-p-x')), findsNothing);

    tester.widget<PageView>(pages).controller!.jumpToPage(0);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const ValueKey('promo-image-p-v')));
    await tester.tap(find.byKey(const ValueKey('promo-image-p-v')));
    await tester.pumpAndSettle();
    expect(find.byType(TopupScreen), findsOneWidget);
  });

  testWidgets('historique : filtres, page suivante, puis reçu partagé', (tester) async {
    server.history = [
      FakeServer.historyItem('w1', 'WITHDRAWAL', -1015, fee: 15, operator: 'AIRTEL', msisdn: '+243970000001', status: 'PENDING'),
      FakeServer.historyItem('s1', 'TRANSFER_SENT', -2020, fee: 20, name: 'Béni Mukendi', phone: '+243 97• ••• 402', note: 'Loyer'),
      for (var i = 0; i < 22; i++) FakeServer.historyItem('t$i', 'TOPUP', 1000, operator: 'MPESA'),
    ];
    final shared = <String>[];
    app.share = (text, subject) async => shared.add('$subject\n$text');
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Historique'));
    await tester.pumpAndSettle();

    expect(find.text('Retrait vers Airtel Money'), findsOneWidget);
    expect(find.textContaining('En cours'), findsOneWidget);
    expect(find.text('-20,20\u00A0\$'), findsOneWidget);
    await tester.ensureVisible(find.text('Voir plus'));
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -300)); // hors de la barre du bas
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voir plus'));
    await tester.pumpAndSettle();
    // 20 opérations par page : la suite est demandée avec le curseur de la première.
    final pages = server.requests.where((r) => r.url.path == '/v1/me/history' && r.url.queryParameters['limit'] == '20');
    expect(pages.map((r) => r.url.queryParameters['cursor']).toList(), [null, '20']);
    expect(find.text('Voir plus'), findsNothing);

    await tester.ensureVisible(find.byKey(const ValueKey('filter-TRANSFER')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('filter-TRANSFER')));
    await tester.pumpAndSettle();
    expect(find.text('Recharge M-Pesa'), findsNothing);
    await tester.tap(find.text('Envoi à Béni Mukendi'));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('receipt-amount')), findsOneWidget);
    expect(find.text('Total débité'), findsOneWidget);
    expect(find.text('CI-20261118-0000S1'), findsOneWidget);
    await tester.ensureVisible(find.text('Partager le reçu'));
    await tester.tap(find.text('Partager le reçu'));
    expect(shared.single, contains('Reçu Capital Invest CI-20261118-0000S1'));
    expect(shared.single, contains('Envoi à Béni Mukendi'));
    expect(shared.single, contains('Frais : 0,20\u00A0\$'));
    expect(shared.single, contains('Total débité : 20,20\u00A0\$'));
    expect(shared.single, contains('Message : Loyer'));
  });

  testWidgets('un reçu introuvable affiche un message clair', (tester) async {
    await start(tester);
    await login(tester);
    final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
    nav.push(MaterialPageRoute<void>(builder: (_) => ReceiptScreen(app: app, id: 'inconnu')));
    await tester.pumpAndSettle();
    expect(find.textContaining('introuvable'), findsOneWidget);
  });

  testWidgets('notifications : pastille sur la cloche, lecture, puis reçu de l\'opération', (tester) async {
    server.history = [FakeServer.historyItem('r1', 'TRANSFER_RECEIVED', 4500, name: 'Patrick Ilunga', phone: '+243 99• ••• 870')];
    server.notifications = [
      {
        'id': 'n2', 'kind': 'TRANSFER_RECEIVED', 'title': 'Argent reçu', 'transactionId': 'r1', 'read': false,
        'body': 'Patrick Ilunga vous a envoyé 45,00 \$. Nouveau solde : 95,00 \$.', 'createdAt': '2026-11-19T08:00:00Z',
      },
      {
        'id': 'n1', 'kind': 'PIN_CHANGED', 'title': 'PIN changé', 'read': true,
        'body': 'Votre PIN vient d\'être changé.', 'createdAt': '2026-11-18T08:00:00Z',
      },
    ];
    await start(tester);
    await login(tester);
    expect(find.byTooltip('Notifications, 1 non lues'), findsOneWidget);

    await tester.tap(find.byTooltip('Notifications, 1 non lues'));
    await tester.pumpAndSettle();
    expect(find.text('Argent reçu'), findsOneWidget);
    expect(find.byKey(const ValueKey('unread-n2')), findsOneWidget);
    expect(find.byKey(const ValueKey('unread-n1')), findsNothing);
    expect(server.requests.where((r) => r.url.path == '/v1/me/notifications/read'), hasLength(1));

    await tester.tap(find.text('Argent reçu'));
    await tester.pumpAndSettle();
    expect(find.byType(ReceiptScreen), findsOneWidget);
    expect(find.text('Patrick Ilunga'), findsOneWidget);

    // Retour à l'accueil : la pastille s'est éteinte.
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();
    expect(find.byTooltip('Notifications'), findsOneWidget);
  });

  testWidgets('sans promotions, le bandeau disparaît sans erreur', (tester) async {
    server.promotions = [];
    await start(tester);
    await login(tester);
    expect(find.byKey(const ValueKey('promos')), findsNothing);
    expect(find.byKey(const ValueKey('balance')), findsOneWidget);
  });

  testWidgets('un mauvais PIN affiche un message clair', (tester) async {
    await start(tester);
    await login(tester, pin: '9999');
    expect(find.text('Numéro ou PIN incorrect.'), findsOneWidget);
    expect(find.text('Se connecter'), findsOneWidget);
  });

  testWidgets('envoi : nom du destinataire et frais montrés avant la confirmation par PIN', (tester) async {
    server.recentRecipients = [
      {'phone': '+243970000002', 'displayName': 'Josué K.'},
      {'phone': '+243970000402', 'displayName': 'Béni M.'},
    ];
    await start(tester);
    await login(tester);
    await pay(tester, 'Envoyer');
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du destinataire')), '970000999');
    await tester.enterText(find.byKey(const ValueKey('amount')), '12,50');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Aucun client Capital Invest avec ce numéro.'), findsOneWidget);
    expect(server.requests.where((r) => r.url.path == '/v1/transfers/fee'), isEmpty);

    await tester.tap(find.byKey(const ValueKey('recent-+243970000002')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();

    expect(find.text('Vérifier l\'envoi'), findsOneWidget);
    expect(find.descendant(of: find.byKey(const ValueKey('recipient-name')), matching: find.text('Josué K.')), findsOneWidget);
    expect(find.text('0,13 \$'), findsOneWidget);
    expect(find.text('12,63 \$'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Envoyer 12,50 \$'));
    await tester.pumpAndSettle();
    expect(find.text('12,50 \$ envoyés'), findsOneWidget);
    expect(server.balance, 5000 - 1263);
    expect(server.requests.last.headers['Idempotency-Key'], isNotNull);
  });

  testWidgets('demande reçue : bandeau sur l\'accueil, frais montrés, avertissement, PIN, puis payée', (tester) async {
    server.moneyRequests = [
      // Plus récentes d'abord : celle de Grâce attend depuis plus longtemps.
      FakeServer.moneyRequest('rq-b', incoming: true, name: 'Patrick Ilunga', phone: '+243990000870', amount: 500),
      FakeServer.moneyRequest('rq-a', incoming: true, name: 'Grâce Mbuyi', phone: '+243810000214', amount: 2000, note: 'Frais scolaires'),
    ];
    await start(tester);
    await login(tester);
    expect(find.text('Grâce vous demande ${formatUsd(2000)}'), findsOneWidget);
    expect(find.text('et 1 autre demande · Voir'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('requests-banner')));
    await tester.pumpAndSettle();
    expect(find.text('À payer'), findsOneWidget);
    await tester.tap(find.text('Grâce Mbuyi vous demande'));
    await tester.pumpAndSettle();
    expect(find.text('Payer la demande'), findsOneWidget);
    expect(find.text('Frais scolaires'), findsOneWidget);
    expect(find.text(formatUsd(20)), findsOneWidget);
    expect(find.text(formatUsd(2020)), findsOneWidget);
    expect(find.textContaining('Ne payez que si vous connaissez'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Payer ${formatUsd(2000)}'));
    await tester.pumpAndSettle();
    expect(find.text('${formatUsd(2000)} envoyés'), findsOneWidget);
    expect(server.balance, 5000 - 2020);
    await tester.tap(find.text('Terminé'));
    await tester.pumpAndSettle();
    // Reste la demande de Patrick, que le client refuse.
    await tester.tap(find.text('Patrick Ilunga vous demande'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Refuser la demande'));
    await tester.pumpAndSettle();
    expect(find.text('À payer'), findsNothing);
    expect(server.moneyRequests.map((r) => r['status']), ['DECLINED', 'PAID']);
  });

  testWidgets('mon QR pour recevoir : montant facultatif, partage, et scanné il ouvre l\'envoi pré-rempli', (tester) async {
    final shared = <String>[];
    app.share = (text, subject) async => shared.add(text);
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mon QR pour recevoir'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('receive-qr')), findsOneWidget);
    expect(find.text('+243 970 000 001'), findsOneWidget);
    expect(find.byKey(const ValueKey('receive-amount')), findsNothing);

    await tester.enterText(find.byKey(const ValueKey('receive-amount-field')), 'abc');
    await tester.pump();
    expect(find.text('Entrez un montant, par exemple 25 ou 12,50.'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('receive-amount-field')), '12,5');
    await tester.pump();
    expect(find.text('12,50\u00a0\$'), findsOneWidget);
    await tester.tap(find.text('Partager mon numéro'));
    expect(shared.single, contains('+243 970 000 001'));
    expect(shared.single, contains('12,50'));
    Navigator.of(tester.element(find.text('Recevoir'))).pop();
    await tester.pumpAndSettle();

    // Un proche scanne ce QR avec « Payer » : l'envoi s'ouvre avec le numéro et le montant.
    expect(ReceiveQr('+243970000002', 1250).payload, 'capitalinvest://send?p=243970000002&a=1250');
    app.scanQr = (_) async => ReceiveQr('+243970000002', 1250).payload;
    await pay(tester, 'Payer');
    expect(find.text('Envoyer'), findsWidgets);
    expect(find.text('970000002'), findsOneWidget);
    expect(find.text('12,50'), findsOneWidget);
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.descendant(of: find.byKey(const ValueKey('recipient-name')), matching: find.text('Josué K.')), findsOneWidget);

    // Un QR mal formé n'est ni un envoi ni un paiement.
    expect(ReceiveQr.parse('capitalinvest://send?p=12345'), isNull);
    expect(ReceiveQr.parse('https://example.com'), isNull);
    expect(ReceiveQr.parse('capitalinvest://send?p=243970000002')!.amountMinor, isNull);
  });

  testWidgets('demander de l\'argent depuis le menu, puis annuler la demande', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Demander de l\'argent'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('new-request')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du client')), '0970 000 001');
    await tester.enterText(find.byKey(const ValueKey('amount')), '15');
    await tester.tap(find.text('Envoyer la demande'));
    await tester.pumpAndSettle();
    expect(find.text('Vous ne pouvez pas vous envoyer de l\'argent.'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du client')), '970000402');
    await tester.enterText(find.byKey(const ValueKey('note')), 'Part du taxi');
    await tester.tap(find.text('Envoyer la demande'));
    await tester.pumpAndSettle();
    expect(find.text('${formatUsd(1500)} demandés'), findsOneWidget);
    final sent = server.requests.where((r) => r.url.path == '/v1/money-requests' && r.method == 'POST').toList();
    expect(sent, hasLength(2));
    expect(sent[0].headers['Idempotency-Key'], isNot(sent[1].headers['Idempotency-Key']));

    await tester.tap(find.text('Terminé'));
    await tester.pumpAndSettle();
    expect(find.text('Demandé à Béni Mukendi'), findsOneWidget);
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Annulée'), findsOneWidget);
  });

  testWidgets('paiement marchand : QR scanné, commerce vérifié, sans frais, PIN, puis preuve pour le caissier', (tester) async {
    app.scanQr = (_) async => 'capitalinvest://pay?m=K7P2QX';
    await start(tester);
    await login(tester);
    await pay(tester, 'Payer');

    // L'appareil photo s'ouvre tout seul ; le commerce est affiché avant tout montant.
    expect(find.byKey(const ValueKey('merchant-name')), findsOneWidget);
    expect(find.text('Alimentation Mama Nzuzi'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('amount')), '12,50');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();

    expect(find.text('Vérifier le paiement'), findsOneWidget);
    expect(find.text('Aucun'), findsOneWidget);
    expect(find.text(formatUsd(1250)), findsNWidgets(2));
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Payer ${formatUsd(1250)}'));
    await tester.pumpAndSettle();

    expect(find.text('Paiement effectué'), findsOneWidget);
    expect(find.text('payés à Alimentation Mama Nzuzi'), findsOneWidget);
    expect(find.textContaining('Reçu CI-20261119-00MP01'), findsOneWidget);
    expect(server.balance, 5000 - 1250);
    final req = server.requests.lastWhere((r) => r.url.path == '/v1/merchant-payments');
    expect(req.headers['Idempotency-Key'], isNotNull);
    expect(req.body, contains('"merchantCode":"K7P2QX"'));

    // Le reçu de l'opération s'ouvre depuis l'écran de preuve.
    await tester.tap(find.text('Voir le reçu'));
    await tester.pumpAndSettle();
    expect(find.text('Marchand'), findsOneWidget);
    expect(find.text('Paiement à Alimentation Mama Nzuzi'), findsOneWidget);
  });

  testWidgets('paiement marchand : code tapé, QR étranger et marchand suspendu', (tester) async {
    var scanned = 'https://exemple.com/menu';
    app.scanQr = (_) async => scanned;
    await start(tester);
    await login(tester);
    await pay(tester, 'Payer');
    expect(find.text('Ce QR n\'est pas un QR de paiement Capital Invest.'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('merchant-code')), 'susp 22');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Ce marchand n\'accepte pas de paiement pour le moment.'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('merchant-code')), 'zzz 999');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Aucun marchand avec ce code. Vérifiez le code sous le QR.'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('merchant-code')), 'k7p 2qx');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Alimentation Mama Nzuzi'), findsOneWidget);
    scanned = '';
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

  for (final (outcome, title, text) in [
    ({'status': 'REVIEW'}, 'Recharge en vérification', 'Vous n\'avez rien à refaire'),
    ({'status': 'FAILED', 'failureCode': 'INSUFFICIENT_BALANCE'}, 'Recharge refusée', 'Solde M-Pesa insuffisant'),
    ({'status': 'FAILED', 'failureCode': 'QUELQUE_CHOSE'}, 'Recharge refusée', 'L\'opérateur a refusé le paiement'),
  ]) {
    testWidgets('recharge : $title (${outcome['failureCode'] ?? outcome['status']})', (tester) async {
      server.topupOutcome = outcome;
      await start(tester);
      await login(tester);
      await pay(tester, 'Recharger');
      await tester.enterText(find.byKey(const ValueKey('amount')), '25');
      await tester.tap(find.widgetWithText(FilledButton, 'Recharger'));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.text(title), findsOneWidget);
      expect(find.textContaining(text), findsOneWidget);
      expect(find.text('Terminé'), findsOneWidget);
    });
  }

  testWidgets('retrait : opérateur deviné, frais vérifiés, PIN, puis argent envoyé', (tester) async {
    await start(tester);
    await login(tester);
    await pay(tester, 'Retirer');
    // Le numéro +243 97… est un numéro Airtel : l'opérateur est déjà choisi.
    expect(tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, 'Airtel Money')).selected, isTrue);
    await tester.enterText(find.byKey(const ValueKey('amount')), '25');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Vérifier le retrait'), findsOneWidget);
    expect(find.text('0,38\u00A0\$'), findsOneWidget);
    expect(find.text('25,38\u00A0\$'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Retirer 25,00\u00A0\$'));
    await tester.pump();
    await tester.pump();
    expect(find.text('Retrait en cours'), findsOneWidget);
    expect(server.balance, 5000 - 2538);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(find.text('Argent envoyé'), findsOneWidget);
    expect(find.textContaining('Airtel Money'), findsOneWidget);
  });

  testWidgets('retrait refusé : le motif et le remboursement sont expliqués', (tester) async {
    server.withdrawalOutcome = {'status': 'FAILED', 'failureCode': 'RECIPIENT_NOT_FOUND'};
    await start(tester);
    await login(tester);
    await pay(tester, 'Retirer');
    await tester.enterText(find.byKey(const ValueKey('amount')), '10');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.textContaining('Retirer 10'));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(find.text('Retrait refusé'), findsOneWidget);
    expect(find.textContaining('pas de compte Airtel Money'), findsOneWidget);
    expect(find.textContaining('recrédité, frais compris'), findsOneWidget);
  });

  test('opérateur deviné d\'après le numéro', () {
    expect(operatorForPhone('+243970000001'), Operator.airtel);
    expect(operatorForPhone('+243810000001'), Operator.mpesa);
    expect(operatorForPhone('+243890000001'), Operator.orange);
    expect(operatorForPhone('+243900000001'), isNull);
  });

  testWidgets('sécurité : changer son PIN avec l\'ancien, puis déconnecter les autres téléphones', (tester) async {
    server.deviceSessions[2]['active'] = false;
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sécurité et PIN'));
    await tester.pumpAndSettle();
    expect(find.text('Samsung Galaxy A14'), findsOneWidget);
    expect(find.textContaining('Ce téléphone'), findsOneWidget);
    expect(find.text('Déconnecter les autres téléphones'), findsOneWidget);

    await tester.tap(find.text('Changer mon PIN'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN actuel')), '1111');
    await tester.enterText(find.byKey(const ValueKey('pin-Nouveau PIN')), '7391');
    await tester.enterText(find.byKey(const ValueKey('pin-Confirmez le nouveau PIN')), '7391');
    await tester.tap(find.widgetWithText(FilledButton, 'Changer mon PIN'));
    await tester.pumpAndSettle();
    expect(find.text('PIN actuel incorrect.'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('pin-PIN actuel')), '2580');
    await tester.tap(find.widgetWithText(FilledButton, 'Changer mon PIN'));
    await tester.pumpAndSettle();
    expect(server.pin, '7391');
    expect(find.text('PIN changé. Vos autres téléphones ont été déconnectés.'), findsOneWidget);
    // Plus aucun autre téléphone connecté : le bouton disparaît.
    expect(find.text('Déconnecter les autres téléphones'), findsNothing);
  });

  testWidgets('sécurité : déconnecter les autres téléphones après confirmation', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sécurité et PIN'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Déconnecter les autres téléphones'));
    await tester.tap(find.text('Déconnecter les autres téléphones'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Déconnecter'));
    await tester.pumpAndSettle();
    expect(find.text('1 téléphone déconnecté.'), findsOneWidget);
    expect(server.requests.where((r) => r.url.path == '/v1/me/security/sessions/sign-out-others'), hasLength(1));
  });

  testWidgets('plafond par jour : baisser tout de suite, remonter après 24 h, annuler la hausse', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sécurité et PIN'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('daily-limit-entry')));
    await tester.pumpAndSettle();
    expect(find.text('600,00\u00A0\$ par jour'), findsOneWidget);
    expect(find.text('Le plafond de votre niveau 1.'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('preset-5000')));
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    expect(server.personalDailyLimit, 5000);
    expect(find.text('Plafond changé : 50,00\u00A0\$ par jour, dès maintenant.'), findsOneWidget);
    expect(find.text('50,00\u00A0\$ par jour'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('amount')), '200');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Hausse enregistrée'), findsOneWidget);
    expect(find.textContaining('Passera à 200,00\u00A0\$'), findsOneWidget);
    expect(find.text('50,00\u00A0\$ par jour'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('cancel-raise')));
    await tester.pumpAndSettle();
    expect(server.pendingDailyLimit, isNull);
    expect(find.text('Hausse annulée. Votre plafond reste à 50,00\u00A0\$ par jour.'), findsOneWidget);
  });

  testWidgets('fermer son compte : d\'abord vider le solde et l\'épargne, puis motif et PIN', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sécurité et PIN'));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -900));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('close-account')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('blocker-SAVINGS_NOT_EMPTY')), findsOneWidget);
    expect(find.textContaining('Retirez ou envoyez votre solde (50,00'), findsOneWidget);
    expect(find.byKey(const Key('close-confirm')), findsNothing);
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();

    server.closure = {'canClose': true, 'balanceMinor': 0, 'savingsMinor': 0, 'blockers': []};
    await tester.tap(find.byKey(const Key('close-account')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.byKey(const Key('close-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('Dites-nous pourquoi vous partez.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('reason-FEES')));
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.byKey(const Key('close-confirm')));
    await tester.pumpAndSettle();
    expect(server.closed, isTrue);
    expect(server.requests.lastWhere((r) => r.url.path == '/v1/me/closure').body, contains('"reason":"FEES"'));
    expect(find.text('Compte fermé'), findsOneWidget);
    expect(app.signedIn, isFalse);
    await tester.tap(find.text('Terminer'));
    await tester.pumpAndSettle();
    await login(tester);
    expect(find.textContaining('Ce compte est fermé'), findsOneWidget);
  });

  testWidgets('alerte de solde bas : choisir 20 \$, puis la désactiver', (tester) async {
    server.notifications = [
      {
        'id': 'n9', 'kind': 'LOW_BALANCE', 'title': 'Solde bas', 'transactionId': null, 'read': false,
        'body': 'Votre solde est de 19,20 \$, sous votre seuil d\'alerte de 20,00 \$.', 'createdAt': '2026-11-19T12:10:00Z',
      },
    ];
    await start(tester);
    await login(tester);
    final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
    nav.push(MaterialPageRoute<void>(builder: (_) => NotificationsScreen(app: app)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('low-balance')));
    await tester.pumpAndSettle();
    expect(tester.widget<ChoiceChip>(find.byKey(const ValueKey('threshold-off'))).selected, isTrue);
    await tester.tap(find.byKey(const ValueKey('threshold-2000')));
    await tester.pump();
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    expect(server.lowBalance, 2000);
    expect(find.text('C\'est noté : un SMS vous préviendra sous 20,00\u00A0\$.'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('threshold-off')));
    await tester.pump();
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    expect(server.lowBalance, isNull);
    expect(find.text('Alerte désactivée.'), findsOneWidget);

    // La notification « Solde bas » ouvre ce réglage.
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('notification-n9')));
    await tester.pumpAndSettle();
    expect(find.text('Alerte de solde bas'), findsWidgets);
  });

  testWidgets('téléphone volé : bloquer son compte depuis Sécurité, PIN redemandé, retour à l\'accueil', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sécurité et PIN'));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -600));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('block-account')));
    await tester.pumpAndSettle();
    expect(find.textContaining('présentez-vous en agence'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '1111');
    await tester.tap(find.byKey(const Key('block-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('Numéro ou PIN incorrect.'), findsOneWidget);
    expect(server.blocked, isFalse);

    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
    await tester.tap(find.byKey(const Key('block-confirm')));
    await tester.pumpAndSettle();
    expect(server.blocked, isTrue);
    expect(find.text('Compte bloqué'), findsOneWidget);
    expect(app.signedIn, isFalse);
    await tester.tap(find.text('Compris'));
    await tester.pumpAndSettle();
    expect(find.text('Se connecter'), findsOneWidget);
    // La connexion explique comment débloquer.
    await login(tester);
    expect(find.textContaining('Compte bloqué à votre demande'), findsOneWidget);
  });

  testWidgets('téléphone volé : bloquer depuis le téléphone d\'un proche, sans se connecter', (tester) async {
    await start(tester);
    await tester.tap(find.text('Téléphone perdu ou volé ?'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du compte à bloquer')), '970000001');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
    await tester.tap(find.byKey(const Key('block-confirm')));
    await tester.pumpAndSettle();
    expect(server.blocked, isTrue);
    final req = server.requests.lastWhere((r) => r.url.path == '/v1/auth/block');
    expect(req.body, contains('+243970000001'));
    expect(find.text('Compte bloqué'), findsOneWidget);
    // Le retour ramène à l'écran de connexion, pas au formulaire de blocage.
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();
    expect(find.text('Se connecter'), findsOneWidget);
  });

  testWidgets('mes dépenses : catégories du mois, comparaison au même jour du mois d\'avant, puis octobre', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Historique'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('spending')));
    await tester.pumpAndSettle();
    expect(find.text('Dépensé depuis le 1er'), findsOneWidget);
    expect(find.text('384,20\u00A0\$'), findsOneWidget);
    expect(find.text('72,20\u00A0\$ de plus que du 1er–19 oct. (312,00\u00A0\$)'), findsOneWidget);
    expect(find.text('Envois'), findsOneWidget);
    expect(find.text('3 opérations · avant : 180,00\u00A0\$'), findsOneWidget);
    expect(find.text('Frais'), findsOneWidget);
    final page = find.byWidgetPredicate((w) => w is Scrollable && w.axisDirection == AxisDirection.down).last;
    await tester.scrollUntilVisible(find.text('Grâce Mbuyi'), 200, scrollable: page);
    expect(find.text('2 envois · +243 81• ••• 214'), findsOneWidget);

    await tester.scrollUntilVisible(find.byKey(const ValueKey('month-2026-10')), -200, scrollable: page);
    await tester.tap(find.byKey(const ValueKey('month-2026-10')));
    await tester.pumpAndSettle();
    expect(find.text('Dépensé ce mois-là'), findsOneWidget);
    expect(find.text('91,20\u00A0\$'), findsOneWidget);
    expect(find.textContaining('de plus que'), findsNothing);
    expect(find.text('Grâce Mbuyi'), findsNothing);
    expect(server.requests.last.url.query, 'month=2026-10');
  });

  testWidgets('relevé de compte : choisir le mois, lire le résumé, partager le PDF', (tester) async {
    final shared = <(int, String, String)>[];
    app.shareFile = (bytes, name, type) async => shared.add((bytes.length, name, type));
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Historique'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('statements')));
    await tester.pumpAndSettle();
    expect(find.text('Novembre 2026'), findsOneWidget);
    expect(find.text('Mois en cours, jusqu\'à aujourd\'hui'), findsOneWidget);
    await tester.tap(find.text('Octobre 2026'));
    await tester.pumpAndSettle();
    expect(find.text('Du 1er octobre au 31 octobre'), findsOneWidget);
    expect(find.text('Solde au 1er octobre'), findsOneWidget);
    expect(find.text('140,80\u00A0\$'), findsOneWidget);
    expect(find.text('5 opérations'), findsOneWidget);
    await tester.tap(find.text('Télécharger le relevé (PDF)'));
    await tester.pumpAndSettle();
    expect(shared, [(FakeServer.pdf.length, 'releve-capital-invest-2026-10.pdf', 'application/pdf')]);
  });

  testWidgets('envoi programmé : loyer chaque mois après un envoi, puis pause et suppression', (tester) async {
    server.scheduled = [
      FakeServer.scheduledTransfer('st-0', 'Maman Chantal', 2000, 'WEEKLY', 5, lastResult: 'FAILED', lastFailure: 'Solde insuffisant'),
    ];
    await start(tester);
    await login(tester);
    final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
    nav.push(MaterialPageRoute<void>(builder: (_) => TransferScreen(app: app)));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro du destinataire')), '0970000402');
    await tester.enterText(find.byKey(const ValueKey('amount')), '30');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Envoyer 30,00\u00A0\$'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Répéter cet envoi chaque mois'));
    await tester.pumpAndSettle();

    // Prérempli avec le même destinataire et le même montant.
    await tester.ensureVisible(find.byKey(const ValueKey('month-day')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('month-day')));
    await tester.pumpAndSettle();
    // Le menu s'ouvre sur le jour d'aujourd'hui : on remonte jusqu'au 5.
    await tester.scrollUntilVisible(find.text('5'), -60, scrollable: find.byType(Scrollable).last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('5').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('scheduled-note')), 'Loyer');
    await tester.ensureVisible(find.text('Continuer'));
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Chaque mois, le 5'), findsOneWidget);
    expect(find.text('30,30\u00A0\$'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.tap(find.text('Programmer'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Premier envoi le 5 déc. 2026'), findsOneWidget);
    final sent = server.requests.lastWhere((r) => r.url.path == '/v1/me/scheduled-transfers' && r.method == 'POST');
    expect(jsonDecode(sent.body), {
      'toPhone': '+243970000402', 'amountMinor': 3000, 'frequency': 'MONTHLY', 'day': 5, 'pin': '2580', 'note': 'Loyer',
    });
    await tester.tap(find.text('Terminé'));
    await tester.pumpAndSettle();

    nav.push(MaterialPageRoute<void>(builder: (_) => ScheduledTransfersScreen(app: app)));
    await tester.pumpAndSettle();
    expect(find.text('Dernier envoi non effectué : Solde insuffisant.'), findsOneWidget);
    expect(find.text('Chaque vendredi'), findsOneWidget);
    expect(find.text('Chaque mois, le 5 · Loyer'), findsOneWidget);
    final rent = find.byKey(const ValueKey('scheduled-st-2'));
    await tester.tap(find.descendant(of: rent, matching: find.text('Mettre en pause')));
    await tester.pumpAndSettle();
    expect(find.descendant(of: rent, matching: find.text('En pause')), findsOneWidget);
    await tester.tap(find.descendant(of: find.byKey(const ValueKey('scheduled-st-0')), matching: find.text('Supprimer')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Supprimer'));
    await tester.pumpAndSettle();
    expect(find.text('Maman Chantal'), findsNothing);
    expect(server.scheduled.map((s) => s['id']), ['st-2']);
  });

  testWidgets('réclamation depuis le reçu : motif, message, numéro et date de réponse, puis suivi', (tester) async {
    server.history = [FakeServer.historyItem('s1', 'TRANSFER_SENT', -2020, fee: 20, name: 'Béni Mukendi', phone: '+243 97• ••• 402')];
    server.complaints = [
      {
        'id': 'cp-0', 'reference': 'REC-20261112-A1B2C3', 'category': 'OTHER', 'categoryLabel': 'Autre problème',
        'message': 'Je ne reçois plus les SMS', 'status': 'ANSWERED', 'operation': null, 'dueAt': '2026-11-19T10:00:00Z',
        'answer': 'Votre numéro était bloqué chez l\'opérateur, c\'est réglé.', 'outcome': 'FOUNDED',
        'answeredAt': '2026-11-13T09:00:00Z', 'createdAt': '2026-11-12T10:00:00Z',
      },
    ];
    await start(tester);
    await login(tester);
    final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
    nav.push(MaterialPageRoute<void>(builder: (_) => ReceiptScreen(app: app, id: 's1')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Signaler un problème'));
    await tester.tap(find.text('Signaler un problème'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Envoyer la réclamation'));
    await tester.pumpAndSettle();
    expect(find.text('Choisissez ce qui ne va pas.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('complaint-category-NOT_ME')));
    await tester.pumpAndSettle();
    expect(find.text('Changer mon PIN'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('complaint-category-NOT_RECEIVED')));
    await tester.pumpAndSettle();
    expect(find.text('Changer mon PIN'), findsNothing);
    await tester.enterText(find.byKey(const ValueKey('complaint-message')), 'Béni dit ne rien avoir reçu');
    await tester.ensureVisible(find.text('Envoyer la réclamation'));
    await tester.tap(find.text('Envoyer la réclamation'));
    await tester.pumpAndSettle();
    expect(find.text('REC-20261119-3F9A21'), findsOneWidget);
    expect(find.textContaining('au plus tard le 26 nov. 2026'), findsOneWidget);
    final sent = server.requests.lastWhere((r) => r.url.path == '/v1/me/complaints' && r.method == 'POST');
    expect(jsonDecode(sent.body), {'transactionId': 's1', 'category': 'NOT_RECEIVED', 'message': 'Béni dit ne rien avoir reçu'});

    nav.push(MaterialPageRoute<void>(builder: (_) => ComplaintsScreen(app: app)));
    await tester.pumpAndSettle();
    expect(find.text('En attente'), findsOneWidget);
    expect(find.textContaining('Envoi à Béni Mukendi'), findsOneWidget);
    expect(find.text('Réglée en votre faveur'), findsOneWidget);
    expect(find.text('Votre numéro était bloqué chez l\'opérateur, c\'est réglé.'), findsOneWidget);
  });

  testWidgets('facture SNEL : compteur vérifié, montant, PIN, puis code du compteur', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Payer une facture'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('biller-SNEL_PREPAID')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('bill-reference')), '01234560000');
    await tester.tap(find.text('Vérifier'));
    await tester.pumpAndSettle();
    expect(find.textContaining('inconnu du facturier'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('bill-reference')), '01234567891');
    await tester.tap(find.text('Vérifier'));
    await tester.pumpAndSettle();
    expect(find.text('KABEYA AMANI'), findsOneWidget);

    await tester.tap(find.text('20 \$'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.ensureVisible(find.text('Payer 20,00\u00A0\$'));
    await tester.tap(find.text('Payer 20,00\u00A0\$'));
    await tester.pumpAndSettle();
    expect(find.text('Facture payée'), findsOneWidget);
    expect(find.text('4821 0937 5512 8064 3379'), findsOneWidget);
    expect(server.balance, 3000);
    final sent = server.requests.lastWhere((r) => r.url.path == '/v1/bills');
    expect(jsonDecode(sent.body), {'biller': 'SNEL_PREPAID', 'reference': '01234567891', 'amountMinor': 2000, 'pin': '2580'});

    await tester.tap(find.text('Terminé'));
    await tester.pumpAndSettle();
    expect(find.text('Bonjour Amani !'), findsOneWidget);
  });

  testWidgets('facture REGIDESO déjà payée : le montant dû est proposé', (tester) async {
    server.recentBills = [
      {'biller': 'REGIDESO', 'billerName': 'REGIDESO', 'reference': '4471203', 'holderName': 'KABEYA AMANI'},
    ];
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Payer une facture'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('recent-bill-4471203')));
    await tester.pumpAndSettle();
    expect(find.text('Montant dû : 18,50\u00A0\$'), findsOneWidget);
    expect(find.text('Payer 18,50\u00A0\$'), findsOneWidget);
  });

  testWidgets('crédit téléphone : numéro d\'un proche, réseau deviné, PIN, puis refus remboursé', (tester) async {
    await start(tester);
    await login(tester);
    await tester.tap(find.byTooltip('Menu'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Acheter du crédit téléphone'));
    await tester.pumpAndSettle();
    // Mon numéro par défaut, réseau Airtel deviné (+243 97…).
    expect(tester.widget<ChoiceChip>(find.byKey(const ValueKey('network-AIRTEL'))).selected, isTrue);

    await tester.tap(find.text('Vodacom 812345678'));
    await tester.pumpAndSettle();
    expect(tester.widget<ChoiceChip>(find.byKey(const ValueKey('network-VODACOM'))).selected, isTrue);
    await tester.tap(find.text('5 \$'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.ensureVisible(find.text('Acheter 5,00\u00A0\$'));
    await tester.tap(find.text('Acheter 5,00\u00A0\$'));
    await tester.pumpAndSettle();
    expect(find.text('Crédit envoyé'), findsOneWidget);
    expect(server.balance, 4500);
    final sent = server.requests.lastWhere((r) => r.url.path == '/v1/airtime');
    expect(jsonDecode(sent.body), {'phone': '+243812345678', 'network': 'VODACOM', 'amountMinor': 500, 'pin': '2580'});

    await tester.tap(find.text('Acheter encore'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro à recharger')), '990000000');
    await tester.enterText(find.byKey(const ValueKey('amount')), '2');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
    await tester.pump();
    await tester.ensureVisible(find.text('Acheter 2,00\u00A0\$'));
    await tester.tap(find.text('Acheter 2,00\u00A0\$'));
    await tester.pumpAndSettle();
    expect(find.text('Crédit non envoyé'), findsOneWidget);
    expect(find.textContaining('Ce numéro n\'est pas actif chez Airtel. 2,00\u00A0\$ sont revenus'), findsOneWidget);
    expect(server.balance, 4500);
  });

  testWidgets('épargne : mettre de côté, fixer un objectif, reprendre', (tester) async {
    await start(tester);
    await login(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('home-savings')));
    await tester.tap(find.byKey(const ValueKey('home-savings')));
    await tester.pumpAndSettle();
    expect(find.text('Fixer un objectif'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('savings-deposit')));
    await tester.pumpAndSettle();
    expect(find.text('Disponible sur votre solde : 50,00\u00A0\$'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('amount')), '80');
    await tester.tap(find.widgetWithText(FilledButton, 'Mettre de côté'));
    await tester.pumpAndSettle();
    expect(find.text('Vous avez 50,00\u00A0\$ sur votre solde.'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('amount')), '30');
    await tester.tap(find.widgetWithText(FilledButton, 'Mettre de côté'));
    await tester.pumpAndSettle();
    expect(server.savings, 3000);
    expect(server.balance, 2000);
    expect(find.text('Ce mois-ci : +30,00\u00A0\$'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('savings-goal')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('goal-label')), 'Rentrée scolaire');
    await tester.enterText(find.byKey(const ValueKey('goal-target')), '120');
    await tester.tap(find.text('Enregistrer'));
    await tester.pumpAndSettle();
    expect(find.text('Rentrée scolaire'), findsOneWidget);
    expect(find.text('25 %'), findsOneWidget);
    expect(find.text('Encore 90,00\u00A0\$ pour atteindre 120,00\u00A0\$'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('savings-withdraw')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Tout'));
    await tester.tap(find.widgetWithText(FilledButton, 'Reprendre'));
    await tester.pumpAndSettle();
    expect(server.savings, 0);
    expect(server.balance, 5000);
    expect(find.text('Repris vers le solde'), findsOneWidget);
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
    await tester.ensureVisible(find.text('Passez au niveau 2'));
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
