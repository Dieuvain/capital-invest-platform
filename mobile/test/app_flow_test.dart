import 'dart:typed_data';

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/api/models.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:capital_invest/screens/kyc_screen.dart';
import 'package:capital_invest/ui/chrome.dart';
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
    for (final a in ['Envoyer', 'Recharger', 'Retirer', 'Plus']) {
      expect(find.byKey(ValueKey('quick-$a')), findsOneWidget);
    }
    expect(find.text('Argent reçu'), findsOneWidget);
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
