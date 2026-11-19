import 'package:material_ui/material_ui.dart';

/// Charte Capital Invest : encre #231F20, ocre #FBB03F, police Hanken Grotesk.
class Brand {
  static const ink = Color(0xFF231F20);
  static const ochre = Color(0xFFFBB03F);
  static const paper = Color(0xFFFAF7F2);
  static const muted = Color(0xFF6B6466);
  static const success = Color(0xFF1F7A4D);
  static const danger = Color(0xFFB3261E);
}

ThemeData buildTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: Brand.ochre,
    primary: Brand.ink,
    onPrimary: Colors.white,
    secondary: Brand.ochre,
    onSecondary: Brand.ink,
    surface: Brand.paper,
    error: Brand.danger,
  );
  final base = ThemeData(useMaterial3: true, colorScheme: scheme, fontFamily: 'HankenGrotesk');
  final border = OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: Color(0xFFD9D3CC)));
  return base.copyWith(
    scaffoldBackgroundColor: Brand.paper,
    appBarTheme: const AppBarTheme(
      backgroundColor: Brand.paper,
      foregroundColor: Brand.ink,
      elevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(fontFamily: 'HankenGrotesk', fontSize: 20, fontWeight: FontWeight.w700, color: Brand.ink),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: Brand.ink,
        foregroundColor: Colors.white,
        minimumSize: const Size.fromHeight(52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        textStyle: const TextStyle(fontFamily: 'HankenGrotesk', fontSize: 16, fontWeight: FontWeight.w700),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: Brand.ink,
        minimumSize: const Size.fromHeight(52),
        side: const BorderSide(color: Brand.ink, width: 1.5),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        textStyle: const TextStyle(fontFamily: 'HankenGrotesk', fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(borderSide: const BorderSide(color: Brand.ink, width: 1.5)),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    ),
  );
}
