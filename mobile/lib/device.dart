import 'dart:math';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Identité du téléphone envoyée à la connexion. L'identifiant est tiré une fois puis gardé :
/// le serveur reconnaît ainsi un téléphone déjà utilisé et ne prévient le client que d'un nouveau.
class DeviceIdentity {
  const DeviceIdentity(this.id, this.name);

  final String id;

  /// « Samsung Galaxy A14 », montré au client dans ses connexions récentes.
  final String? name;

  static Future<DeviceIdentity> load() async {
    String? id;
    try {
      final prefs = await SharedPreferences.getInstance();
      id = prefs.getString('device_id');
      if (id == null) {
        id = randomDeviceId();
        await prefs.setString('device_id', id);
      }
    } catch (_) {
      // Stockage indisponible : un identifiant pour cette ouverture seulement.
    }
    return DeviceIdentity(id ?? randomDeviceId(), await _name());
  }

  static Future<String?> _name() async {
    try {
      final info = DeviceInfoPlugin();
      switch (defaultTargetPlatform) {
        case TargetPlatform.android:
          final a = await info.androidInfo;
          final brand = a.manufacturer.isEmpty
              ? ''
              : '${a.manufacturer[0].toUpperCase()}${a.manufacturer.substring(1)} ';
          return '$brand${a.model}'.trim();
        case TargetPlatform.iOS:
          return (await info.iosInfo).modelName;
        default:
          return null;
      }
    } catch (_) {
      return null;
    }
  }
}

String randomDeviceId() {
  final r = Random.secure();
  return 'app-${List.generate(8, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join()}';
}
