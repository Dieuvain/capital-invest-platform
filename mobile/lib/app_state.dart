import 'package:material_ui/material_ui.dart';
import 'package:share_plus/share_plus.dart';

import 'api/api_client.dart';
import 'api/models.dart';
import 'device.dart';

/// État de l'app : client connecté et identifiant de l'appareil. Le jeton reste en mémoire seulement :
/// à chaque ouverture, le client entre son PIN.
class AppState extends ChangeNotifier {
  AppState(this.api, {String? deviceId, this.deviceName}) : deviceId = deviceId ?? randomDeviceId();

  final ApiClient api;
  final String deviceId;

  /// Nom du téléphone, montré au client dans ses connexions récentes.
  final String? deviceName;

  /// Ouvre le partage du téléphone (WhatsApp, SMS…). Remplacé dans les tests.
  Future<void> Function(String text, String subject) share = _systemShare;

  /// Ouvre l'appareil photo et renvoie le texte du QR lu, ou null si le client abandonne.
  /// Remplacé dans les tests, qui n'ont pas de caméra.
  Future<String?> Function(BuildContext context)? scanQr;
  Customer? customer;

  /// Clair, sombre, ou comme le téléphone (par défaut).
  ThemeMode themeMode = ThemeMode.system;

  void setThemeMode(ThemeMode mode) {
    themeMode = mode;
    notifyListeners();
  }

  /// Numéro du dernier client connecté, pour pré-remplir l'écran PIN.
  String? lastPhone;

  bool get signedIn => customer != null && api.token != null;

  void signedInAs(SessionTokens tokens) {
    customer = tokens.customer;
    lastPhone = tokens.customer.phone;
    notifyListeners();
  }

  Future<void> refreshCustomer() async {
    customer = await api.me();
    notifyListeners();
  }

  /// Fin de session (déconnexion ou expiration) : retour à l'écran PIN.
  Future<void> signOut({bool callServer = true}) async {
    if (callServer) {
      try {
        await api.logout();
      } on ApiException {
        // La session est peut-être déjà expirée côté serveur.
      }
    }
    api.token = null;
    customer = null;
    notifyListeners();
  }
}

Future<void> _systemShare(String text, String subject) async {
  await SharePlus.instance.share(ShareParams(text: text, subject: subject));
}
