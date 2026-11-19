import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Acheter du crédit téléphone (unités), pour soi ou pour un proche, sur tous les réseaux.
class AirtimeScreen extends StatefulWidget {
  const AirtimeScreen({super.key, required this.app, this.limits});

  final AppState app;
  final Limits? limits;

  @override
  State<AirtimeScreen> createState() => _AirtimeScreenState();
}

class _AirtimeScreenState extends State<AirtimeScreen> with ApiAction {
  static const _amounts = [100, 200, 500, 1000];

  late final phone = TextEditingController(text: _local(widget.app.customer!.phone));
  final amount = TextEditingController();
  final pin = TextEditingController();
  late MobileNetwork? network = MobileNetwork.forPhone(widget.app.customer!.phone);
  List<RecentAirtimeNumber> recent = const [];
  AirtimePurchase? purchase;

  /// Gardée tant que la saisie ne change pas : un nouvel essai après une coupure n'achète pas deux fois.
  String _key = newIdempotencyKey();

  static String _local(String p) => p.startsWith('+243') ? p.substring(4) : p;

  @override
  void initState() {
    super.initState();
    phone.addListener(_phoneChanged);
    _loadRecent();
  }

  Future<void> _loadRecent() async {
    try {
      final r = await widget.app.api.recentAirtimeNumbers();
      final own = widget.app.customer!.phone;
      if (mounted) setState(() => recent = r.where((n) => n.phone != own).toList());
    } on ApiException {
      // Pas de raccourcis : le client tape le numéro.
    }
  }

  void _phoneChanged() {
    _key = newIdempotencyKey();
    final guessed = MobileNetwork.forPhone(phone.text);
    if (guessed != null && guessed != network) setState(() => network = guessed);
  }

  void _pick(String number, MobileNetwork? net) {
    phone.text = _local(number);
    setState(() => network = net ?? network);
  }

  bool get _forMe => normalizePhone(phone.text) == widget.app.customer!.phone;

  Future<void> _buy() async {
    final number = normalizePhone(phone.text);
    if (number == null) return setState(() => error = 'Numéro invalide : 9 chiffres après +243.');
    if (network == null) return setState(() => error = 'Choisissez le réseau de ce numéro.');
    final minor = parseUsd(amount.text);
    if (minor == null || minor < 50 || minor > 5000) {
      return setState(() => error = 'Le crédit va de 0,50 \$ à 50 \$ par achat.');
    }
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final p = await run(
      widget.app,
      () => widget.app.api.buyAirtime(phone: number, network: network, amountMinor: minor, pin: pin.text, idempotencyKey: _key),
    );
    pin.clear();
    if (p != null && mounted) setState(() => purchase = p);
  }

  @override
  Widget build(BuildContext context) {
    final p = purchase;
    if (p != null) return _status(p);
    return FormPage(
      title: 'Acheter du crédit',
      subtitle: 'Des unités pour votre téléphone ou celui d\'un proche, sur tous les réseaux. Sans frais.',
      children: [
        ErrorBanner(error),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            ChoiceChip(
              label: const Text('Mon numéro'),
              selected: _forMe,
              onSelected: (_) => _pick(widget.app.customer!.phone, MobileNetwork.forPhone(widget.app.customer!.phone)),
            ),
            for (final r in recent)
              ChoiceChip(
                label: Text('${r.network?.label ?? ''} ${_local(r.phone)}'.trim()),
                selected: normalizePhone(phone.text) == r.phone,
                onSelected: (_) => _pick(r.phone, r.network),
              ),
          ],
        ),
        const SizedBox(height: 14),
        PhoneField(controller: phone, label: 'Numéro à recharger'),
        const SizedBox(height: 14),
        Text('Réseau', style: TextStyle(color: context.pal.muted)),
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          children: [
            for (final n in MobileNetwork.values)
              ChoiceChip(
                key: ValueKey('network-${n.apiName}'),
                label: Text(n.label),
                selected: network == n,
                onSelected: (_) => setState(() {
                  network = n;
                  _key = newIdempotencyKey();
                }),
              ),
          ],
        ),
        const SizedBox(height: 16),
        TextField(
          key: const ValueKey('amount'),
          controller: amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$', helperText: 'De 0,50 \$ à 50 \$'),
          onChanged: (_) => setState(() => _key = newIdempotencyKey()),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          children: [
            for (final a in _amounts)
              ChoiceChip(
                label: Text('${a ~/ 100} \$'),
                selected: parseUsd(amount.text) == a,
                onSelected: (_) => setState(() {
                  amount.text = '${a ~/ 100}';
                  _key = newIdempotencyKey();
                }),
              ),
          ],
        ),
        const SizedBox(height: 20),
        PinField(controller: pin, label: 'PIN pour confirmer', onSubmitted: (_) => _buy()),
        const SizedBox(height: 20),
        BusyButton(
          label: parseUsd(amount.text) == null ? 'Acheter' : 'Acheter ${formatUsd(parseUsd(amount.text)!)}',
          busy: busy,
          onPressed: _buy,
        ),
      ],
    );
  }

  Widget _status(AirtimePurchase p) {
    final net = p.network?.label ?? 'l\'opérateur';
    final (icon, color, title, text) = switch (p.status) {
      'DELIVERED' => (
        Icons.check_circle,
        Brand.success,
        'Crédit envoyé',
        '${formatUsd(p.amountMinor)} de crédit $net sont arrivés sur le ${p.phone}.',
      ),
      'FAILED' => (
        Icons.cancel,
        Brand.danger,
        'Crédit non envoyé',
        '${p.failureMessage ?? '$net a refusé ce numéro'}. ${formatUsd(p.amountMinor)} sont revenus sur votre solde.',
      ),
      _ => (
        Icons.hourglass_top,
        Brand.ochre,
        'Crédit en cours',
        '$net n\'a pas encore confirmé. Si le crédit n\'arrive pas, vous serez remboursé. Vous n\'avez rien à refaire.',
      ),
    };
    return FormPage(
      title: 'Acheter du crédit',
      children: [
        const SizedBox(height: 24),
        Icon(icon, size: 72, color: color),
        const SizedBox(height: 16),
        Text(title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Text(text, textAlign: TextAlign.center, style: TextStyle(color: context.pal.muted, fontSize: 15)),
        const SizedBox(height: 24),
        FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
        TextButton(
          onPressed: () => setState(() {
            purchase = null;
            _key = newIdempotencyKey();
          }),
          child: const Text('Acheter encore'),
        ),
      ],
    );
  }
}
