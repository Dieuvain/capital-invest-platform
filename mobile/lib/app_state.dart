import 'dart:async';
import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

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

  /// Partage un fichier (relevé PDF) : WhatsApp, e-mail, Fichiers… Remplacé dans les tests.
  Future<void> Function(Uint8List bytes, String fileName, String mimeType) shareFile = _systemShareFile;

  /// Ouvre l'appareil photo et renvoie le texte du QR lu, ou null si le client abandonne.
  /// Remplacé dans les tests, qui n'ont pas de caméra.
  Future<String?> Function(BuildContext context)? scanQr;
  Customer? customer;

  // ---- Verrouillage et discrétion -------------------------------------------------------------

  /// Solde remplacé par des points sur l'accueil : pour sortir son téléphone en public.
  bool hideBalance = false;

  /// Temps en arrière-plan après lequel il faut de nouveau le PIN. Zéro : dès que l'app est quittée.
  Duration lockAfter = const Duration(minutes: 1);

  /// Message montré sur l'écran PIN après un verrouillage automatique.
  String? lockNotice;

  /// Horloge, remplacée dans les tests.
  DateTime Function() now = DateTime.now;

  /// Enregistre un réglage sur le téléphone. Remplacé dans les tests.
  Future<void> Function(String key, Object value) savePreference = _savePreference;

  DateTime? _backgroundSince;
  int _lockPaused = 0;

  /// Relit les réglages gardés sur le téléphone, au démarrage.
  Future<void> loadPreferences() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      hideBalance = prefs.getBool('hide_balance') ?? hideBalance;
      final seconds = prefs.getInt('lock_after_seconds');
      if (seconds != null && lockChoices.contains(Duration(seconds: seconds))) lockAfter = Duration(seconds: seconds);
    } catch (_) {
      // Stockage indisponible : réglages par défaut.
    }
  }

  void toggleHideBalance() {
    hideBalance = !hideBalance;
    unawaited(savePreference('hide_balance', hideBalance));
    notifyListeners();
  }

  void setLockAfter(Duration d) {
    lockAfter = d;
    unawaited(savePreference('lock_after_seconds', d.inSeconds));
    notifyListeners();
  }

  /// L'app passe en arrière-plan.
  void wentToBackground() {
    if (_lockPaused == 0) _backgroundSince ??= now();
  }

  /// L'app revient au premier plan : vrai s'il faut redemander le PIN.
  bool shouldLockOnReturn() {
    final since = _backgroundSince;
    _backgroundSince = null;
    return signedIn && since != null && !now().difference(since).isNegative && now().difference(since) >= lockAfter;
  }

  /// Ferme la session et revient à l'écran PIN, avec un mot d'explication.
  Future<void> lock() async {
    lockNotice = 'Par sécurité, l\'app s\'est verrouillée. Entrez votre PIN pour continuer.';
    await signOut();
  }

  /// Pendant l'appareil photo ou le partage, quitter l'app un moment ne la verrouille pas.
  Future<T> withoutLock<T>(Future<T> Function() action) async {
    _lockPaused++;
    try {
      return await action();
    } finally {
      _lockPaused--;
      _backgroundSince = null;
    }
  }

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
    lockNotice = null;
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

/// Délais proposés dans Sécurité.
const lockChoices = [Duration.zero, Duration(minutes: 1), Duration(minutes: 5)];

Future<void> _savePreference(String key, Object value) async {
  try {
    final prefs = await SharedPreferences.getInstance();
    if (value is bool) await prefs.setBool(key, value);
    if (value is int) await prefs.setInt(key, value);
  } catch (_) {
    // Stockage indisponible : le réglage vaut pour cette ouverture.
  }
}

Future<void> _systemShareFile(Uint8List bytes, String fileName, String mimeType) async {
  await SharePlus.instance.share(
    ShareParams(files: [XFile.fromData(bytes, mimeType: mimeType, name: fileName)], fileNameOverrides: [fileName]),
  );
}

Future<void> _systemShare(String text, String subject) async {
  await SharePlus.instance.share(ShareParams(text: text, subject: subject));
}
