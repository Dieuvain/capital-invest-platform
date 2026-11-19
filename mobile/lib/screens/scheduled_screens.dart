import 'package:material_ui/material_ui.dart';

import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

const _weekdays = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'];

/// « Chaque mois, le 5 », « Chaque lundi ».
String scheduleLabel(String frequency, int day) => frequency == 'WEEKLY'
    ? 'Chaque ${_weekdays[day - 1]}'
    : day == 1
    ? 'Chaque mois, le 1er'
    : day == 31
    ? 'Chaque mois, le dernier jour'
    : 'Chaque mois, le $day';

/// Liste des envois programmés : prochaine date, dernier résultat, pause et suppression.
class ScheduledTransfersScreen extends StatefulWidget {
  const ScheduledTransfersScreen({super.key, required this.app});

  final AppState app;

  @override
  State<ScheduledTransfersScreen> createState() => _ScheduledTransfersScreenState();
}

class _ScheduledTransfersScreenState extends State<ScheduledTransfersScreen> with ApiAction {
  List<ScheduledTransfer>? items;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, widget.app.api.scheduledTransfers);
    if (l != null && mounted) setState(() => items = l);
  }

  Future<void> _new() async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => NewScheduledTransferScreen(app: widget.app)));
    if (mounted) await _load();
  }

  Future<void> _toggle(ScheduledTransfer s) async {
    final api = widget.app.api;
    final r = await run(widget.app, () => s.active ? api.pauseScheduledTransfer(s.id) : api.resumeScheduledTransfer(s.id));
    if (r != null) await _load();
  }

  Future<void> _cancel(ScheduledTransfer s) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Supprimer cet envoi programmé ?'),
        content: Text('${formatUsd(s.amountMinor)} à ${s.recipientName} ne partiront plus. Les envois déjà faits restent dans l\'historique.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Annuler')),
          FilledButton(onPressed: () => Navigator.of(c).pop(true), child: const Text('Supprimer')),
        ],
      ),
    );
    if (ok != true) return;
    await run(widget.app, () => widget.app.api.cancelScheduledTransfer(s.id));
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final list = items;
    return FormPage(
      title: 'Envois programmés',
      subtitle: 'Loyer, argent pour la famille : l\'envoi part tout seul à 8 h, avec les frais d\'un envoi normal.',
      children: [
        ErrorBanner(error),
        if (list == null && error == null) const Center(child: CircularProgressIndicator()),
        if (list != null && list.isEmpty)
          SoftCard(
            padding: const EdgeInsets.all(20),
            child: Text('Aucun envoi programmé.', style: TextStyle(color: context.pal.muted)),
          ),
        for (final s in list ?? const <ScheduledTransfer>[])
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: SoftCard(
              key: ValueKey('scheduled-${s.id}'),
              padding: const EdgeInsets.fromLTRB(16, 14, 8, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      IconTile(s.active ? Icons.event_repeat_rounded : Icons.pause_circle_outline_rounded, size: 42, accent: s.active),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '${formatUsd(s.amountMinor)} à ${s.recipientName}',
                              style: const TextStyle(fontWeight: FontWeight.w700),
                            ),
                            Text(
                              [scheduleLabel(s.frequency, s.day), ?s.note].join(' · '),
                              style: TextStyle(color: context.pal.muted, fontSize: 13),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    s.active ? 'Prochain envoi le ${formatDay(s.nextRunAt!)}' : 'En pause',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  if (s.lastResult == 'FAILED')
                    Text(
                      'Dernier envoi non effectué : ${s.lastFailure}.',
                      style: const TextStyle(color: Brand.danger, fontSize: 13),
                    )
                  else if (s.lastRunAt != null)
                    Text(
                      'Dernier envoi le ${formatDay(s.lastRunAt!)}',
                      style: TextStyle(color: context.pal.muted, fontSize: 13),
                    ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      TextButton(onPressed: () => _cancel(s), child: const Text('Supprimer')),
                      TextButton(onPressed: () => _toggle(s), child: Text(s.active ? 'Mettre en pause' : 'Reprendre')),
                    ],
                  ),
                ],
              ),
            ),
          ),
        const SizedBox(height: 8),
        FilledButton.icon(
          onPressed: _new,
          icon: const Icon(Icons.add_rounded),
          label: const Text('Programmer un envoi'),
        ),
      ],
    );
  }
}

/// Programmer : destinataire, montant, rythme, jour ; puis vérification des frais et PIN.
class NewScheduledTransferScreen extends StatefulWidget {
  const NewScheduledTransferScreen({super.key, required this.app, this.toPhone, this.amountMinor, this.note});

  final AppState app;
  final String? toPhone;
  final int? amountMinor;
  final String? note;

  @override
  State<NewScheduledTransferScreen> createState() => _NewScheduledTransferScreenState();
}

class _NewScheduledTransferScreenState extends State<NewScheduledTransferScreen> with ApiAction {
  late final phone = TextEditingController(text: widget.toPhone);
  late final amount = TextEditingController(
    text: widget.amountMinor == null ? null : formatUsd(widget.amountMinor!).replaceAll(RegExp(r'[^0-9,]'), ''),
  );
  late final note = TextEditingController(text: widget.note);
  final pin = TextEditingController();
  String frequency = 'MONTHLY';
  int day = DateTime.now().day.clamp(1, 28);
  FeeQuote? quote;
  String? toPhone;
  Recipient? recipient;
  ScheduledTransfer? done;

