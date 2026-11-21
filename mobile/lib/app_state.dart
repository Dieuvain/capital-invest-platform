import 'dart:async';
import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api/api_client.dart';
import 'api/models.dart';
import 'biometrics.dart';
import 'device.dart';

/// État de l'app : client connecté et identifiant de l'appareil. Le jeton reste en mémoire seulement :
/// à chaque ouverture, le client entre son PIN.
class AppState extends ChangeNotifier {
  AppState(this.api, {String? deviceId, this.deviceName, this.biometrics = const NoBiometrics()})
    : deviceId = deviceId ?? randomDeviceId();

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

  // ---- Connexion par empreinte --------------------------------------------------------------

  /// Lecteur d'empreinte du téléphone et coffre de la clé.
  final Biometrics biometrics;

  /// Le téléphone a un lecteur avec au moins une empreinte enregistrée.
  bool biometricsAvailable = false;

  /// Numéro dont la clé d'empreinte est gardée sur ce téléphone, ou null.
  String? biometricPhone;

  /// À l'écran de connexion, proposer l'empreinte tout de suite (ouverture de l'app, verrouillage, session
  /// expirée), mais pas après « Se déconnecter » : le client vient de choisir de sortir.
  bool offerBiometric = true;

  /// L'empreinte ouvre le compte connecté sur ce téléphone.
  bool get biometricEnabled => biometricPhone != null && biometricPhone == customer?.phone;

  Future<void> loadBiometrics() async {
    biometricsAvailable = await biometrics.available();
    biometricPhone = biometricsAvailable ? (await biometrics.read())?.phone : null;
    if (biometricPhone != null) lastPhone ??= biometricPhone;
    notifyListeners();
  }

  /// Active l'empreinte : PIN vérifié par le serveur, puis doigt posé, puis clé rangée dans le coffre.
  /// Faux si le client n'a pas posé son doigt (rien n'est activé).
  Future<bool> enableBiometric(String pin) async {
    final phone = customer!.phone;
    if (!await biometrics.confirm('Posez votre doigt pour activer la connexion par empreinte')) return false;
    final key = await api.enableBiometric(pin);
    await biometrics.save(BiometricKey(phone, key));
    biometricPhone = phone;
    notifyListeners();
    return true;
  }

  /// Retire l'empreinte de ce téléphone, au serveur puis dans le coffre.
  Future<void> disableBiometric() async {
    await api.disableBiometric();
    await forgetBiometric();
  }

  /// Oublie la clé gardée sur le téléphone, sans appeler le serveur (qui l'a déjà retirée).
  Future<void> forgetBiometric() async {
    if (biometricPhone == null) return;
    biometricPhone = null;
    await biometrics.clear();
    notifyListeners();
  }

  /// Connexion par empreinte. Null si le client n'a pas posé son doigt : il entre alors son PIN.
  /// Clé retirée par le serveur (PIN changé ailleurs, oublié, compte bloqué) : la clé est oubliée et
  /// l'erreur remonte, pour que l'écran explique pourquoi le PIN est demandé.
  Future<SessionTokens?> biometricLogin() async {
    offerBiometric = false;
    final stored = await biometrics.read();
    if (stored == null) {
      await forgetBiometric();
      return null;
    }
    if (!await biometrics.confirm('Posez votre doigt pour ouvrir Capital Invest')) return null;
    try {
      return await api.biometricLogin(phone: stored.phone, key: stored.key, deviceId: deviceId, deviceName: deviceName);
    } on ApiException catch (e) {
      if (e.code == 'BIOMETRIC_INVALID') await forgetBiometric();
      rethrow;
    }
  }

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
    lockNotice = biometricPhone != null
        ? 'Par sécurité, l\'app s\'est verrouillée. Posez votre doigt ou entrez votre PIN pour continuer.'
        : 'Par sécurité, l\'app s\'est verrouillée. Entrez votre PIN pour continuer.';
    await signOut(offerBiometric: true);
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

  /// Fin de session (déconnexion ou expiration) : retour à l'écran PIN. [offerBiometric] : l'empreinte est
  /// proposée d'emblée à l'écran de connexion (verrouillage, expiration), pas après « Se déconnecter ».
  Future<void> signOut({bool callServer = true, bool offerBiometric = false}) async {
    this.offerBiometric = offerBiometric;
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
