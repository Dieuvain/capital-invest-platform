import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';

/// Vraie police et vraies icônes, pour des captures d'écran fidèles.
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  Future<ByteData> read(String path) async => ByteData.sublistView(File(path).readAsBytesSync());
  final flutterRoot = Platform.environment['FLUTTER_ROOT'] ?? '/opt/flutter-sdk/flutter';
  await (FontLoader('HankenGrotesk')..addFont(read('assets/fonts/HankenGrotesk.ttf'))).load();
  await (FontLoader(
    'MaterialIcons',
  )..addFont(read('$flutterRoot/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf'))).load();
  await testMain();
}