  Future<void> _review() async {
    final p = normalizePhone(phone.text);
    if (p == null) return setState(() => error = 'Numéro congolais attendu (9 chiffres après +243).');
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 25 ou 12,50.');
    final who = await run(widget.app, () => widget.app.api.lookupRecipient(p));
    if (who == null) return;
    final q = await run(widget.app, () => widget.app.api.transferFee(minor));
    if (q != null) {
      setState(() {
        toPhone = p;
        recipient = who;
        quote = q;
      });
    }
  }

  Future<void> _confirm() async {
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final s = await run(
      widget.app,
      () => widget.app.api.scheduleTransfer(
        toPhone: toPhone!,
        amountMinor: quote!.amountMinor,
        frequency: frequency,
        day: day,
        pin: pin.text,
        note: note.text.trim(),
      ),
    );
    pin.clear();
    if (s != null && mounted) setState(() => done = s);
  }

  @override
  Widget build(BuildContext context) {
    final d = done;
    if (d != null) {
      return FormPage(
        title: 'Envoi programmé',
        children: [
          const SizedBox(height: 24),
          const Icon(Icons.event_available_rounded, size: 72, color: Brand.success),
          const SizedBox(height: 16),
          Text(
            '${formatUsd(d.amountMinor)} à ${d.recipientName}',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            '${scheduleLabel(d.frequency, d.day)}, à 8 h. Premier envoi le ${formatDay(d.nextRunAt!)}. '
            'Si votre solde ne suffit pas, vous êtes prévenu par SMS.',
            key: const ValueKey('scheduled-first-run'),
            textAlign: TextAlign.center,
            style: TextStyle(color: context.pal.muted, fontSize: 15),
          ),
          const SizedBox(height: 24),
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
        ],
      );
    }
    final q = quote;
    if (q != null) {
      return FormPage(
        title: 'Vérifier l\'envoi programmé',
        children: [
          ErrorBanner(error),
          SoftCard(
            child: Column(
              children: [
                _Line('Destinataire', recipient!.displayName),
                _Line('Numéro', toPhone!),
                _Line('Rythme', scheduleLabel(frequency, day)),
                _Line('Montant reçu', formatUsd(q.amountMinor)),
                _Line('Frais à chaque envoi', formatUsd(q.feeMinor)),
                Divider(height: 28, color: context.pal.divider),
                _Line('Débité à chaque envoi', formatUsd(q.totalMinor), strong: true),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Text(
            'Votre PIN autorise tous les envois à venir. Vous pouvez les mettre en pause ou les supprimer à tout moment.',
            style: TextStyle(color: context.pal.muted, fontSize: 13),
          ),
          const SizedBox(height: 16),
          PinField(controller: pin, label: 'PIN pour confirmer', autofocus: true, onSubmitted: (_) => _confirm()),
          const SizedBox(height: 20),
          BusyButton(label: 'Programmer', busy: busy, onPressed: _confirm),
          TextButton(onPressed: () => setState(() => quote = null), child: const Text('Modifier')),
        ],
      );
    }
    return FormPage(
      title: 'Programmer un envoi',
      subtitle: 'L\'argent part tout seul, à 8 h, au rythme que vous choisissez.',
      children: [
        ErrorBanner(error),
        PhoneField(controller: phone, label: 'Numéro du destinataire'),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('amount'),
          controller: amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$'),
        ),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('scheduled-note'),
          controller: note,
          maxLength: 140,
          decoration: const InputDecoration(labelText: 'Motif (facultatif)', hintText: 'Ex. : Loyer'),
        ),
        const SizedBox(height: 4),
        Wrap(
          spacing: 8,
          children: [
            for (final (f, label) in [('MONTHLY', 'Chaque mois'), ('WEEKLY', 'Chaque semaine')])
              ChoiceChip(
                label: Text(label),
                selected: frequency == f,
                onSelected: (_) => setState(() {
                  frequency = f;
                  day = f == 'WEEKLY' ? DateTime.now().weekday : DateTime.now().day.clamp(1, 28);
                }),
              ),
          ],
        ),
        const SizedBox(height: 12),
        if (frequency == 'WEEKLY')
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (var i = 1; i <= 7; i++)
                ChoiceChip(
                  key: ValueKey('weekday-$i'),
                  label: Text(_weekdays[i - 1].substring(0, 3)),
                  selected: day == i,
                  onSelected: (_) => setState(() => day = i),
                ),
            ],
          )
        else
          DropdownButtonFormField<int>(
            key: const ValueKey('month-day'),
            initialValue: day,
            decoration: const InputDecoration(labelText: 'Jour du mois'),
            items: [
              for (var i = 1; i <= 31; i++)
                DropdownMenuItem(value: i, child: Text(i == 31 ? 'Dernier jour du mois' : i == 1 ? '1er' : '$i')),
            ],
            onChanged: (v) => setState(() => day = v ?? day),
          ),
        const SizedBox(height: 16),
        BusyButton(label: 'Continuer', busy: busy, onPressed: _review),
      ],
    );
  }
}

class _Line extends StatelessWidget {
  const _Line(this.label, this.value, {this.strong = false});

  final String label;
  final String value;
  final bool strong;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      children: [
        Expanded(
          child: Text(label, style: TextStyle(color: strong ? context.pal.text : context.pal.muted, fontSize: 16)),
        ),
        Text(value, style: TextStyle(fontSize: strong ? 20 : 16, fontWeight: strong ? FontWeight.w800 : FontWeight.w600)),
      ],
    ),
  );
}
