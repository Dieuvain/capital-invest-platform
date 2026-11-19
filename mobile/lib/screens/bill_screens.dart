import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

IconData billerIcon(String category) => switch (category) {
  'Électricité' => Icons.bolt_rounded,
  'Eau' => Icons.water_drop_outlined,
  _ => Icons.tv_rounded,
};

/// Choisir un facturier, ou repayer un compte déjà payé.
class BillersScreen extends StatefulWidget {
  const BillersScreen({super.key, required this.app});

  final AppState app;

  @override
  State<BillersScreen> createState() => _BillersScreenState();
}

class _BillersScreenState extends State<BillersScreen> with ApiAction {
  List<Biller>? billers;
  List<RecentBill> recent = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final b = await run(widget.app, widget.app.api.billers);
    if (b == null || !mounted) return;
    setState(() => billers = b);
    try {
      final r = await widget.app.api.recentBills();
      if (mounted) setState(() => recent = r);
    } on ApiException {
      // Pas de raccourcis : le client tape son numéro.
    }
  }

  void _open(Biller b, [String? reference]) => Navigator.of(context).push(
    MaterialPageRoute<void>(builder: (_) => BillPayScreen(app: widget.app, biller: b, reference: reference)),
  );

  @override
  Widget build(BuildContext context) {
    final list = billers ?? const <Biller>[];
    return FormPage(
      title: 'Payer une facture',
      subtitle: 'Électricité, eau, télévision : payez depuis votre solde, sans frais et sans faire la queue.',
      children: [
        ErrorBanner(error),
        if (recent.isNotEmpty) ...[
          const Text('Mes factures', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 8),
          for (final r in recent)
            if (list.where((b) => b.code == r.biller).firstOrNull case final b?)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: SoftCard(
                  key: ValueKey('recent-bill-${r.reference}'),
                  onTap: () => _open(b, r.reference),
                  padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
                  child: Row(
                    children: [
                      IconTile(billerIcon(b.category), size: 42, accent: true),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('${r.billerName} · ${r.reference}', style: const TextStyle(fontWeight: FontWeight.w600)),
                            Text(r.holderName, style: TextStyle(color: context.pal.muted, fontSize: 13)),
                          ],
                        ),
                      ),
                      const Icon(Icons.chevron_right_rounded),
                    ],
                  ),
                ),
              ),
          const SizedBox(height: 10),
          const Text('Autre facture', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 8),
        ],
        for (final b in list)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: SoftCard(
              key: ValueKey('biller-${b.code}'),
              onTap: () => _open(b),
              padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
              child: Row(
                children: [
                  IconTile(billerIcon(b.category), size: 42),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(b.label, style: const TextStyle(fontWeight: FontWeight.w600)),
                        Text(
                          b.prepaid ? '${b.category} · compteur prépayé' : b.category,
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
}

/// Payer : numéro vérifié chez le facturier (nom du titulaire), montant, PIN ; puis le code du compteur.
class BillPayScreen extends StatefulWidget {
  const BillPayScreen({super.key, required this.app, required this.biller, this.reference});

  final AppState app;
  final Biller biller;
  final String? reference;

  @override
  State<BillPayScreen> createState() => _BillPayScreenState();
}

class _BillPayScreenState extends State<BillPayScreen> with ApiAction {
  late final reference = TextEditingController(text: widget.reference);
  final amount = TextEditingController();
  final pin = TextEditingController();
  BillAccount? account;
  BillPayment? payment;
  String _key = newIdempotencyKey();

  @override
  void initState() {
    super.initState();
    if (widget.reference != null) WidgetsBinding.instance.addPostFrameCallback((_) => _check());
  }

  Future<void> _check() async {
    final a = await run(widget.app, () => widget.app.api.lookupBill(widget.biller.code, reference.text));
    if (a == null || !mounted) return;
    setState(() {
      account = a;
      if (a.amountDueMinor != null) amount.text = formatUsd(a.amountDueMinor!).replaceAll(RegExp(r'[^0-9,]'), '');
      _key = newIdempotencyKey();
    });
  }

  Future<void> _pay() async {
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 10 ou 12,50.');
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final p = await run(
      widget.app,
      () => widget.app.api.payBill(
        biller: widget.biller.code,
        reference: account!.reference,
        amountMinor: minor,
        pin: pin.text,
        idempotencyKey: _key,
      ),
    );
    pin.clear();
    if (p != null && mounted) setState(() => payment = p);
  }

  @override
  Widget build(BuildContext context) {
    final p = payment;
    if (p != null) return _status(p);
    final a = account;
    final b = widget.biller;
    return FormPage(
      title: b.label,
      subtitle: b.prepaid
          ? 'Achetez de l\'électricité : vous recevez un code à taper sur votre compteur, aussi envoyé par SMS.'
          : 'Réglez votre facture ${b.category.toLowerCase()} depuis votre solde.',
      children: [
        ErrorBanner(error),
        TextField(
          key: const ValueKey('bill-reference'),
          controller: reference,
          keyboardType: TextInputType.number,
          enabled: a == null,
          decoration: InputDecoration(labelText: b.referenceLabel),
        ),
        const SizedBox(height: 16),
        if (a == null)
          BusyButton(label: 'Vérifier', busy: busy, onPressed: _check)
        else ...[
          SoftCard(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                const Icon(Icons.verified_rounded, color: Brand.success),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(a.holderName, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                      Text(
                        a.amountDueMinor == null
                            ? 'Titulaire du compteur ${a.reference}'
                            : 'Montant dû : ${formatUsd(a.amountDueMinor!)}',
                        style: TextStyle(color: context.pal.muted, fontSize: 13),
                      ),
                    ],
                  ),
                ),
                TextButton(
                  onPressed: () => setState(() {
                    account = null;
                    amount.clear();
                  }),
                  child: const Text('Changer'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            key: const ValueKey('amount'),
            controller: amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$'),
            onChanged: (_) => setState(() => _key = newIdempotencyKey()),
          ),
          if (b.prepaid) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              children: [
                for (final v in [5, 10, 20, 50])
                  ChoiceChip(
                    label: Text('$v \$'),
                    selected: parseUsd(amount.text) == v * 100,
                    onSelected: (_) => setState(() {
                      amount.text = '$v';
                      _key = newIdempotencyKey();
                    }),
                  ),
              ],
            ),
          ],
          const SizedBox(height: 16),
          PinField(controller: pin, label: 'PIN pour confirmer', onSubmitted: (_) => _pay()),
          const SizedBox(height: 20),
          BusyButton(
            label: parseUsd(amount.text) == null ? 'Payer' : 'Payer ${formatUsd(parseUsd(amount.text)!)}',
            busy: busy,
            onPressed: _pay,
          ),
        ],
      ],
    );
  }

  Widget _status(BillPayment p) {
    final (icon, color, title, text) = switch (p.status) {
      'PAID' => (
        Icons.check_circle,
        Brand.success,
        'Facture payée',
        p.token == null
            ? '${formatUsd(p.amountMinor)} payés à ${p.billerName} pour ${p.holderName} (${p.reference}).'
            : 'Tapez ce code sur le compteur ${p.reference}. Il vous a aussi été envoyé par SMS.',
      ),
      'FAILED' => (
        Icons.cancel,
        Brand.danger,
        'Paiement refusé',
        '${p.failureMessage ?? '${p.billerName} a refusé le paiement'}. ${formatUsd(p.amountMinor)} sont revenus sur votre solde.',
      ),
      _ => (
        Icons.hourglass_top,
        Brand.ochre,
        'Paiement en cours',
        '${p.billerName} n\'a pas encore confirmé. S\'il refuse, vous serez remboursé. Vous n\'avez rien à refaire.',
      ),
    };
    return FormPage(
      title: p.billerName,
      children: [
        const SizedBox(height: 24),
        Icon(icon, size: 72, color: color),
        const SizedBox(height: 16),
        Text(title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Text(text, textAlign: TextAlign.center, style: TextStyle(color: context.pal.muted, fontSize: 15)),
        if (p.token != null) ...[
          const SizedBox(height: 20),
          SoftCard(
            padding: const EdgeInsets.symmetric(vertical: 18, horizontal: 12),
            child: Column(
              children: [
                Text('Code du compteur', style: TextStyle(color: context.pal.muted)),
                const SizedBox(height: 6),
                FittedBox(
                  child: Text(
                    p.token!,
                    key: const ValueKey('bill-token'),
                    style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800, letterSpacing: 1.5),
                  ),
                ),
                const SizedBox(height: 4),
                TextButton.icon(
                  icon: const Icon(Icons.copy_rounded, size: 18),
                  label: const Text('Copier le code'),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: p.token!.replaceAll(' ', '')));
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Code copié')));
                  },
                ),
                Text('${formatUsd(p.amountMinor)} · ${p.holderName}', style: TextStyle(color: context.pal.muted, fontSize: 13)),
              ],
            ),
          ),
        ],
        const SizedBox(height: 24),
        FilledButton(onPressed: () => Navigator.of(context).popUntil((r) => r.isFirst), child: const Text('Terminé')),
      ],
    );
  }
}
