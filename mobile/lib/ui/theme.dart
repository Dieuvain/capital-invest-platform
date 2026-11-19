import 'package:material_ui/material_ui.dart';

/// Charte Capital Invest : encre #231F20, ocre #FBB03F, police Hanken Grotesk.
class Brand {
  static const ink = Color(0xFF231F20);
  static const ochre = Color(0xFFFBB03F);
  static const ochreLight = Color(0xFFFDC56A);
  static const ochreDeep = Color(0xFFF2921C);
  static const success = Color(0xFF1F9D5C);
  static const danger = Color(0xFFD93025);

  static const ochreGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [ochreLight, ochreDeep],
  );
}

/// Couleurs qui changent entre le mode clair et le mode sombre.
@immutable
class Palette extends ThemeExtension<Palette> {
  const Palette({
    required this.bg,
    required this.surface,
    required this.surfaceAlt,
    required this.text,
    required this.muted,
    required this.divider,
    required this.nav,
    required this.frontCard,
    required this.backCard,
  });

  final Color bg;
  final Color surface;
  final Color surfaceAlt;
  final Color text;
  final Color muted;
  final Color divider;
  final Color nav;
  final Gradient frontCard;
  final Color backCard;

  static const light = Palette(
    bg: Color(0xFFEFEEEC),
    surface: Colors.white,
    surfaceAlt: Color(0xFFF1F0EE),
    text: Brand.ink,
    muted: Color(0xFF7A7476),
    divider: Color(0xFFECE9E6),
    nav: Color(0xFF151213),
    frontCard: LinearGradient(
      begin: Alignment.topLeft,
      end: Alignment.bottomRight,
      colors: [Color(0xFF3A3233), Brand.ink],
    ),
    backCard: Brand.ochre,
  );

  static const dark = Palette(
    bg: Colors.black,
    surface: Color(0xFF161616),
    surfaceAlt: Color(0xFF0B0B0B),
    text: Colors.white,
    muted: Color(0xFF8E8A8B),
    divider: Color(0xFF262626),
    nav: Color(0xFF1C1C1C),
    frontCard: LinearGradient(
      begin: Alignment.topLeft,
      end: Alignment.bottomRight,
      colors: [Color(0xFF4A4041), Color(0xFF2A2425)],
    ),
    backCard: Brand.ochre,
  );

  @override
  Palette copyWith() => this;

  @override
  Palette lerp(Palette? other, double t) => t < 0.5 ? this : (other ?? this);
}

extension PaletteOf on BuildContext {
  Palette get pal => Theme.of(this).extension<Palette>()!;
}

ThemeData buildTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final p = dark ? Palette.dark : Palette.light;
  final scheme = ColorScheme.fromSeed(
    seedColor: Brand.ochre,
    brightness: brightness,
    primary: dark ? Colors.white : Brand.ink,
    onPrimary: dark ? Colors.black : Colors.white,
    secondary: Brand.ochre,
    onSecondary: Brand.ink,
    surface: p.bg,
    onSurface: p.text,
    error: Brand.danger,
  );
  final base = ThemeData(useMaterial3: true, colorScheme: scheme, fontFamily: 'HankenGrotesk', brightness: brightness);
  final border = OutlineInputBorder(
    borderRadius: BorderRadius.circular(16),
    borderSide: BorderSide(color: p.divider),
  );
  return base.copyWith(
    extensions: [p],
    scaffoldBackgroundColor: p.bg,
    textTheme: base.textTheme.apply(bodyColor: p.text, displayColor: p.text, fontFamily: 'HankenGrotesk'),
    iconTheme: IconThemeData(color: p.text),
    dividerColor: p.divider,
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: dark ? Colors.white : Brand.ink,
        foregroundColor: dark ? Colors.black : Colors.white,
        minimumSize: const Size.fromHeight(54),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(99)),
        textStyle: const TextStyle(fontFamily: 'HankenGrotesk', fontSize: 16, fontWeight: FontWeight.w700),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: p.text,
        minimumSize: const Size.fromHeight(54),
        side: BorderSide(color: p.text, width: 1.5),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(99)),
        textStyle: const TextStyle(fontFamily: 'HankenGrotesk', fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: p.text)),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: p.surface,
      border: border,
      enabledBorder: border,
      focusedBorder: border.copyWith(borderSide: BorderSide(color: p.text, width: 1.5)),
      labelStyle: TextStyle(color: p.muted),
      contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
    ),
    chipTheme: base.chipTheme.copyWith(
      backgroundColor: p.surface,
      selectedColor: Brand.ochre,
      labelStyle: TextStyle(color: p.text, fontFamily: 'HankenGrotesk', fontWeight: FontWeight.w600),
      secondaryLabelStyle: const TextStyle(color: Brand.ink, fontFamily: 'HankenGrotesk', fontWeight: FontWeight.w700),
      side: BorderSide(color: p.divider),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(99)),
    ),
    bottomSheetTheme: BottomSheetThemeData(backgroundColor: p.surface, modalBackgroundColor: p.surface),
    datePickerTheme: DatePickerThemeData(backgroundColor: p.surface),
  );
}
