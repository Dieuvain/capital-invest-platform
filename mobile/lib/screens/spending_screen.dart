import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'statement_screens.dart';

const _labels = {
  'TRANSFERS': ('Envois', Icons.send_rounded),
  'MERCHANTS': ('Marchands', Icons.storefront_rounded),
  'BILLS': ('Factures', Icons.receipt_rounded),
  'AIRTIME': ('Crédit téléphone', Icons.phone_android_rounded),
  'WITHDRAWALS': ('Retraits', Icons.account_balance_wallet_rounded),
  'FEES': ('Frais', Icons.percent_rounded),
};

const _monthsShort = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

/// « 1er–19 oct. », ou « octobre 2026 » pour un mois entier.
String _period(DateTime from, DateTime to) {
  if (from.day == 1 && to.add(const Duration(days: 1)).month != to.month) {
    return monthTitle('${to.year}-${to.month.toString().padLeft(2, '0')}').toLowerCase();
  }
  return '${from.day == 1 ? '1er' : from.day}–${to.day} ${_monthsShort[to.month - 1]}';
}

/// « Mes dépenses » : où part l'argent ce mois-ci, par catégorie, comparé au mois d'avant.
class SpendingScreen extends StatefulWidget {
  const SpendingScreen({super.key, required this.app});

  final AppState app;

  @override
  State<SpendingScreen> createState() => _SpendingScreenState();
}

class _SpendingScreenState extends State<SpendingScreen> with ApiAction {
  List<StatementMonth>? months;
  String? month;
  SpendingSummary? summary;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, widget.app.api.statementMonths);
    if (l != null && mounted) setState(() => months = l.take(6).toList());
    await _show(l?.firstOrNull?.month);
  }

  Future<void> _show(String? m) async {
    setState(() => month = m);
    final s = await run(widget.app, () => widget.app.api.spending(m));
    if (s != null && mounted && month == m) setState(() => summary = s);
  }

  @override
  Widget build(BuildContext context) {
    final s = summary;
    return FormPage(
      title: 'Mes dépenses',
      subtitle: 'Où part votre argent, frais compris. L\'épargne n\'est pas une dépense : elle reste à vous.',
      children: [
        ErrorBanner(error),
        if ((months?.length ?? 0) > 1)
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                for (final m in months!)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      key: ValueKey('month-${m.month}'),
                      label: Text(monthTitle(m.month)),
                      selected: month == m.month,
                      onSelected: (_) => _show(m.month),
                    ),
                  ),
              ],
            ),
          ),
        const SizedBox(height: 16),
        if (s != null) ...[
          _Headline(s),
          const SizedBox(height: 16),
          if (s.categories.isEmpty)
            Text('Aucune dépense ce mois-ci.', style: TextStyle(color: context.pal.muted))
          else
            SoftCard(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 4),
              child: Column(
                children: [
                  for (final c in s.categories)
                    _CategoryRow(c, max: s.categories.first.amountMinor, compared: s.comparedTo != null),
                ],
              ),
            ),
          const SizedBox(height: 12),
          IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(child: _Stat('Reçu', s.receivedMinor, 'recharges et envois reçus')),
                const SizedBox(width: 12),
                Expanded(child: _Stat('Mis de côté', s.savedMinor, 'sur votre épargne')),
              ],
            ),
          ),
          if (s.topRecipients.isNotEmpty) ...[
            const SizedBox(height: 24),
            const Text('À qui vous envoyez le plus', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
            const SizedBox(height: 10),
            for (final r in s.topRecipients)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: SoftCard(
                  padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
                  child: Row(
                    children: [
                      const IconTile(Icons.person_rounded, size: 38),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(r.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                            Text(
                              '${r.count == 1 ? '1 envoi' : '${r.count} envois'}${r.phone == null ? '' : ' · ${r.phone}'}',
                              style: TextStyle(color: context.pal.muted, fontSize: 13),
                            ),
                          ],
                        ),
                      ),
                      Text(formatUsd(r.amountMinor), style: const TextStyle(fontWeight: FontWeight.w600)),
                    ],
                  ),
                ),
              ),
          ],
        ],
      ],
    );
  }
}

class _Headline extends StatelessWidget {
  const _Headline(this.s);

  final SpendingSummary s;

  @override
  Widget build(BuildContext context) {
    final prev = s.previousSpentMinor;
    String? compare;
    if (prev != null && s.comparedFrom != null && s.comparedTo != null) {
      final period = _period(s.comparedFrom!, s.comparedTo!);
      final diff = s.spentMinor - prev;
      compare = diff == 0
          ? 'Autant que du $period'
          : '${formatUsd(diff.abs())} de ${diff > 0 ? 'plus' : 'moins'} que du $period (${formatUsd(prev)})';
      if (!period.contains('–')) compare = compare.replaceFirst('que du ', 'qu\'en ').replaceFirst('Autant que du ', 'Autant qu\'en ');
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          s.partial ? 'Dépensé depuis le 1er' : 'Dépensé ce mois-là',
          style: TextStyle(color: context.pal.muted, fontSize: 14),
        ),
        Text(
          formatUsd(s.spentMinor),
          key: const ValueKey('spent'),
          style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w700, letterSpacing: -0.5),
        ),
        if (compare != null) Text(compare, style: TextStyle(color: context.pal.muted, fontSize: 14)),
      ],
    );
  }
}

/// Une barre par catégorie, toutes de la même couleur : le libellé porte l'identité, la longueur le montant.
class _CategoryRow extends StatelessWidget {
  const _CategoryRow(this.c, {required this.max, required this.compared});

  final CategorySpending c;
  final int max;
  final bool compared;

  @override
  Widget build(BuildContext context) {
    final (label, icon) = _labels[c.category] ?? (c.category, Icons.circle_outlined);
    final dark = Theme.of(context).brightness == Brightness.dark;
    final share = max <= 0 ? 0.0 : (c.amountMinor / max).clamp(0.0, 1.0);
    final detail = [
      if (c.category != 'FEES' && c.count > 0) c.count == 1 ? '1 opération' : '${c.count} opérations',
      if (compared && c.previousMinor != null) 'avant : ${formatUsd(c.previousMinor!)}',
    ].join(' · ');
    return Padding(
      key: ValueKey('category-${c.category}'),
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 18, color: context.pal.muted),
              const SizedBox(width: 8),
              Expanded(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w600))),
              Text(formatUsd(c.amountMinor), style: const TextStyle(fontWeight: FontWeight.w600)),
            ],
          ),
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: Container(
              height: 8,
              color: context.pal.surfaceAlt,
              alignment: Alignment.centerLeft,
              child: FractionallySizedBox(
                widthFactor: share,
                child: Container(
                  decoration: BoxDecoration(
                    color: dark ? Brand.ochre : Brand.ochreDeep,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ),
            ),
          ),
          if (detail.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(detail, style: TextStyle(color: context.pal.muted, fontSize: 12.5)),
          ],
        ],
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat(this.label, this.minor, this.sub);

  final String label;
  final int minor;
  final String sub;

  @override
  Widget build(BuildContext context) => SoftCard(
    padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: TextStyle(color: context.pal.muted, fontSize: 13)),
        Text(formatUsd(minor), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
        Text(sub, style: TextStyle(color: context.pal.muted, fontSize: 12)),
      ],
    ),
  );
}
