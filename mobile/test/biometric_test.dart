import 'dart:convert';

import 'package:capital_invest/api/api_client.dart';
import 'package:capital_invest/app_state.dart';
import 'package:capital_invest/biometrics.dart';
import 'package:capital_invest/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'fake_server.dart';

/// Lecteur d'empreinte simulé : le test décide si le doigt est reconnu.
class FakeBiometrics implements Biometrics {
  bool hasReader = true;
  bool fingerAccepted = true;
  BiometricKey? stored;
  final prompts = <String>[];

  @override
  Future<bool> available() async => hasReader;

  @override
  Future<bool> confirm(String reason) async {
    prompts.add(reason);
    return fingerAccepted;
  }

  @override
  Future<BiometricKey?> read() async => stored;

  @override
  Future<void> save(BiometricKey key) async => stored = key;

  @override
  Future<void> clear() async => stored = null;
}

void main() {
  late FakeServer server;
  late FakeBiometrics reader;
  late AppState app;

  setUp(() {
    server = FakeServer();
    reader = FakeBiometrics();
    app = AppState(ApiClient(baseUrl: 'http://test', httpClient: server.client), deviceId: 'test', biometrics: reader)
      ..savePreference = (key, value) async {};
  });

  Future<void> start(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await app.loadBiometrics();
    await tester.pumpWidget(CapitalInvestApp(app: app));
    await tester.pumpAndSettle();
  }

  Future<void> pinLogin(WidgetTester tester, {String pin = '2580'}) async {
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), pin);
    await tester.ensureVisible(find.text('Se connecter'));
    await tester.tap(find.text('Se connecter'));
    await tester.pumpAndSettle();
  }

  Future<void> openSecurity(WidgetTester tester) async {
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sécurité et PIN'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const ValueKey('biometric-switch')));
    await tester.pumpAndSettle();
  }

  Future<void> signOut(WidgetTester tester) async {
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Se déconnecter'));
    await tester.tap(find.text('Se déconnecter'));
    await tester.pumpAndSettle();
  }

  Iterable<Map<String, dynamic>> sent(String path) =>
      server.requests.where((r) => r.url.path == path).map((r) => r.body.isEmpty ? <String, dynamic>{} : jsonDecode(r.body));

  /// Clé déjà rangée sur le téléphone, comme après une activation lors d'une ouverture précédente.
  void alreadyEnabled() {
    server.biometricKeys.add('cle-0');
    reader.stored = const BiometricKey('+243970000001', 'cle-0');
  }

  testWidgets('activer l\'empreinte dans Sécurité : PIN vérifié, doigt posé, clé rangée', (tester) async {
    await start(tester);
    expect(find.byKey(const ValueKey('biometric-login')), findsNothing);
    await pinLogin(tester);
    await openSecurity(tester);
    expect(find.text('Désactivée'), findsOneWidget);

    // Mauvais PIN : refusé par le serveur, rien n'est rangé.
    await tester.tap(find.byKey(const ValueKey('biometric-switch')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '9999');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Numéro ou PIN incorrect.'), findsOneWidget);
    expect(reader.stored, isNull);

    // Doigt non reconnu : rien n'est demandé au serveur.
    reader.fingerAccepted = false;
    await tester.ensureVisible(find.byKey(const ValueKey('biometric-switch')));
    await tester.tap(find.byKey(const ValueKey('biometric-switch')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(sent('/v1/me/security/biometric'), hasLength(1));
    expect(reader.stored, isNull);
    expect(find.text('Empreinte non reconnue : rien n\'a changé. Réessayez.'), findsOneWidget);

    reader.fingerAccepted = true;
    await tester.ensureVisible(find.byKey(const ValueKey('biometric-switch')));
    await tester.tap(find.byKey(const ValueKey('biometric-switch')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('pin-PIN')), '2580');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(sent('/v1/me/security/biometric').last, {'pin': '2580'});
    expect(reader.stored?.phone, '+243970000001');
    expect(reader.stored?.key, server.biometricKeys.single);
    expect(find.text('Activée sur ce téléphone'), findsOneWidget);
    expect(find.textContaining('Votre PIN reste demandé pour chaque envoi'), findsOneWidget);
  });

  testWidgets('à l\'ouverture de l\'app, le doigt suffit pour entrer', (tester) async {
    alreadyEnabled();
    await start(tester);
    expect(reader.prompts, ['Posez votre doigt pour ouvrir Capital Invest']);
    expect(sent('/v1/auth/biometric').single, {'phone': '+243970000001', 'key': 'cle-0', 'deviceId': 'test'});
    expect(sent('/v1/auth/login'), isEmpty);
    expect(find.byKey(const ValueKey('balance')), findsOneWidget);
  });

  testWidgets('doigt refusé : le PIN reste là, et le bouton permet de réessayer', (tester) async {
    alreadyEnabled();
    reader.fingerAccepted = false;
    await start(tester);
    expect(sent('/v1/auth/biometric'), isEmpty);
    expect(find.byKey(const ValueKey('biometric-login')), findsOneWidget);
    expect(find.text('Compte +243970000001. Ou entrez votre PIN :'), findsOneWidget);
    // Le numéro est déjà rempli : il suffit du PIN.
    expect(find.text('970000001'), findsOneWidget);

    reader.fingerAccepted = true;
    await tester.tap(find.byKey(const ValueKey('biometric-login')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('balance')), findsOneWidget);
  });

  testWidgets('le PIN marche toujours quand l\'empreinte est active', (tester) async {
    alreadyEnabled();
    reader.fingerAccepted = false;
    await start(tester);
    await pinLogin(tester);
    expect(find.byKey(const ValueKey('balance')), findsOneWidget);
    expect(reader.stored, isNotNull);
  });

  testWidgets('clé retirée par le serveur (PIN changé ailleurs) : oubliée, message clair, PIN demandé', (tester) async {
    reader.stored = const BiometricKey('+243970000001', 'cle-retiree');
    await start(tester);
    expect(find.textContaining('L\'empreinte a été désactivée sur ce téléphone'), findsOneWidget);
    expect(reader.stored, isNull);
    expect(find.byKey(const ValueKey('biometric-login')), findsNothing);
    await pinLogin(tester);
    expect(find.byKey(const ValueKey('balance')), findsOneWidget);
  });

  testWidgets('après « Se déconnecter », le lecteur ne s\'ouvre pas tout seul ; après un verrouillage, si', (tester) async {
    alreadyEnabled();
    await start(tester);
    expect(reader.prompts, hasLength(1));

    await signOut(tester);
    expect(reader.prompts, hasLength(1));
    expect(find.byKey(const ValueKey('biometric-login')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('biometric-login')));
    await tester.pumpAndSettle();
    expect(reader.prompts, hasLength(2));

    // Verrouillage : le lecteur s'ouvre tout seul ; doigt refusé, le message explique les deux voies.
    reader.fingerAccepted = false;
    await app.lock();
    await tester.pumpAndSettle();
    expect(reader.prompts, hasLength(3));
    expect(find.textContaining('Posez votre doigt ou entrez votre PIN'), findsOneWidget);
  });

  testWidgets('désactiver l\'empreinte la retire du serveur et du téléphone', (tester) async {
    alreadyEnabled();
    await start(tester);
    await openSecurity(tester);
    expect(find.text('Activée sur ce téléphone'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('biometric-switch')));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.method == 'DELETE' && r.url.path == '/v1/me/security/biometric'), hasLength(1));
    expect(server.biometricKeys, isEmpty);
    expect(reader.stored, isNull);
    expect(find.textContaining('Empreinte désactivée'), findsOneWidget);
  });

  testWidgets('téléphone sans empreinte enregistrée : Sécurité explique, sans interrupteur', (tester) async {
    reader.hasReader = false;
    await start(tester);
    await pinLogin(tester);
    await tester.tap(find.byTooltip('Profil'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sécurité et PIN'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Aucune empreinte n\'est enregistrée sur ce téléphone'), findsOneWidget);
    expect(find.byKey(const ValueKey('biometric-switch')), findsNothing);
  });

  testWidgets('PIN oublié : l\'empreinte est oubliée, à réactiver avec le nouveau PIN', (tester) async {
    alreadyEnabled();
    reader.fingerAccepted = false;
    await start(tester);
    await tester.ensureVisible(find.text('PIN oublié ?'));
    await tester.tap(find.text('PIN oublié ?'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('phone-Numéro de téléphone')), '970000001');
    await tester.tap(find.text('Recevoir le code'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('code')), '123456');
    await tester.enterText(find.byKey(const ValueKey('pin-Nouveau PIN')), '7391');
    await tester.enterText(find.byKey(const ValueKey('pin-Confirmez le PIN')), '7391');
    await tester.tap(find.text('Changer mon PIN'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('balance')), findsOneWidget);
    expect(reader.stored, isNull);
    expect(app.biometricPhone, isNull);
  });
}
