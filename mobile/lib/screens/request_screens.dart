import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'history_screens.dart';

const _statusLabel = {
  'PENDING': 'En attente',
  'PAID': 'Payée',
  'DECLINED': 'Refusée',
  'CANCELLED': 'Annulée',
  'EXPIRED': 'Expirée',
};

/// Demandes d'argent : celles à payer en haut, puis celles que le client a faites et leurs réponses.
class MoneyRequestsScreen extends StatefulWidget {
  const MoneyRequestsScreen({super.key, required this.app, this.limits});

  final AppState app;
  final Limits? limits;

  @override
  State<MoneyRequestsScreen> createState() => _MoneyRequestsScreenState();
}

class _MoneyRequestsScreenState extends State<MoneyRequestsScreen> with ApiAction {
  MoneyRequestList? list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final l = await run(widget.app, widget.app.api.moneyRequests);
    if (l != null && mounted) setState(() => list = l);
  }

  Future<void> _push(Widget screen) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => screen));
    if (mounted && widget.app.signedIn) await _load();
  }

  Future<void> _cancel(MoneyRequest r) async {
    final ok = await run(widget.app, () => widget.app.api.cancelMoneyRequest(r.id));
    if (ok != null) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final l = list;
    return FormPage(
      title: 'Demandes d\'argent',
      subtitle: 'Demandez un montant à un autre client : il paie depuis son app, avec son PIN.',
      children: [
        ErrorBanner(error),
        FilledButton.icon(
          key: const ValueKey('new-request'),
          onPressed: () => _push(RequestMoneyScreen(app: widget.app)),
          icon: const Icon(Icons.call_received_rounded),
          label: const Text('Demander de l\'argent'),
        ),
        if (l != null && l.toPay.isNotEmpty) ...[
          const SizedBox(height: 24),
          const _Title('À payer'),
          for (final r in l.toPay)
            _RequestTile(r, onTap: () => _push(PayRequestScreen(app: widget.app, request: r, limits: widget.limits))),
        ],
        const SizedBox(height: 24),
        const _Title('Récentes'),
        if (l != null && l.recent.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text('Aucune demande pour l\'instant.', style: TextStyle(color: context.pal.muted)),
          ),
        for (final r in l?.recent ?? const <MoneyRequest>[])
          _RequestTile(
            r,
            onTap: r.transactionId == null
                ? null
                : () => _push(ReceiptScreen(app: widget.app, id: r.transactionId!)),
            trailing: !r.incoming && r.pending
                ? TextButton(onPressed: busy ? null : () => _cancel(r), child: const Text('Annuler'))
                : null,
          ),
      ],
    );
  }
}

class _Title extends StatelessWidget {
  const _Title(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Text(text, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
  );
}

class _RequestTile extends StatelessWidget {
  const _RequestTile(this.r, {this.onTap, this.trailing});

