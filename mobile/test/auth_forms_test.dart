import 'dart:convert';

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/main.dart';
import 'package:capital_invest/ui/messages.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'fake_server.dart';

/// Formulaires d'avant connexion : ouvrir un compte et PIN oublié.
void main() {
  late FakeServer server;
  late AppState app;

  setUp(() {
    server = FakeServer();
    app = AppState(ApiClient(baseUrl: 'http://test', httpClient: server.client), deviceId: 'test');
  });

  Future<void> start(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(CapitalInvestApp(app: app));
  }

  Future<void> openFrom(WidgetTester tester, String link) async {
    await start(tester);
    await tester.ensureVisible(find.text(link));
    await tester.pumpAndSettle();
    await tester.tap(find.text(link));
    await tester.pumpAndSettle();
  }

  Future<void> submit(WidgetTester tester, String label) async {
    await tester.ensureVisible(find.text(label));
    await tester.tap(find.text(label));
    await tester.pumpAndSettle();
  }

  testWidgets('ouverture de compte : numéro, code SMS, nom et PIN vérifiés avant envoi', (tester) async {
    await openFrom(tester, 'Ouvrir un compte');
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '12');
    await submit(tester, 'Recevoir le code');
    expect(find.text('Numéro congolais attendu (9 chiffres après +243).'), findsOneWidget);
    expect(server.requests.where((r) => r.url.path == '/v1/auth/otp'), isEmpty);

    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await submit(tester, 'Recevoir le code');
    expect(find.textContaining('Code envoyé au +243970000001'), findsOneWidget);

    Future<void> fill(String code, String name, String pin, String pin2) async {
      await tester.enterText(find.byKey(const ValueKey('code')), code);
      await tester.enterText(find.byKey(const ValueKey('name')), name);
      await tester.enterText(find.byKey(const ValueKey('pin-Choisissez un PIN')), pin);
      await tester.enterText(find.byKey(const ValueKey('pin-Confirmez le PIN')), pin2);
      await submit(tester, 'Créer mon compte');
    }

    await fill('123', 'Bora Ilunga', '4826', '4826');
    expect(find.text('Le code SMS fait 6 chiffres.'), findsOneWidget);
    await fill('123456', 'Bo', '4826', '4826');
    expect(find.text('Entrez votre nom complet.'), findsOneWidget);
    await fill('123456', 'Bora Ilunga', '1234', '1234');
    expect(find.text('PIN trop simple (suite de chiffres)'), findsOneWidget);
    await fill('123456', 'Bora Ilunga', '4826', '4827');
    expect(find.text('Les deux PIN sont différents.'), findsOneWidget);
    expect(server.requests.where((r) => r.url.path == '/v1/auth/register'), isEmpty);

    await fill('654321', 'Bora Ilunga', '4826', '4826');
    expect(find.text('Code invalide ou expiré.'), findsOneWidget);

    await fill('123456', '  Bora Ilunga ', '4826', '4826');
    final sent = jsonDecode(server.requests.lastWhere((r) => r.url.path == '/v1/auth/register').body);
    expect(sent['fullName'], 'Bora Ilunga');
    expect(sent['phone'], '+243970000001');
    expect(find.text('Bonjour Bora !'), findsOneWidget);
  });

  testWidgets('PIN oublié : le code SMS fait 6 chiffres et les deux PIN doivent correspondre', (tester) async {
    await openFrom(tester, 'PIN oublié ?');
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '97000');
    await submit(tester, 'Recevoir le code');
    expect(find.text('Numéro congolais attendu (9 chiffres après +243).'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await submit(tester, 'Recevoir le code');
    expect(find.text('Nouveau PIN'), findsWidgets);

    Future<void> fill(String code, String pin, String pin2) async {
      await tester.enterText(find.byKey(const ValueKey('code')), code);
      await tester.enterText(find.byKey(const ValueKey('pin-Nouveau PIN')), pin);
      await tester.enterText(find.byKey(const ValueKey('pin-Confirmez le PIN')), pin2);
      await submit(tester, 'Changer mon PIN');
    }

    await fill('12345', '7391', '7391');
    expect(find.text('Le code SMS fait 6 chiffres.'), findsOneWidget);
    await fill('123456', '7391', '7392');
    expect(find.text('Les deux PIN sont différents.'), findsOneWidget);
    expect(server.requests.where((r) => r.url.path == '/v1/auth/pin-reset'), isEmpty);

    await fill('000000', '7391', '7391');
    expect(find.text('Code invalide ou expiré.'), findsOneWidget);

    await fill('123456', '7391', '7391');
    expect(server.pin, '7391');
    expect(find.text('Bonjour Amani !'), findsOneWidget);
  });

  test('une saisie refusée par le serveur donne un message en français', () {
    expect(
      messageFor(ApiException(400, 'VALIDATION_ERROR', 'fullName: must not be blank')),
      'Certaines informations ne sont pas valides. Vérifiez la saisie et réessayez.',
    );
  });
}
