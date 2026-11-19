import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';

/// Charge la vraie police dans les tests, pour que les mises en page soient vérifiées avec les vraies largeurs.
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  final loader = FontLoader('HankenGrotesk')
    ..addFont(Future.value(ByteData.sublistView(File('assets/fonts/HankenGrotesk.ttf').readAsBytesSync())));
  await loader.load();
  await testMain();
}
