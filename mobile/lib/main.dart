import 'package:material_ui/material_ui.dart';

import 'api/api_client.dart';
import 'app_state.dart';
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
  runApp(
    CapitalInvestApp(
      app: AppState(
        ApiClient(baseUrl: apiUrl),
        deviceId: device.id,
        deviceName: device.name,
      ),
    ),
  );
}

class CapitalInvestApp extends StatelessWidget {
  const CapitalInvestApp({super.key, required this.app, this.photos});

  final AppState app;

  /// Remplaçable dans les tests, pour ne pas ouvrir l'appareil photo.
  final PhotoSource? photos;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) => MaterialApp(
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
