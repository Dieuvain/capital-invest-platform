import 'package:material_ui/material_ui.dart';

import 'theme.dart';

/// Bouton carré arrondi, translucide, posé sur l'en-tête ocre.
class HeaderButton extends StatelessWidget {
  const HeaderButton({super.key, required this.icon, required this.tooltip, required this.onTap, this.dot = false});

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final bool dot;

  @override
  Widget build(BuildContext context) => Tooltip(
        message: tooltip,
        child: Material(
          color: const Color(0x33FFFFFF),
          borderRadius: BorderRadius.circular(18),
          child: InkWell(
            borderRadius: BorderRadius.circular(18),
            onTap: onTap,
            child: SizedBox(
              width: 52,
              height: 52,
              child: Stack(alignment: Alignment.center, children: [
                Icon(icon, color: Brand.ink, size: 26),
                if (dot)
                  Positioned(
                    top: 12,
                    right: 12,
                    child: Container(
                      width: 9,
                      height: 9,
                      decoration: BoxDecoration(
                          color: Colors.white, shape: BoxShape.circle, border: Border.all(color: Brand.ink, width: 1.5)),
                    ),
                  ),
              ]),
            ),
          ),
        ),
      );
}

/// Feuille claire aux coins arrondis qui recouvre l'en-tête, avec une petite bosse sous l'onglet actif.
class NotchedSheetClipper extends CustomClipper<Path> {
  NotchedSheetClipper({required this.notchX, this.radius = 32, this.notchWidth = 64, this.notchHeight = 14});

  /// Position horizontale de la bosse ; null : pas de bosse.
  final double? notchX;
  final double radius;
  final double notchWidth;
  final double notchHeight;

  @override
  Path getClip(Size size) {
    final top = notchHeight;
    final p = Path()..moveTo(0, top + radius);
    p.quadraticBezierTo(0, top, radius, top);
    final x = notchX;
    if (x != null) {
      final half = notchWidth / 2;
      p.lineTo(x - half, top);
      p.cubicTo(x - half * 0.45, top, x - half * 0.45, 0, x, 0);
      p.cubicTo(x + half * 0.45, 0, x + half * 0.45, top, x + half, top);
    }
    p.lineTo(size.width - radius, top);
    p.quadraticBezierTo(size.width, top, size.width, top + radius);
    p.lineTo(size.width, size.height);
    p.lineTo(0, size.height);
    p.close();
    return p;
  }

  @override
  bool shouldReclip(NotchedSheetClipper old) => old.notchX != notchX;
}

/// Carte blanche à ombre douce.
class SoftCard extends StatelessWidget {
  const SoftCard({super.key, required this.child, this.onTap, this.padding = const EdgeInsets.all(18), this.width});

  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsets padding;
  final double? width;

  @override
  Widget build(BuildContext context) => Container(
        width: width,
        decoration: BoxDecoration(color: Brand.card, borderRadius: BorderRadius.circular(22), boxShadow: Brand.softShadow),
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(22),
          child: InkWell(
            borderRadius: BorderRadius.circular(22),
            onTap: onTap,
            child: Padding(padding: padding, child: child),
          ),
        ),
      );
}

/// Pastille d'icône au dégradé ocre, ombrée.
class IconTile extends StatelessWidget {
  const IconTile(this.icon, {super.key, this.size = 52, this.muted = false});

  final IconData icon;
  final double size;
  final bool muted;

  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          gradient: muted ? null : Brand.tileGradient,
          color: muted ? const Color(0xFFE9E5E0) : null,
          borderRadius: BorderRadius.circular(size * 0.32),
          boxShadow: muted ? null : const [BoxShadow(color: Color(0x40F2921C), blurRadius: 14, offset: Offset(0, 6))],
        ),
        child: Icon(icon, color: muted ? Brand.muted : Brand.ink, size: size * 0.5),
      );
}

/// Montant avec les centimes en plus petit : « 1 237,50 $ ».
class Amount extends StatelessWidget {
  const Amount(this.formatted, {super.key, this.size = 34, this.weight = FontWeight.w800, this.color = Brand.ink});

  /// Montant déjà formaté par formatUsd.
  final String formatted;
  final double size;
  final FontWeight weight;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final comma = formatted.lastIndexOf(',');
    final main = comma < 0 ? formatted : formatted.substring(0, comma);
    final rest = comma < 0 ? '' : formatted.substring(comma);
    return Text.rich(
      TextSpan(children: [
        TextSpan(text: main, style: TextStyle(fontSize: size, fontWeight: weight, color: color, letterSpacing: -0.5)),
        TextSpan(text: rest, style: TextStyle(fontSize: size * 0.5, fontWeight: weight, color: color)),
      ]),
      semanticsLabel: formatted,
    );
  }
}

/// Page à en-tête ocre et feuille arrondie, pour les écrans secondaires.
class BrandedPage extends StatelessWidget {
  const BrandedPage({super.key, required this.title, required this.children, this.subtitle});

  final String title;
  final String? subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: Brand.ochreDeep,
        body: Container(
          decoration: const BoxDecoration(gradient: Brand.headerGradient),
          child: SafeArea(
            bottom: false,
            child: Column(children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 20, 18),
                child: Row(children: [
                  if (Navigator.of(context).canPop())
                    IconButton(
                      tooltip: 'Retour',
                      icon: const Icon(Icons.arrow_back, color: Brand.ink),
                      onPressed: () => Navigator.of(context).maybePop(),
                    )
                  else
                    const SizedBox(width: 8),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(title, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Brand.ink)),
                  ),
                ]),
              ),
              Expanded(
                child: ClipPath(
                  clipper: NotchedSheetClipper(notchX: null, notchHeight: 0),
                  child: Container(
                    color: Brand.paper,
                    child: ListView(
                      padding: const EdgeInsets.fromLTRB(20, 28, 20, 32),
                      children: [
                        if (subtitle != null) ...[
                          Text(subtitle!, style: const TextStyle(color: Brand.muted, fontSize: 15, height: 1.4)),
                          const SizedBox(height: 24),
                        ],
                        ...children,
                      ],
                    ),
                  ),
                ),
              ),
            ]),
          ),
        ),
      );
}
