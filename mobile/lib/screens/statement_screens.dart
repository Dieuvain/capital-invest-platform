import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'history_screens.dart';

const _months = [
  'janvier',
  'février',
  'mars',
  'avril',
  'mai',
  'juin',
  'juillet',
  'août',
  'septembre',
  'octobre',
  'novembre',
  'décembre',
];

/// « Novembre 2026 » pour « 2026-11 ».
String monthTitle(String month) {
  final parts = month.split('-');
  final name = _months[int.parse(parts[1]) - 1];
  return '${name[0].toUpperCase()}${name.substring(1)} ${parts[0]}';
}

/// « 1er novembre », « 19 novembre ».
String _dayTitle(DateTime d) => '${d.day == 1 ? '1er' : d.day} ${_months[d.month - 1]}';

/// Relevés de compte : un par mois depuis l'ouverture du compte.
class StatementsScreen extends StatefulWidget {
  const StatementsScreen({super.key, required this.app});

  final AppState app;

  @override
  State<StatementsScreen> createState() => _StatementsScreenState();
}

class _StatementsScreenState extends State<StatementsScreen> with ApiAction {
  List<StatementMonth>? months;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, widget.app.api.statementMonths);
    if (l != null && mounted) setState(() => months = l);
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Relevés de compte',
    subtitle: 'Toutes vos opérations du mois, avec le solde au début et à la fin. À garder ou à montrer à un bailleur, une banque.',
    children: [
      ErrorBanner(error),
      for (final m in months ?? const <StatementMonth>[])
        Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: SoftCard(
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => StatementScreen(app: widget.app, month: m.month),
              ),
            ),
            padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
            child: Row(
              children: [
                const IconTile(Icons.receipt_long_rounded, size: 42),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(monthTitle(m.month), style: const TextStyle(fontWeight: FontWeight.w600)),
                      Text(
                        m.partial ? 'Mois en cours, jusqu\'à aujourd\'hui' : 'Mois complet',
                        style: TextStyle(color: context.pal.muted, fontSize: 13),
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right_rounded),
              ],
            ),
          ),
        ),
    ],
  );
}

/// Relevé d'un mois : résumé, opérations, et le PDF à partager.
class StatementScreen extends StatefulWidget {
  const StatementScreen({super.key, required this.app, required this.month});

  final AppState app;
  final String month;

  @override
  State<StatementScreen> createState() => _StatementScreenState();
}

class _StatementScreenState extends State<StatementScreen> with ApiAction {
  Statement? statement;
  bool downloading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = await run(widget.app, () => widget.app.api.statement(widget.month));
    if (s != null && mounted) setState(() => statement = s);
  }

  Future<void> _pdf() async {
    setState(() => downloading = true);
    try {
      final bytes = await run(widget.app, () => widget.app.api.statementPdf(widget.month));
      if (bytes != null)
        await widget.app.shareFile(bytes, 'releve-capital-invest-${widget.month}.pdf', 'application/pdf');
    } finally {
      if (mounted) setState(() => downloading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = statement;
    return FormPage(
      title: monthTitle(widget.month),
      subtitle: s == null
          ? null
          : 'Du ${_dayTitle(s.from)} au ${_dayTitle(s.to)}${s.partial ? ', arrêté à aujourd\'hui' : ''}',
      children: [
        ErrorBanner(error),
        if (s != null) ...[
          SoftCard(
            child: Column(
              children: [
                _Row('Solde au ${_dayTitle(s.from)}', formatUsd(s.openingBalanceMinor)),
                _Row('Entrées', '+${formatUsd(s.totalInMinor)}', color: Brand.success),
                _Row('Sorties', formatUsd(-s.totalOutMinor)),
                if (s.feesMinor > 0) _Row('dont frais', formatUsd(s.feesMinor), muted: true),
                Divider(height: 28, color: context.pal.divider),
                _Row('Solde au ${_dayTitle(s.to)}', formatUsd(s.closingBalanceMinor), strong: true),
              ],
            ),
          ),
          const SizedBox(height: 16),
          BusyButton(
            key: const ValueKey('statement-pdf'),
            label: 'Télécharger le relevé (PDF)',
            busy: downloading,
            onPressed: _pdf,
          ),
          const SizedBox(height: 24),
          Text(
            s.items.isEmpty
                ? 'Aucune opération ce mois-ci.'
                : '${s.items.length} opération${s.items.length > 1 ? 's' : ''}',
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          if (s.items.isNotEmpty)
            SoftCard(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
              child: Column(
                children: [
                  for (final i in s.items.reversed)
                    HistoryTile(
                      i,
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => ReceiptScreen(app: widget.app, id: i.id),
                        ),
                      ),
                    ),
                ],
              ),
            ),
        ],
      ],
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {this.strong = false, this.muted = false, this.color});

  final String label;
  final String value;
  final bool strong;
  final bool muted;
  final Color? color;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 5),
    child: Row(
      children: [
        Expanded(
          child: Text(
            label,
            style: TextStyle(color: context.pal.muted, fontSize: muted ? 13 : 14),
          ),
        ),
        Text(
          value,
          style: TextStyle(
            fontWeight: strong ? FontWeight.w800 : (muted ? FontWeight.w500 : FontWeight.w700),
            fontSize: strong ? 18 : (muted ? 13 : 15),
            color: muted ? context.pal.muted : color,
          ),
        ),
      ],
    ),
  );
}
