import 'package:material_ui/material_ui.dart';

import 'chrome.dart';
import 'theme.dart';

/// Deux cartes empilées : la carte prépayée ocre derrière, le portefeuille encre devant,
/// avec une encoche en bas à droite qui accueille un bouton en pilule.
class StackedCards extends StatelessWidget {
  const StackedCards({
    super.key,
    required this.backLabel,
    required this.backNumber,
    required this.label,
    required this.amount,
    required this.cornerLabel,
    required this.cornerValue,
    required this.ownerLabel,
    required this.owner,
    this.pillLabel,
    this.pillIcon = Icons.add,
    this.onPill,
    this.onToggleHidden,
    this.hidden = false,
  });

  /// Œil à côté du libellé : masquer ou montrer le montant.
  final VoidCallback? onToggleHidden;
  final bool hidden;

  final String backLabel;
  final String backNumber;
  final String label;

  /// Montant formaté par formatUsd, ou « … ».
  final String amount;
  final String cornerLabel;
  final String cornerValue;
  final String ownerLabel;
  final String owner;
  final String? pillLabel;
  final IconData pillIcon;
  final VoidCallback? onPill;

  static const _backHeight = 52.0;
  static const _frontHeight = 196.0;

  @override
  Widget build(BuildContext context) {
    final p = context.pal;
    return SizedBox(
      height: _backHeight + _frontHeight,
      child: Stack(
        children: [
          // Carte de derrière : seul son bandeau dépasse.
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            height: _backHeight + 40,
            child: Container(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
              decoration: BoxDecoration(color: p.backCard, borderRadius: BorderRadius.circular(26)),
              alignment: Alignment.topCenter,
              child: Row(
                children: [
                  const _Chip(),
                  const SizedBox(width: 10),
                  Text(
                    backLabel,
                    style: const TextStyle(color: Brand.ink, fontWeight: FontWeight.w700, fontSize: 13),
                  ),
                  const Spacer(),
                  Text(backNumber, style: const TextStyle(color: Brand.ink, fontSize: 15, letterSpacing: 1)),
                ],
              ),
            ),
          ),
          // Carte de devant, avec l'encoche du bouton.
          Positioned(
            left: 0,
            right: 0,
            top: _backHeight,
            height: _frontHeight,
            child: LayoutBuilder(
              builder: (context, box) {
                final pillWidth = pillLabel == null ? 0.0 : 136.0;
                return Stack(
                  children: [
                    ClipPath(
                      clipper: _NotchClipper(notchWidth: pillWidth, notchHeight: pillLabel == null ? 0 : 58),
                      child: Container(
                        decoration: BoxDecoration(gradient: p.frontCard, borderRadius: BorderRadius.circular(26)),
                        padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Container(
                                  width: 30,
                                  height: 30,
                                  decoration: BoxDecoration(color: Brand.ochre, borderRadius: BorderRadius.circular(9)),
                                  alignment: Alignment.center,
                                  child: const Text(
                                    'CI',
                                    style: TextStyle(color: Brand.ink, fontWeight: FontWeight.w900, fontSize: 12),
                                  ),
                                ),
                                const Spacer(),
                                Column(
                                  crossAxisAlignment: CrossAxisAlignment.end,
                                  children: [
                                    Text(cornerLabel, style: const TextStyle(color: Colors.white70, fontSize: 12)),
                                    Text(
                                      cornerValue,
                                      style: const TextStyle(
                                        color: Colors.white,
                                        fontSize: 15,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                            const Spacer(),
                            Row(
                              children: [
                                Text(label, style: const TextStyle(color: Colors.white70, fontSize: 13)),
                                if (onToggleHidden != null)
                                  InkResponse(
                                    key: const ValueKey('toggle-balance'),
                                    onTap: onToggleHidden,
                                    radius: 20,
                                    child: Padding(
                                      padding: const EdgeInsets.symmetric(horizontal: 8),
                                      child: Icon(
                                        hidden ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                                        size: 15,
                                        color: Colors.white70,
                                        semanticLabel: hidden ? 'Montrer le solde' : 'Masquer le solde',
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                            FittedBox(
                              fit: BoxFit.scaleDown,
                              alignment: Alignment.centerLeft,
                              child: Amount(amount, key: const ValueKey('balance'), size: 36, color: Colors.white),
                            ),
                            const SizedBox(height: 10),
                            Text(ownerLabel, style: const TextStyle(color: Colors.white70, fontSize: 12)),
                            Text(
                              owner,
                              style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w500),
                            ),
                          ],
                        ),
                      ),
                    ),
                    if (pillLabel != null)
                      Positioned(
                        right: 0,
                        bottom: 0,
                        width: pillWidth - 8,
                        height: 50,
                        child: Material(
                          color: p.text == Colors.white ? const Color(0xFF1C1C1C) : Brand.ink,
                          borderRadius: BorderRadius.circular(99),
                          child: InkWell(
                            borderRadius: BorderRadius.circular(99),
                            onTap: onPill,
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Container(
                                  width: 30,
                                  height: 30,
                                  decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                                  child: Icon(pillIcon, size: 20, color: Brand.ink),
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  pillLabel!,
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontWeight: FontWeight.w600,
                                    fontSize: 15,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip();

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 26,
    height: 16,
    child: Stack(
      children: [
        Positioned(
          left: 0,
          child: Container(
            width: 16,
            height: 16,
            decoration: const BoxDecoration(color: Brand.ink, shape: BoxShape.circle),
          ),
        ),
        Positioned(
          right: 0,
          child: Container(
            width: 16,
            height: 16,
            decoration: BoxDecoration(
              color: Brand.ochre,
              shape: BoxShape.circle,
              border: Border.all(color: Brand.ink, width: 2.5),
            ),
          ),
        ),
      ],
    ),
  );
}

/// Carte arrondie dont le coin bas droit est creusé en pilule, comme dans le modèle.
class _NotchClipper extends CustomClipper<Path> {
  _NotchClipper({required this.notchWidth, required this.notchHeight});

  final double notchWidth;
  final double notchHeight;

  @override
  Path getClip(Size size) {
    final card = Path()..addRRect(RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(26)));
    if (notchWidth == 0) return card;
    final notch = Path()
      ..addRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(size.width - notchWidth, size.height - notchHeight, notchWidth + 30, notchHeight + 30),
          const Radius.circular(30),
        ),
      );
    return Path.combine(PathOperation.difference, card, notch);
  }

  @override
  bool shouldReclip(_NotchClipper old) => old.notchWidth != notchWidth || old.notchHeight != notchHeight;
}
