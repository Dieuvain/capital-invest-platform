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
      ..recentBills = [
        {'biller': 'SNEL_PREPAID', 'billerName': 'SNEL prépayé', 'reference': '01234567891', 'holderName': 'KABEYA AMANI'},
      ]
      ..savings = 18500
      ..savingsGoal = {'label': 'Rentrée scolaire', 'targetMinor': 30000}
      ..savingsMoves = [
        {'id': 'sv-3', 'kind': 'DEPOSIT', 'amountMinor': 5000, 'createdAt': '2026-11-18T17:05:00Z'},
        {'id': 'sv-2', 'kind': 'WITHDRAWAL', 'amountMinor': 1500, 'createdAt': '2026-11-09T11:20:00Z'},
        {'id': 'sv-1', 'kind': 'DEPOSIT', 'amountMinor': 2000, 'createdAt': '2026-11-02T08:00:00Z'},
        {'id': 'sv-0', 'kind': 'DEPOSIT', 'amountMinor': 13000, 'createdAt': '2026-10-28T19:45:00Z'},
      ]
      ..notifications = [
        {
          'id': 'n3', 'kind': 'TRANSFER_RECEIVED', 'title': 'Argent reçu', 'transactionId': 'd4e5f6', 'read': false,
          'body': 'Patrick Ilunga vous a envoyé 45,00 \$. Nouveau solde : 1 237,50 \$.', 'createdAt': '2026-11-18T12:10:00Z',
        },
        {
          'id': 'n2', 'kind': 'TOPUP', 'title': 'Recharge reçue', 'transactionId': 'a7b8c9', 'read': true,
          'body': '1 000,00 \$ ajoutés depuis M-Pesa. Nouveau solde : 1 432,50 \$.', 'createdAt': '2026-11-17T09:05:00Z',
        },
        {
          'id': 'n1', 'kind': 'WITHDRAWAL_PAID', 'title': 'Retrait versé', 'transactionId': 'd1e2f3', 'read': true,
          'body': '400,00 \$ sont arrivés sur votre numéro Airtel Money +243970000001.', 'createdAt': '2026-11-16T18:31:00Z',
        },
      ]
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
    )..scanQr = (_) async => 'capitalinvest://pay?m=K7P2QX';
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

      await tester.tap(find.byTooltip('Notifications, 1 non lues'));
      await tester.pumpAndSettle();
      await shot('2f-notifications$suffix');
      await tester.tap(find.byTooltip('Retour'));
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

      await tester.tap(find.byKey(const ValueKey('quick-Payer')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('amount')), '18,75');
      await tester.pump();
      await shot('6-paiement-marchand$suffix');
      await tester.tap(find.text('Continuer'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
      await tester.tap(find.textContaining('Payer 18,75'));
      await tester.pumpAndSettle();
      await shot('6b-paiement-preuve$suffix');
      await tester.tap(find.text('Terminé'));
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

      server.moneyRequests = [
        FakeServer.moneyRequest('rq-3', incoming: false, name: 'Béni Mukendi', phone: '+243970000402', amount: 1500,
            note: 'Part du taxi', at: '2026-11-19T07:15:00Z'),
        FakeServer.moneyRequest('rq-2', incoming: true, name: 'Grâce Mbuyi', phone: '+243810000214', amount: 2000,
            note: 'Frais scolaires', at: '2026-11-18T18:40:00Z'),
        FakeServer.moneyRequest('rq-1', incoming: false, name: 'Patrick Ilunga', phone: '+243990000870', amount: 4500,
            note: 'Loyer de novembre', status: 'PAID', at: '2026-11-17T10:00:00Z'),
      ];
      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Demander de l\'argent'));
      await tester.pumpAndSettle();
      await shot('7-demandes$suffix');
      await tester.tap(find.text('Grâce Mbuyi vous demande'));
      await tester.pumpAndSettle();
      await shot('7b-demande-a-payer$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('new-request')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('phone-Numéro du client')), '970 000 402');
      await tester.enterText(find.byKey(const ValueKey('amount')), '15');
      await tester.enterText(find.byKey(const ValueKey('note')), 'Part du taxi');
      await tester.pump();
      await shot('7c-demander$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Historique'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('statements')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Octobre 2026'));
      await tester.pumpAndSettle();
      await shot('9-releve$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Accueil'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('home-savings')));
      await tester.tap(find.byKey(const ValueKey('home-savings')));
      await tester.pumpAndSettle();
      await shot('10-epargne$suffix');
      await tester.tap(find.byKey(const ValueKey('savings-deposit')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('amount')), '20');
      await tester.pump();
      await shot('10b-mettre-de-cote$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();

      await tester.drag(find.byType(ListView).first, const Offset(0, 800));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Acheter du crédit téléphone'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Vodacom 812345678'));
      await tester.tap(find.text('5 \$'));
      await tester.pumpAndSettle();
      await shot('11-credit$suffix');
      await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
      await tester.ensureVisible(find.text('Acheter 5,00\u00A0\$'));
      await tester.tap(find.text('Acheter 5,00\u00A0\$'));
      await tester.pumpAndSettle();
      await shot('11b-credit-envoye$suffix');
      await tester.tap(find.text('Terminé'));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Payer une facture'));
      await tester.pumpAndSettle();
      await shot('12-factures$suffix');
      await tester.tap(find.byKey(const ValueKey('recent-bill-01234567891')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('20 \$'));
      await tester.pumpAndSettle();
      await shot('12b-facture-snel$suffix');
      await tester.enterText(find.byKey(const ValueKey('pin-PIN pour confirmer')), '2580');
      await tester.ensureVisible(find.text('Payer 20,00\u00A0\$'));
      await tester.tap(find.text('Payer 20,00\u00A0\$'));
      await tester.pumpAndSettle();
      await shot('12c-code-compteur$suffix');
      await tester.tap(find.text('Terminé'));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Profil'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Sécurité et PIN'));
      await tester.pumpAndSettle();
      await shot('8-securite$suffix');
      await tester.tap(find.text('Changer mon PIN'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('pin-PIN actuel')), '2580');
      await tester.enterText(find.byKey(const ValueKey('pin-Nouveau PIN')), '7391');
      await tester.pump();
      await shot('8b-changer-pin$suffix');
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Retour'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Accueil'));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Passer au niveau 2'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('idNumber')), 'CE 1234 5678');
      await tester.pump();
      await shot('5-niveau-2$suffix');
    });
  }
}
