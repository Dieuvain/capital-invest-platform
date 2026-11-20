import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Envoi à un numéro qui n'a pas encore de compte : le proche reçoit un SMS et l'argent
/// l'attend 7 jours. S'il n'ouvre pas son compte, l'argent revient, frais compris.
class InviteTransferScreen extends StatefulWidget {
  const InviteTransferScreen({super.key, required this.app, required this.phone, required this.quote, this.note});

  final AppState app;
  final String phone;
  final FeeQuote quote;
  final String? note;

  @override
  State<InviteTransferScreen> createState() => _InviteTransferScreenState();
}

class _InviteTransferScreenState extends State<InviteTransferScreen> with ApiAction {
  final name = TextEditingController();
  final pin = TextEditingController();
  final String _key = newIdempotencyKey();
  PendingTransfer? done;

  Future<void> _send() async {
    final n = name.text.trim();
    if (n.isEmpty) return setState(() => error = 'Donnez le nom de votre proche : il vous aide à suivre l\'envoi.');
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final r = await run(
      widget.app,
      () => widget.app.api.sendPendingTransfer(
        toPhone: widget.phone,
        recipientName: n,
        amountMinor: widget.quote.amountMinor,
        pin: pin.text,
        idempotencyKey: _key,
        note: widget.note,
      ),
    );
    pin.clear();
    if (r != null) setState(() => done = r);
  }

  @override
  Widget build(BuildContext context) {
    final d = done;
    if (d != null) {
      return FormPage(
        title: 'Envoyer',
        children: [
          const SizedBox(height: 24),
          const Icon(Icons.hourglass_top_rounded, size: 72, color: Brand.ochreDeep),
          const SizedBox(height: 16),
          Text(
            '${formatUsd(d.amountMinor)} en attente',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            '${d.recipientName} a reçu un SMS. L\'argent lui est versé dès qu\'il ouvre son compte Capital Invest '
            'avec le ${d.phone}. Sans compte le ${formatDay(d.expiresAt)}, il vous revient, frais compris.',
            key: const ValueKey('invite-done'),
            textAlign: TextAlign.center,
            style: TextStyle(color: context.pal.muted),
          ),
          const SizedBox(height: 24),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Terminé')),
        ],
      );
    }
    final q = widget.quote;
    return FormPage(
      title: 'Envoyer sans compte',
      subtitle: 'Votre proche reçoit un SMS. L\'argent l\'attend 7 jours, le temps d\'ouvrir son compte.',
      children: [
        ErrorBanner(error),
        SoftCard(
          child: Column(
            children: [
              _Line('Numéro', widget.phone),
              _Line('Montant', formatUsd(q.amountMinor)),
              _Line('Frais', formatUsd(q.feeMinor)),
              Divider(height: 28, color: context.pal.divider),
              _Line('Total débité', formatUsd(q.totalMinor), strong: true),
            ],
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          key: const ValueKey('invite-name'),
          controller: name,
          maxLength: 40,
          textCapitalization: TextCapitalization.words,
          decoration: const InputDecoration(labelText: 'Nom de votre proche', hintText: 'Maman Chantal'),
        ),
        Text(
          'Pas encore reçu dans 7 jours ? L\'argent vous revient, frais compris. Vous pouvez aussi l\'annuler avant.',
          style: TextStyle(color: context.pal.muted, fontSize: 13),
        ),
        const SizedBox(height: 16),
        PinField(controller: pin, label: 'PIN pour confirmer', onSubmitted: (_) => _send()),
        const SizedBox(height: 20),
        BusyButton(label: 'Envoyer ${formatUsd(q.amountMinor)}', busy: busy, onPressed: _send),
      ],
    );
  }
}

/// Envois à des proches sans compte : en attente, reçus, annulés ou remboursés.
class PendingTransfersScreen extends StatefulWidget {
  const PendingTransfersScreen({super.key, required this.app});

  final AppState app;

  @override
  State<PendingTransfersScreen> createState() => _PendingTransfersScreenState();
}

class _PendingTransfersScreenState extends State<PendingTransfersScreen> with ApiAction {
  List<PendingTransfer>? items;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, () => widget.app.api.pendingTransfers());
    if (l != null) setState(() => items = l);
  }

  Future<void> _cancel(PendingTransfer t) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Annuler l\'envoi ?'),
        content: Text('${formatUsd(t.amountMinor + t.feeMinor)} reviennent sur votre solde, frais compris.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Non')),
          FilledButton(onPressed: () => Navigator.of(c).pop(true), child: const Text('Annuler l\'envoi')),
        ],
      ),
    );
    if (ok != true) return;
    final r = await run(widget.app, () => widget.app.api.cancelPendingTransfer(t.id));
    if (r != null) {
      setState(() => items = [for (final x in items!) x.id == r.id ? r : x]);
    }
  }

  String _state(PendingTransfer t) => switch (t.status) {
    'PENDING' => 'Attend son inscription jusqu\'au ${formatDay(t.expiresAt)}',
    'CLAIMED' => 'Reçu ${t.settledAt == null ? '' : formatWhen(t.settledAt!)}'.trim(),
    'CANCELLED' => 'Annulé, remboursé',
    _ => 'Non réclamé, remboursé',
  };

  @override
  Widget build(BuildContext context) {
    final list = items;
    return FormPage(
      title: 'Envois en attente',
      subtitle: 'À des proches qui n\'ont pas encore de compte.',
      children: [
        ErrorBanner(error),
        if (list == null && error == null) const Center(child: CircularProgressIndicator()),
        if (list != null && list.isEmpty) Text('Aucun envoi.', style: TextStyle(color: context.pal.muted)),
        for (final t in list ?? const <PendingTransfer>[])
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: SoftCard(
              key: ValueKey('pending-${t.id}'),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        t.isPending ? Icons.hourglass_top_rounded : (t.status == 'CLAIMED' ? Icons.check_circle : Icons.undo_rounded),
                        color: t.isPending ? Brand.ochreDeep : (t.status == 'CLAIMED' ? Brand.success : context.pal.muted),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(t.recipientName, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                            Text(t.phone, style: TextStyle(color: context.pal.muted, fontSize: 13)),
                          ],
                        ),
                      ),
                      Text(formatUsd(t.amountMinor), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(_state(t), style: TextStyle(color: context.pal.muted, fontSize: 13)),
                  if (t.isPending)
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton(
                        key: ValueKey('cancel-pending-${t.id}'),
                        onPressed: busy ? null : () => _cancel(t),
                        child: const Text('Annuler et reprendre l\'argent'),
                      ),
                    ),
                ],
              ),
            ),
          ),
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
        Expanded(child: Text(label, style: TextStyle(color: strong ? context.pal.text : context.pal.muted, fontSize: 16))),
        Text(value, style: TextStyle(fontSize: strong ? 20 : 16, fontWeight: strong ? FontWeight.w800 : FontWeight.w600)),
      ],
    ),
  );
}