  final MoneyRequest r;
  final VoidCallback? onTap;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final who = r.incoming ? '${r.counterpartyName} vous demande' : 'Demandé à ${r.counterpartyName}';
    final when = r.pending ? 'expire le ${formatDateTime(r.expiresAt).split(' · ').last.replaceFirst(RegExp(r' \d{4}$'), '')}' : formatDateTime(r.createdAt);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: SoftCard(
        onTap: onTap,
        padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
        child: Row(
          children: [
            IconTile(r.incoming ? Icons.call_made_rounded : Icons.call_received_rounded, size: 42, accent: r.incoming && r.pending),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(who, style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text(
                    [if (r.note != null) r.note!, '${_statusLabel[r.status] ?? r.status} · $when'].join('\n'),
                    style: TextStyle(color: context.pal.muted, fontSize: 13),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(formatUsd(r.amountMinor), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                ?trailing,
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Demander un montant à un autre client, avec son numéro.
class RequestMoneyScreen extends StatefulWidget {
  const RequestMoneyScreen({super.key, required this.app});

  final AppState app;

  @override
  State<RequestMoneyScreen> createState() => _RequestMoneyScreenState();
}

class _RequestMoneyScreenState extends State<RequestMoneyScreen> with ApiAction {
  final phone = TextEditingController();
  final amount = TextEditingController();
  final note = TextEditingController();
  MoneyRequest? done;

  /// Gardée tant que le formulaire ne change pas : un nouvel essai après une coupure ne crée pas deux demandes.
  String _key = newIdempotencyKey();
  String _keyFor = '';

  Future<void> _send() async {
    final p = normalizePhone(phone.text);
    if (p == null) return setState(() => error = 'Numéro congolais attendu (9 chiffres après +243).');
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 25 ou 12,50.');
    final form = '$p|$minor|${note.text.trim()}';
    if (form != _keyFor) {
      _key = newIdempotencyKey();
      _keyFor = form;
    }
    final r = await run(
      widget.app,
      () => widget.app.api.requestMoney(payerPhone: p, amountMinor: minor, idempotencyKey: _key, note: note.text.trim()),
    );
    if (r != null) setState(() => done = r);
  }

  @override
  Widget build(BuildContext context) {
    final d = done;
    if (d != null) {
      return FormPage(
        title: 'Demande envoyée',
        children: [
          const SizedBox(height: 24),
          const Icon(Icons.check_circle, size: 72, color: Brand.success),
          const SizedBox(height: 16),
          Text(
            '${formatUsd(d.amountMinor)} demandés',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            'à ${d.counterpartyName} (${d.counterpartyPhone}). Il ou elle reçoit un SMS et paie depuis son app. '
            'Vous serez prévenu dès que l\'argent arrive.',
            textAlign: TextAlign.center,
            style: TextStyle(color: context.pal.muted),
          ),
          const SizedBox(height: 24),
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
        ],
      );
    }
    return FormPage(
      title: 'Demander',
      subtitle: 'À un autre client Capital Invest. Il paie les frais d\'envoi habituels.',
      children: [
        ErrorBanner(error),
        PhoneField(controller: phone, label: 'Numéro du client'),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('amount'),
          controller: amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Montant demandé', suffixText: '\$'),
        ),
        const SizedBox(height: 12),
        TextField(
          key: const ValueKey('note'),
          controller: note,
          maxLength: 140,
          decoration: const InputDecoration(labelText: 'Motif', hintText: 'Loyer, part du taxi…'),
        ),
        const SizedBox(height: 12),
        BusyButton(label: 'Envoyer la demande', busy: busy, onPressed: _send),
      ],
    );
  }
}

/// Une demande reçue : qui demande, combien, pourquoi ; payer avec le PIN, ou refuser.
class PayRequestScreen extends StatefulWidget {
  const PayRequestScreen({super.key, required this.app, required this.request, this.limits});

  final AppState app;
  final MoneyRequest request;
  final Limits? limits;

  @override
  State<PayRequestScreen> createState() => _PayRequestScreenState();
}

class _PayRequestScreenState extends State<PayRequestScreen> with ApiAction {
  final pin = TextEditingController();
  FeeQuote? quote;
  TransferResult? paid;
  bool declined = false;

  @override
  void initState() {
    super.initState();
    _quote();
  }

  Future<void> _quote() async {
    final q = await run(widget.app, () => widget.app.api.transferFee(widget.request.amountMinor));
    if (q != null && mounted) setState(() => quote = q);
  }

  Future<void> _pay() async {
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final r = await run(widget.app, () => widget.app.api.payMoneyRequest(widget.request.id, pin.text));
    pin.clear();
    if (r != null) setState(() => paid = r);
  }

  Future<void> _decline() async {
    final r = await run(widget.app, () => widget.app.api.declineMoneyRequest(widget.request.id));
    if (r != null && mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.request;
    final p = paid;
    if (p != null) {
      return FormPage(
        title: 'Demande payée',
        children: [
          const SizedBox(height: 24),
          const Icon(Icons.check_circle, size: 72, color: Brand.success),
          const SizedBox(height: 16),
          Text(
            '${formatUsd(p.amountMinor)} envoyés',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            'à ${r.counterpartyName}. Frais : ${formatUsd(p.feeMinor)}.',
            textAlign: TextAlign.center,
            style: TextStyle(color: context.pal.muted),
          ),
          const SizedBox(height: 24),
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
        ],
      );
    }
    final q = quote;
    return FormPage(
      title: 'Payer la demande',
      children: [
        ErrorBanner(error),
        SoftCard(
          child: Column(
            children: [
              _Row('Demandé par', r.counterpartyName),
              _Row('Numéro', r.counterpartyPhone),
              if (r.note != null) _Row('Motif', r.note!),
              _Row('Montant', formatUsd(r.amountMinor)),
              _Row('Frais', q == null ? '…' : formatUsd(q.feeMinor)),
              Divider(height: 28, color: context.pal.divider),
              _Row('Total débité', q == null ? '…' : formatUsd(q.totalMinor), strong: true),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.info_outline_rounded, size: 18, color: context.pal.muted),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Ne payez que si vous connaissez cette personne. Capital Invest ne vous demandera jamais d\'argent.',
                style: TextStyle(color: context.pal.muted, fontSize: 13),
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        PinField(controller: pin, label: 'PIN pour confirmer', onSubmitted: (_) => _pay()),
        const SizedBox(height: 20),
        BusyButton(label: 'Payer ${formatUsd(r.amountMinor)}', busy: busy, onPressed: _pay),
        TextButton(onPressed: busy ? null : _decline, child: const Text('Refuser la demande')),
      ],
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {this.strong = false});

  final String label;
  final String value;
  final bool strong;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: TextStyle(color: strong ? context.pal.text : context.pal.muted, fontSize: 16)),
        const SizedBox(width: 16),
        Expanded(
          child: Text(
            value,
            textAlign: TextAlign.right,
            style: TextStyle(fontSize: strong ? 20 : 16, fontWeight: strong ? FontWeight.w800 : FontWeight.w600),
          ),
        ),
      ],
    ),
  );
}

/// Bandeau de l'accueil : la plus ancienne demande à payer, et combien d'autres attendent.
class RequestsBanner extends StatelessWidget {
  const RequestsBanner({super.key, required this.toPay, required this.onTap});

  final List<MoneyRequest> toPay;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final first = toPay.first;
    final more = toPay.length - 1;
    return SoftCard(
      key: const ValueKey('requests-banner'),
      onTap: onTap,
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      child: Row(
        children: [
          const IconTile(Icons.call_made_rounded, size: 42, accent: true),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${first.counterpartyName.split(' ').first} vous demande ${formatUsd(first.amountMinor)}',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                Text(
                  more > 0 ? 'et $more autre${more > 1 ? 's' : ''} demande${more > 1 ? 's' : ''} · Voir' : 'Payer ou refuser',
                  style: TextStyle(color: context.pal.muted, fontSize: 13),
                ),
              ],
            ),
          ),
          Icon(Icons.chevron_right_rounded, color: context.pal.muted),
        ],
      ),
    );
  }
}
