import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Plafond d'envoi par jour choisi par le client, sous celui de son niveau.
/// Baisser est immédiat ; remonter attend 24 h, pour qu'un voleur qui connaît le PIN ne vide pas le compte.
class DailyLimitScreen extends StatefulWidget {
  const DailyLimitScreen({super.key, required this.app});

  final AppState app;

  @override
  State<DailyLimitScreen> createState() => _DailyLimitScreenState();
}

class _DailyLimitScreenState extends State<DailyLimitScreen> with ApiAction {
  final amount = TextEditingController();
  final pin = TextEditingController();
  Limits? limits;

  /// Montant choisi ; null avec [level] vrai : le plafond du niveau.
  bool level = false;
  String? done;

  static const _presets = [2000, 5000, 10000, 20000];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, widget.app.api.limits);
    if (l != null && mounted) setState(() => limits = l);
  }

  void _pick(int? minor) => setState(() {
    level = minor == null;
    amount.text = minor == null ? '' : (minor ~/ 100).toString();
  });

  Future<void> _save() async {
    final l = limits;
    if (l == null) return;
    final minor = level ? null : parseUsd(amount.text);
    if (!level && minor == null) return setState(() => error = 'Choisissez un montant.');
    if (pin.text.length != 4) return setState(() => error = 'Entrez votre PIN pour confirmer.');
    final r = await run(widget.app, () => widget.app.api.setDailyLimit(minor, pin.text));
    pin.clear();
    if (r == null || !mounted) return;
    setState(() {
      limits = r;
      done = r.pendingAt != null
          ? 'Hausse enregistrée : elle prendra effet ${formatWhen(r.pendingAt!)}. Un SMS vous a été envoyé.'
          : 'Plafond changé : ${formatUsd(r.dailyOutMinor)} par jour, dès maintenant.';
    });
  }

  Future<void> _cancel() async {
    final r = await run(widget.app, widget.app.api.cancelDailyLimitRaise);
    if (r != null && mounted) {
      setState(() {
        limits = r;
        done = 'Hausse annulée. Votre plafond reste à ${formatUsd(r.dailyOutMinor)} par jour.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = limits;
    return FormPage(
      title: 'Plafond d\'envoi par jour',
      subtitle:
          'Ce que vous pouvez envoyer, retirer et payer chaque jour. Le baisser est immédiat ; le remonter prend effet 24 h plus tard, pour qu\'un voleur ne puisse pas vider votre compte.',
      children: [
        ErrorBanner(error),
        if (done != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.check_circle, color: Brand.success, size: 20),
                const SizedBox(width: 8),
                Expanded(child: Text(done!, style: const TextStyle(fontWeight: FontWeight.w600))),
              ],
            ),
          ),
        if (l != null) ...[
          SoftCard(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Aujourd\'hui', style: TextStyle(color: context.pal.muted, fontSize: 13)),
                Text(
                  '${formatUsd(l.dailyOutMinor)} par jour',
                  key: const ValueKey('daily-limit'),
                  style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
                ),
                Text(
                  l.personalDailyOutMinor == null
                      ? 'Le plafond de votre niveau ${l.kycLevel}.'
                      : 'Choisi par vous. Plafond de votre niveau : ${formatUsd(l.levelDailyOutMinor)}.',
                  style: TextStyle(color: context.pal.muted, fontSize: 13),
                ),
                if (l.pendingAt != null) ...[
                  const SizedBox(height: 10),
                  Text(
                    'Passera à ${formatUsd(l.pendingDailyOutMinor ?? l.levelDailyOutMinor)} ${formatWhen(l.pendingAt!)}.',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  Text(
                    'Ce n\'est pas vous ? Annulez la hausse, puis bloquez votre compte.',
                    style: TextStyle(color: context.pal.muted, fontSize: 13),
                  ),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton(
                      key: const ValueKey('cancel-raise'),
                      onPressed: busy ? null : _cancel,
                      child: const Text('Annuler la hausse'),
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 20),
          const Text('Nouveau plafond', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final p in _presets.where((p) => p < l.levelDailyOutMinor))
                ChoiceChip(
                  key: ValueKey('preset-$p'),
                  label: Text(formatUsd(p)),
                  selected: !level && parseUsd(amount.text) == p,
                  onSelected: (_) => _pick(p),
                ),
              ChoiceChip(
                key: const ValueKey('preset-level'),
                label: Text('Niveau ${l.kycLevel} (${formatUsd(l.levelDailyOutMinor)})'),
                selected: level,
                onSelected: (_) => _pick(null),
              ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('amount'),
            controller: amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Autre montant', suffixText: '\$'),
            onChanged: (_) => setState(() => level = false),
          ),
          const SizedBox(height: 12),
          PinField(controller: pin, label: 'PIN pour confirmer', onSubmitted: (_) => _save()),
          const SizedBox(height: 20),
          BusyButton(label: 'Enregistrer', busy: busy, onPressed: _save),
        ],
      ],
    );
  }
}
