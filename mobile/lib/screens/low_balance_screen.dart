import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Alerte de solde bas : un SMS quand le solde passe sous le seuil choisi, une fois, puis de nouveau
/// seulement après être repassé au-dessus.
class LowBalanceScreen extends StatefulWidget {
  const LowBalanceScreen({super.key, required this.app});

  final AppState app;

  @override
  State<LowBalanceScreen> createState() => _LowBalanceScreenState();
}

class _LowBalanceScreenState extends State<LowBalanceScreen> with ApiAction {
  final amount = TextEditingController();
  bool loaded = false;
  bool off = true;
  String? done;

  static const _presets = [500, 1000, 2000, 5000];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final t = await run(widget.app, widget.app.api.lowBalanceThreshold);
    if (!mounted || error != null) return;
    setState(() {
      loaded = true;
      off = t == null;
      amount.text = t == null ? '' : _plain(t);
    });
  }

  /// « 20 » pour 2000, « 7,50 » pour 750 : ce que le client taperait.
  String _plain(int minor) => minor % 100 == 0 ? '${minor ~/ 100}' : formatUsd(minor).replaceAll(RegExp(r'[^0-9,]'), '');

  Future<void> _save() async {
    final minor = off ? null : parseUsd(amount.text);
    if (!off && minor == null) return setState(() => error = 'Choisissez un montant.');
    final saved = await run(widget.app, () async {
      await widget.app.api.setLowBalanceThreshold(minor);
      return true;
    });
    if (saved == true && mounted) {
      setState(() => done = minor == null ? 'Alerte désactivée.' : 'C\'est noté : un SMS vous préviendra sous ${formatUsd(minor)}.');
    }
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Alerte de solde bas',
    subtitle: 'Un SMS quand votre solde passe sous le montant choisi, pour recharger à temps. Une seule fois, pas à chaque achat.',
    children: [
      ErrorBanner(error),
      if (done != null)
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Row(
            children: [
              const Icon(Icons.check_circle, color: Brand.success, size: 20),
              const SizedBox(width: 8),
              Expanded(child: Text(done!, style: const TextStyle(fontWeight: FontWeight.w600))),
            ],
          ),
        ),
      if (loaded) ...[
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final p in _presets)
              ChoiceChip(
                key: ValueKey('threshold-$p'),
                label: Text(formatUsd(p)),
                selected: !off && parseUsd(amount.text) == p,
                onSelected: (_) => setState(() {
                  off = false;
                  amount.text = _plain(p);
                  done = null;
                }),
              ),
            ChoiceChip(
              key: const ValueKey('threshold-off'),
              label: const Text('Pas d\'alerte'),
              selected: off,
              onSelected: (_) => setState(() {
                off = true;
                amount.clear();
                done = null;
              }),
            ),
          ],
        ),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('amount'),
          controller: amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Autre montant', suffixText: '\$'),
          onChanged: (v) => setState(() {
            off = v.trim().isEmpty;
            done = null;
          }),
        ),
        const SizedBox(height: 20),
        BusyButton(label: 'Enregistrer', busy: busy, onPressed: _save),
      ],
    ],
  );
}
