import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:local_auth/local_auth.dart';

/// Clé de connexion par empreinte, reçue du serveur à l'activation, pour un numéro.
class BiometricKey {
  const BiometricKey(this.phone, this.key);

  final String phone;
  final String key;
}

/// Lecteur d'empreinte (ou de visage) du téléphone et coffre où dort la clé. Remplacé dans les tests.
abstract class Biometrics {
  /// Vrai si le téléphone a un lecteur et qu'au moins une empreinte (ou un visage) y est enregistrée.
  Future<bool> available();

  /// Demande au client de poser son doigt. Faux s'il annule, si le doigt n'est pas reconnu ou si le
  /// lecteur est bloqué : l'app propose alors le PIN.
  Future<bool> confirm(String reason);

  Future<BiometricKey?> read();

  Future<void> save(BiometricKey key);

  Future<void> clear();
}

/// Pas de lecteur : téléphone trop ancien, ou tests qui n'en ont pas besoin.
class NoBiometrics implements Biometrics {
  const NoBiometrics();

  @override
  Future<bool> available() async => false;

  @override
  Future<bool> confirm(String reason) async => false;

  @override
  Future<BiometricKey?> read() async => null;

  @override
  Future<void> save(BiometricKey key) async {}

  @override
  Future<void> clear() async {}
}

/// Lecteur du téléphone (local_auth) et coffre chiffré du système (Keystore Android, trousseau iOS).
///
/// Biométrie seule : le code de déverrouillage du téléphone ne remplace pas l'empreinte, car un proche peut le
/// connaître. Le repli, c'est le PIN Capital Invest.
class DeviceBiometrics implements Biometrics {
  DeviceBiometrics({LocalAuthentication? auth, FlutterSecureStorage? storage})
    : _auth = auth ?? LocalAuthentication(),
      _storage = storage ??
          const FlutterSecureStorage(
            // La clé ne quitte pas ce téléphone : ni sauvegarde iCloud, ni restauration sur un autre appareil.
            iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
          );

  final LocalAuthentication _auth;
  final FlutterSecureStorage _storage;

  static const _phoneKey = 'biometric_phone';
  static const _secretKey = 'biometric_key';

  @override
  Future<bool> available() async {
    try {
      return await _auth.isDeviceSupported() && (await _auth.getAvailableBiometrics()).isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<bool> confirm(String reason) async {
    try {
      return await _auth.authenticate(localizedReason: reason, biometricOnly: true, persistAcrossBackgrounding: true);
    } catch (_) {
      // Trop d'essais, empreinte retirée des réglages, lecteur occupé : le PIN prend le relais.
      return false;
    }
  }

  @override
  Future<BiometricKey?> read() async {
    try {
      final phone = await _storage.read(key: _phoneKey);
      final key = await _storage.read(key: _secretKey);
      return phone == null || key == null ? null : BiometricKey(phone, key);
    } catch (_) {
      return null;
    }
  }

  @override
  Future<void> save(BiometricKey key) async {
    await _storage.write(key: _phoneKey, value: key.phone);
    await _storage.write(key: _secretKey, value: key.key);
  }

  @override
  Future<void> clear() async {
    try {
      await _storage.delete(key: _secretKey);
      await _storage.delete(key: _phoneKey);
    } catch (_) {
      // Coffre indisponible : le serveur a de toute façon retiré la clé.
    }
  }
}
