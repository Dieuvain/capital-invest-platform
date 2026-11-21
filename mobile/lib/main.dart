import 'dart:async';

import 'package:material_ui/material_ui.dart';

import 'api/api_client.dart';
import 'app_state.dart';
import 'biometrics.dart';
import 'device.dart';
import 'screens/auth_screens.dart';
import 'screens/home_screen.dart';
import 'screens/kyc_screen.dart';
import 'ui/theme.dart';

/// Adresse du serveur, fixée à la compilation : flutter run --dart-define=API_URL=https://api.capitalinvest.cd
/// Par défaut, le serveur local vu depuis l'émulateur Android.
const apiUrl = String.fromEnvironment('API_URL', defaultValue: 'http://10.0.2.2:8080');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final device = await DeviceIdentity.load();
  final app = AppState(
    ApiClient(baseUrl: apiUrl),
    deviceId: device.id,
    deviceName: device.name,
    biometrics: DeviceBiometrics(),
  );
  await app.loadPreferences();
  await app.loadBiometrics();
  runApp(CapitalInvestApp(app: app));
}

class CapitalInvestApp extends StatefulWidget {
  const CapitalInvestApp({super.key, required this.app, this.photos});

  final AppState app;

  /// Remplaçable dans les tests, pour ne pas ouvrir l'appareil photo.
  final PhotoSource? photos;

  @override
  State<CapitalInvestApp> createState() => _CapitalInvestAppState();
}

/// Surveille le passage en arrière-plan : au retour, après le délai choisi, l'app redemande le PIN.
/// Un téléphone posé ou volé déverrouillé ne laisse donc pas l'argent ouvert.
class _CapitalInvestAppState extends State<CapitalInvestApp> with WidgetsBindingObserver {
  final navigator = GlobalKey<NavigatorState>();

  AppState get app => widget.app;
  PhotoSource? get photos => widget.photos;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      // « paused » seulement : au retour, le téléphone repasse aussi par « hidden ».
      case AppLifecycleState.paused:
        app.wentToBackground();
      case AppLifecycleState.resumed:
        if (app.shouldLockOnReturn()) {
          // Les écrans ouverts (envoi en cours, PIN tapé à moitié) sont fermés avec la session.
          navigator.currentState?.popUntil((r) => r.isFirst);
          unawaited(app.lock());
        }
      default:
        break;
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) => MaterialApp(
      navigatorKey: navigator,
      title: 'Capital Invest',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      themeMode: app.themeMode,
      home: app.signedIn
          ? HomeScreen(key: ValueKey(app.customer!.id), app: app, photos: photos ?? ImagePickerPhotoSource())
          : WelcomeScreen(app: app),
    ),
  );
}
