import 'package:material_ui/material_ui.dart';

import 'theme.dart';

/// Bouton rond de l'en-tête (menu, alertes, retour).
class RoundButton extends StatelessWidget {
  const RoundButton({super.key, required this.icon, required this.tooltip, required this.onTap, this.dot = false});

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final bool dot;

  @override
  Widget build(BuildContext context) => Tooltip(
    message: tooltip,
    child: Material(
      color: context.pal.surface,
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: SizedBox(
          width: 48,
          height: 48,
          child: Stack(
            alignment: Alignment.center,
            children: [
              Icon(icon, size: 22),
              if (dot)
                Positioned(
                  top: 12,
                  right: 13,
                  child: Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(color: Brand.ochreDeep, shape: BoxShape.circle),
                  ),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}

/// Bloc arrondi sur fond de page.
class SoftCard extends StatelessWidget {
  const SoftCard({
    super.key,
    required this.child,
    this.onTap,
    this.padding = const EdgeInsets.all(18),
    this.width,
    this.radius = 26,
  });

  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsets padding;
  final double? width;
  final double radius;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: width,
    child: Material(
      color: context.pal.surface,
      borderRadius: BorderRadius.circular(radius),
      child: InkWell(
        borderRadius: BorderRadius.circular(radius),
        onTap: onTap,
        child: Padding(padding: padding, child: child),
      ),
    ),
  );
}

/// Pastille d'icône ronde, discrète.
class IconTile extends StatelessWidget {
  const IconTile(this.icon, {super.key, this.size = 48, this.accent = false});

  final IconData icon;
  final double size;
  final bool accent;

  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(
      gradient: accent ? Brand.ochreGradient : null,
      color: accent ? null : context.pal.surfaceAlt,
      shape: BoxShape.circle,
    ),
    child: Icon(icon, size: size * 0.44, color: accent ? Brand.ink : null),
  );
}

/// Montant avec les centimes en plus petit : « 1 237,50 $ ».
class Amount extends StatelessWidget {
  const Amount(this.formatted, {super.key, this.size = 34, this.weight = FontWeight.w700, this.color});

  /// Montant déjà formaté par formatUsd.
  final String formatted;
  final double size;
  final FontWeight weight;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final c = color ?? context.pal.text;
    final comma = formatted.lastIndexOf(',');
    final main = comma < 0 ? formatted : formatted.substring(0, comma);
    final rest = comma < 0 ? '' : formatted.substring(comma);
    return Text.rich(
      TextSpan(
        children: [
          TextSpan(
            text: main,
            style: TextStyle(fontSize: size, fontWeight: weight, color: c, letterSpacing: -0.5),
          ),
          TextSpan(
            text: rest,
            style: TextStyle(fontSize: size * 0.55, fontWeight: weight, color: c),
          ),
        ],
      ),
      semanticsLabel: formatted,
    );
  }
}

/// Page secondaire : bouton retour rond, grand titre, contenu sur fond de page.
class BrandedPage extends StatelessWidget {
  const BrandedPage({super.key, required this.title, required this.children, this.subtitle});

  final String title;
  final String? subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 40),
        children: [
          if (Navigator.of(context).canPop())
            Align(
              alignment: Alignment.centerLeft,
              child: RoundButton(
                icon: Icons.arrow_back_rounded,
                tooltip: 'Retour',
                onTap: () => Navigator.of(context).maybePop(),
              ),
            ),
          const SizedBox(height: 18),
          Text(title, style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w700, letterSpacing: -0.5)),
          if (subtitle != null) ...[
            const SizedBox(height: 6),
            Text(subtitle!, style: TextStyle(color: context.pal.muted, fontSize: 15, height: 1.4)),
          ],
          const SizedBox(height: 24),
          ...children,
        ],
      ),
    ),
  );
}
