import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';

/// Demande le PIN de l'app avant une action sensible. Null si le client annule.
Future<String?> askPin(BuildContext context, {required String title, String? message, required String confirm}) {
  final pin = TextEditingController();
  return showDialog<String>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (message != null) ...[Text(message), const SizedBox(height: 12)],
          PinField(controller: pin, autofocus: true, onSubmitted: (v) => Navigator.of(c).pop(v)),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(c).pop(), child: const Text('Annuler')),
        FilledButton(
          key: const ValueKey('pin-confirm'),
          onPressed: () => Navigator.of(c).pop(pin.text),
          child: Text(confirm),
        ),
      ],
    ),
  );
}

/// Dessin de la carte : ocre pour la virtuelle, encre pour la physique ; grisée quand elle ne paie pas.
class CardFace extends StatelessWidget {
  const CardFace({super.key, required this.card, this.details, this.onTap});

  final PaymentCard card;

  /// Numéro complet, quand le client l'a demandé avec son PIN.
  final CardDetails? details;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final dim = !card.isActive;
    final onCard = card.isVirtual ? Brand.ink : Colors.white;
    final gradient = card.isVirtual ? Brand.ochreGradient : context.pal.frontCard;
    final number = details?.groupedPan ?? '••••  ••••  ••••  ${card.last4}';
    return GestureDetector(
      onTap: onTap,
      child: AspectRatio(
        aspectRatio: 1.586, // format ISO des cartes bancaires
        child: Opacity(
          opacity: dim ? 0.55 : 1,
          child: Container(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
            decoration: BoxDecoration(gradient: gradient, borderRadius: BorderRadius.circular(22)),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      card.label,
                      style: TextStyle(color: onCard, fontWeight: FontWeight.w700, fontSize: 14),
                    ),
                    const Spacer(),
                    _StatusChip(card),
                  ],
                ),
                const Spacer(),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(
                    number,
                    key: ValueKey('card-number-${card.id}'),
                    style: TextStyle(color: onCard, fontSize: 21, letterSpacing: 1.5, fontWeight: FontWeight.w600),
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            card.embossedName,
                            style: TextStyle(color: onCard, fontSize: 13, fontWeight: FontWeight.w600),
                          ),
                          Text(
                            details == null
                                ? 'Expire ${card.expiry}'
                                : 'Expire ${details!.expiry} · CVV ${details!.cvv}',
                            key: ValueKey('card-expiry-${card.id}'),
                            style: TextStyle(color: onCard.withValues(alpha: 0.75), fontSize: 12),
                          ),
                        ],
                      ),
                    ),
                    Text(
                      card.network,
                      style: TextStyle(
                        color: onCard,
                        fontSize: 18,
                        fontWeight: FontWeight.w900,
                        fontStyle: FontStyle.italic,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip(this.card);

  final PaymentCard card;

  @override
  Widget build(BuildContext context) {
    final color = switch (card.status) {
      'ACTIVE' => Brand.success,
      'BLOCKED' || 'EXPIRED' => Brand.danger,
      _ => Brand.ink.withValues(alpha: 0.7),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20)),
      child: Text(
        card.statusLabel,
        style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w700),
      ),
    );
  }
}

/// Mes cartes : la virtuelle se crée en une touche, la physique se commande et se retire en agence.
class CardsScreen extends StatefulWidget {
  const CardsScreen({super.key, required this.app, this.onKyc});

  final AppState app;

  /// Ouvre le passage au niveau 2, quand le client n'y est pas encore.
  final VoidCallback? onKyc;

  @override
  State<CardsScreen> createState() => _CardsScreenState();
}

class _CardsScreenState extends State<CardsScreen> with ApiAction {
  CardsOverview? overview;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final o = await run(widget.app, widget.app.api.cards);
    if (o != null && mounted) setState(() => overview = o);
  }

  Future<void> _open(PaymentCard c) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => CardScreen(app: widget.app, card: c, overview: overview!),
      ),
    );
    if (mounted) await _load();
  }

  Future<void> _createVirtual() async {
    final c = await run(widget.app, widget.app.api.createVirtualCard);
    if (c == null || !mounted) return;
    await _load();
    if (mounted) await _open(c);
  }

  Future<void> _orderPhysical() async {
    final c = await Navigator.of(context).push<PaymentCard>(
      MaterialPageRoute(
        builder: (_) => OrderCardScreen(app: widget.app, overview: overview!),
      ),
    );
    if (c != null && mounted) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final o = overview;
    return FormPage(
      title: 'Cartes',
      subtitle:
          'Cartes Visa prépayées : elles dépensent le solde de votre compte, en magasin, en ligne et au distributeur.',
      children: [
        ErrorBanner(error),
        if (o == null && error == null) const Center(child: CircularProgressIndicator()),
        if (o != null && !o.eligible)
          SoftCard(
            key: const ValueKey('cards-kyc'),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Row(
                  children: [
                    IconTile(Icons.verified_user_outlined, accent: true),
                    SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Les cartes demandent le niveau 2',
                        style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Text(
                  'Envoyez votre pièce d\'identité. Dès la validation, votre carte virtuelle est prête et vous pouvez commander la carte physique.',
                  style: TextStyle(color: context.pal.muted, height: 1.4),
                ),
                if (widget.onKyc != null) ...[
                  const SizedBox(height: 14),
                  FilledButton(
                    onPressed: () {
                      Navigator.of(context).pop();
                      widget.onKyc!();
                    },
                    child: const Text('Passer au niveau 2'),
                  ),
                ],
              ],
            ),
          ),
        if (o != null && o.eligible) ...[
          for (final c in o.cards)
            Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  CardFace(key: ValueKey('card-${c.id}'), card: c, onTap: () => _open(c)),
                  if (c.status == 'READY_FOR_PICKUP' || c.isDead || c.inProduction)
                    Padding(
                      padding: const EdgeInsets.only(top: 8, left: 4),
                      child: Text(_hint(c), style: TextStyle(color: context.pal.muted, fontSize: 13)),
                    ),
                ],
              ),
            ),
          if (o.virtual == null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: BusyButton(
                key: const ValueKey('card-create-virtual'),
                label: 'Créer ma carte virtuelle',
                busy: busy,
                onPressed: _createVirtual,
              ),
            ),
          if (o.physical == null && !o.cards.any((c) => !c.isVirtual && c.replaceable))
            OutlinedButton.icon(
              key: const ValueKey('card-order-physical'),
              onPressed: _orderPhysical,
              icon: const Icon(Icons.credit_card_rounded),
              label: Text(
                o.physicalCardFeeMinor > 0
                    ? 'Commander la carte physique · ${formatUsd(o.physicalCardFeeMinor)}'
                    : 'Commander la carte physique',
              ),
            ),
        ],
      ],
    );
  }

  String _hint(PaymentCard c) => switch (c.status) {
    'ORDERED' || 'IN_PRODUCTION' =>
      'En fabrication. Nous vous prévenons par SMS quand elle arrive à l\'agence ${c.pickupBranch ?? ''}.',
    'READY_FOR_PICKUP' => 'Elle vous attend à l\'agence ${c.pickupBranch ?? ''}. Venez avec votre pièce d\'identité.',
    _ => c.replaceable ? 'Touchez la carte pour la remplacer.' : '',
  };
}

class CardScreenTiming {
  /// Le numéro se recache tout seul : un écran laissé ouvert ne le montre pas longtemps.
  static const showFor = Duration(seconds: 60);
}

/// Une carte : son numéro, son gel, ses plafonds, son blocage, ses derniers paiements.
class CardScreen extends StatefulWidget {
  const CardScreen({super.key, required this.app, required this.card, required this.overview});

  final AppState app;
  final PaymentCard card;
  final CardsOverview overview;

  @override
  State<CardScreen> createState() => _CardScreenState();
}

class _CardScreenState extends State<CardScreen> with ApiAction {
  late PaymentCard card = widget.card;
  CardDetails? details;
  List<CardPayment>? payments;
  String? done;
  Timer? _hideTimer;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  /// La carte a pu changer depuis la liste (arrivée à l'agence, gel depuis un autre téléphone).
  Future<void> _reload() async {
    final c = await run(widget.app, () => widget.app.api.card(card.id));
    if (c != null && mounted) setState(() => card = c);
    await _loadPayments();
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadPayments() async {
    if (!card.inService) return;
    try {
      final p = await widget.app.api.cardPayments(card.id);
      if (mounted) setState(() => payments = p);
    } on ApiException {
      // La liste reste vide ; le reste de l'écran marche.
    }
  }

  Future<void> _apply(Future<PaymentCard> Function() call, {String? message}) async {
    final c = await run(widget.app, call);
    if (c == null || !mounted) return;
    setState(() {
      card = c;
      done = message;
      if (!c.inService) details = null;
    });
  }

  Future<void> _showNumber() async {
    if (details != null) {
      _hideTimer?.cancel();
      return setState(() => details = null);
    }
    final pin = await askPin(
      context,
      title: 'Afficher le numéro',
      message: 'Personne d\'autre ne doit voir votre écran.',
      confirm: 'Afficher',
    );
    if (pin == null || !mounted) return;
    final d = await run(widget.app, () => widget.app.api.cardDetails(card.id, pin));
    if (d == null || !mounted) return;
    setState(() => details = d);
    _hideTimer?.cancel();
    _hideTimer = Timer(CardScreenTiming.showFor, () {
      if (mounted) setState(() => details = null);
    });
  }

  Future<void> _toggleFreeze(bool freeze) async {
    if (freeze) {
      return _apply(
        () => widget.app.api.freezeCard(card.id),
        message: 'Carte gelée : aucun paiement ne passera. Dégelez-la quand vous la retrouvez.',
      );
    }
    final pin = await askPin(context, title: 'Dégeler la carte', confirm: 'Dégeler');
    if (pin == null || !mounted) return;
    await _apply(() => widget.app.api.unfreezeCard(card.id, pin), message: 'Carte de nouveau active.');
  }

  Future<void> _toggleOnline(bool on) =>
      _apply(() => widget.app.api.setCardControls(card.id, dailyLimitMinor: card.dailyLimitMinor, onlinePayments: on));

  Future<void> _editLimit() async {
    final input = TextEditingController(text: formatAmountInput(card.dailyLimitMinor));
    final max = widget.overview.maxDailyLimitMinor;
    final value = await showDialog<int>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Plafond par jour'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Ce que cette carte peut payer en un jour, jusqu\'à ${formatUsd(max)}.'),
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('card-limit'),
              controller: input,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(), child: const Text('Annuler')),
          FilledButton(
            key: const ValueKey('card-limit-save'),
            onPressed: () => Navigator.of(c).pop(parseUsd(input.text) ?? -1),
            child: const Text('Enregistrer'),
          ),
        ],
      ),
    );
    if (value == null || !mounted) return;
    if (value < 100 || value > max) {
      return setState(() => error = 'Choisissez un plafond entre 1,00 \$ et ${formatUsd(max)}.');
    }
    await _apply(
      () => widget.app.api.setCardControls(card.id, dailyLimitMinor: value, onlinePayments: card.onlinePayments),
      message: 'Plafond changé : ${formatUsd(value)} par jour.',
    );
  }

  Future<void> _block() async {
    final reason = await showModalBottomSheet<String>(
      context: context,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
      builder: (s) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Bloquer la carte', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              Text(
                'C\'est définitif : la carte ne marchera plus jamais. Si vous l\'avez seulement égarée, gelez-la plutôt.',
                style: TextStyle(color: context.pal.muted, height: 1.4),
              ),
              const SizedBox(height: 10),
              for (final (code, label) in const [
                ('LOST', 'Je l\'ai perdue'),
                ('STOLEN', 'On me l\'a volée'),
                ('FRAUD', 'Je vois des paiements que je n\'ai pas faits'),
              ])
                ListTile(
                  key: ValueKey('block-$code'),
                  contentPadding: EdgeInsets.zero,
                  title: Text(label),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => Navigator.of(s).pop(code),
                ),
            ],
          ),
        ),
      ),
    );
    if (reason == null || !mounted) return;
    final pin = await askPin(context, title: 'Bloquer définitivement ?', confirm: 'Bloquer');
    if (pin == null || !mounted) return;
    await _apply(
      () => widget.app.api.blockCard(card.id, reason, pin),
      message: card.isVirtual
          ? 'Carte bloquée. Vous pouvez en créer une nouvelle.'
          : 'Carte bloquée. Vous pouvez la remplacer ci-dessous.',
    );
  }

  Future<void> _delete() async {
    final pin = await askPin(
      context,
      title: 'Supprimer la carte virtuelle ?',
      message: 'Son numéro ne marchera plus, y compris sur les sites où vous l\'avez enregistré. Vous pourrez en créer une nouvelle.',
      confirm: 'Supprimer',
    );
    if (pin == null || !mounted) return;
    final ok = await run(widget.app, () async {
      await widget.app.api.deleteCard(card.id, pin);
      return true;
    });
    if (ok == true && mounted) Navigator.of(context).pop();
  }

  final _replaceKey = newIdempotencyKey();

  Future<void> _replace() async {
    final fee = card.isVirtual ? 0 : widget.overview.replacementFeeMinor;
    final pin = await askPin(
      context,
      title: 'Remplacer la carte',
      message: card.isVirtual
          ? 'Une nouvelle carte virtuelle, avec un nouveau numéro.'
          : 'Nouvelle carte avec un nouveau numéro, à retirer à l\'agence ${card.pickupBranch ?? ''}.'
                '${fee > 0 ? ' ${formatUsd(fee)} sont pris sur votre solde.' : ''}',
      confirm: 'Remplacer',
    );
    if (pin == null || !mounted) return;
    final c = await run(widget.app, () => widget.app.api.replaceCard(card.id, pin: pin, idempotencyKey: _replaceKey));
    if (c != null && mounted) Navigator.of(context).pop();
  }

  Future<void> _activate() async {
    final c = await Navigator.of(context).push<PaymentCard>(
      MaterialPageRoute(
        builder: (_) => ActivateCardScreen(app: widget.app, card: card),
      ),
    );
    if (c != null && mounted) {
      setState(() {
        card = c;
        done = 'Carte activée. Utilisez le PIN que vous venez de choisir aux terminaux et aux distributeurs.';
      });
      await _loadPayments();
    }
  }

  @override
  Widget build(BuildContext context) {
    final muted = TextStyle(color: context.pal.muted, fontSize: 13);
    return FormPage(
      title: card.label,
      children: [
        ErrorBanner(error),
        if (done != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Text(
              done!,
              key: const ValueKey('card-done'),
              style: const TextStyle(color: Brand.success, fontWeight: FontWeight.w600),
            ),
          ),
        CardFace(card: card, details: details),
        const SizedBox(height: 16),
        if (card.inService) ...[
          // Pas de bouton « copier » : d'autres applications du téléphone peuvent lire le presse-papiers.
          OutlinedButton.icon(
            key: const ValueKey('card-show-number'),
            onPressed: busy ? null : _showNumber,
            icon: Icon(details == null ? Icons.visibility_outlined : Icons.visibility_off_outlined),
            label: Text(details == null ? 'Afficher le numéro' : 'Cacher le numéro'),
          ),
          const SizedBox(height: 16),
          SoftCard(
            padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 6),
            child: Column(
              children: [
                SwitchListTile(
                  key: const ValueKey('card-freeze'),
                  value: card.isFrozen,
                  onChanged: busy ? null : _toggleFreeze,
                  title: const Text('Geler la carte'),
                  subtitle: const Text('Bloque les paiements le temps de la retrouver. Réversible.'),
                ),
                if (!card.isVirtual)
                  SwitchListTile(
                    key: const ValueKey('card-online'),
                    value: card.onlinePayments,
                    onChanged: busy ? null : _toggleOnline,
                    title: const Text('Paiements en ligne'),
                    subtitle: const Text(
                      'Sur les sites et les applications. Coupés tant que vous n\'en avez pas besoin.',
                    ),
                  ),
                ListTile(
                  key: const ValueKey('card-limit-row'),
                  onTap: busy ? null : _editLimit,
                  title: const Text('Plafond par jour'),
                  subtitle: Text(
                    '${formatUsd(card.spentTodayMinor)} payés aujourd\'hui sur ${formatUsd(card.dailyLimitMinor)}',
                  ),
                  trailing: const Icon(Icons.edit_outlined),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          if (payments != null && payments!.isNotEmpty) ...[
            const Text('Derniers paiements', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 18)),
            const SizedBox(height: 8),
            for (final p in payments!.take(10))
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: IconTile(
                  p.declined ? Icons.block_rounded : (p.online ? Icons.language_rounded : Icons.storefront_rounded),
                  size: 40,
                ),
                title: Text(p.merchantName),
                subtitle: Text(
                  p.declined
                      ? 'Refusé : ${p.declineMessage ?? ''}'
                      : (p.status == 'REVERSED' ? 'Annulé, remboursé' : formatDateTime(p.createdAt)),
                  style: p.declined ? const TextStyle(color: Brand.danger, fontSize: 13) : muted,
                ),
                trailing: Text(
                  formatUsd(p.amountMinor),
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    decoration: p.declined || p.status == 'REVERSED' ? TextDecoration.lineThrough : null,
                  ),
                ),
              ),
            const SizedBox(height: 8),
          ],
          TextButton.icon(
            key: const ValueKey('card-block'),
            onPressed: busy ? null : _block,
            icon: const Icon(Icons.report_gmailerrorred_rounded, color: Brand.danger),
            label: const Text('Carte perdue, volée ou piratée', style: TextStyle(color: Brand.danger)),
          ),
          if (card.isVirtual)
            TextButton(
              key: const ValueKey('card-delete'),
              onPressed: busy ? null : _delete,
              child: const Text('Supprimer la carte virtuelle'),
            ),
        ],
        if (card.inProduction) _ProductionSteps(card),
        if (card.status == 'READY_FOR_PICKUP') ...[
          const SizedBox(height: 16),
          FilledButton(
            key: const ValueKey('card-activate'),
            onPressed: _activate,
            child: const Text('J\'ai ma carte : l\'activer'),
          ),
        ],
        if (card.isDead) ...[
          Text(
            card.status == 'EXPIRED' ? 'Cette carte a expiré.' : 'Cette carte est bloquée définitivement.',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 12),
          if (card.replaceable)
            BusyButton(
              key: const ValueKey('card-replace'),
              label: card.isVirtual || widget.overview.replacementFeeMinor == 0
                  ? 'Remplacer la carte'
                  : 'Remplacer la carte · ${formatUsd(widget.overview.replacementFeeMinor)}',
              busy: busy,
              onPressed: _replace,
            ),
        ],
      ],
    );
  }
}

/// Commandée → en fabrication → prête à l'agence → active.
class _ProductionSteps extends StatelessWidget {
  const _ProductionSteps(this.card);

  final PaymentCard card;

  @override
  Widget build(BuildContext context) {
    const steps = [
      ('ORDERED', 'Commandée'),
      ('IN_PRODUCTION', 'En fabrication'),
      ('READY_FOR_PICKUP', 'À l\'agence'),
      ('ACTIVE', 'Activée'),
    ];
    final at = steps.indexWhere((s) => s.$1 == card.status);
    return SoftCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < steps.length; i++)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  Icon(
                    i < at
                        ? Icons.check_circle_rounded
                        : (i == at ? Icons.radio_button_checked_rounded : Icons.radio_button_unchecked_rounded),
                    color: i <= at ? Brand.ochreDeep : context.pal.muted,
                  ),
                  const SizedBox(width: 12),
                  Text(steps[i].$2, style: TextStyle(fontWeight: i == at ? FontWeight.w700 : FontWeight.w400)),
                ],
              ),
            ),
          const SizedBox(height: 6),
          Text(
            'Agence de retrait : ${card.pickupBranch ?? ''}',
            style: TextStyle(color: context.pal.muted, fontSize: 13),
          ),
        ],
      ),
    );
  }
}

/// Commande de la carte physique : l'agence où la retirer, puis le PIN.
class OrderCardScreen extends StatefulWidget {
  const OrderCardScreen({super.key, required this.app, required this.overview});

  final AppState app;
  final CardsOverview overview;

  @override
  State<OrderCardScreen> createState() => _OrderCardScreenState();
}

class _OrderCardScreenState extends State<OrderCardScreen> with ApiAction {
  final pin = TextEditingController();
  late String? branch = widget.overview.branches.length == 1 ? widget.overview.branches.first : null;

  /// Gardée pendant tout l'écran : un nouvel essai après une coupure ne commande pas deux cartes.
  final key = newIdempotencyKey();

  Future<void> _order() async {
    if (branch == null) return setState(() => error = 'Choisissez l\'agence où retirer votre carte.');
    if (pin.text.length != 4) return setState(() => error = 'Entrez votre PIN pour confirmer.');
    final c = await run(
      widget.app,
      () => widget.app.api.orderPhysicalCard(pickupBranch: branch!, pin: pin.text, idempotencyKey: key),
    );
    if (c != null && mounted) Navigator.of(context).pop(c);
  }

  @override
  Widget build(BuildContext context) {
    final fee = widget.overview.physicalCardFeeMinor;
    return FormPage(
      title: 'Carte physique',
      subtitle: 'Pour payer en magasin et retirer au distributeur. Fabriquée à votre nom, elle se retire en agence avec votre pièce d\'identité.',
      children: [
        ErrorBanner(error),
        DropdownButtonFormField<String>(
          key: const ValueKey('card-branch'),
          initialValue: branch,
          decoration: const InputDecoration(labelText: 'Agence de retrait'),
          items: [for (final b in widget.overview.branches) DropdownMenuItem(value: b, child: Text(b))],
          onChanged: (v) => setState(() => branch = v),
        ),
        const SizedBox(height: 16),
        SoftCard(
          child: Text(
            fee > 0
                ? 'Prix de la carte : ${formatUsd(fee)}, pris sur votre solde à la commande.'
                : 'La carte est offerte.',
            key: const ValueKey('card-fee'),
          ),
        ),
        const SizedBox(height: 16),
        PinField(controller: pin, onSubmitted: (_) => _order()),
        const SizedBox(height: 20),
        BusyButton(key: const ValueKey('card-order'), label: 'Commander', busy: busy, onPressed: _order),
      ],
    );
  }
}

/// Activation de la carte remise en agence : le code du pli, puis le PIN de la carte, choisi par le client.
class ActivateCardScreen extends StatefulWidget {
  const ActivateCardScreen({super.key, required this.app, required this.card});

  final AppState app;
  final PaymentCard card;

  @override
  State<ActivateCardScreen> createState() => _ActivateCardScreenState();
}

class _ActivateCardScreenState extends State<ActivateCardScreen> with ApiAction {
  final code = TextEditingController();
  final cardPin = TextEditingController();
  final confirm = TextEditingController();

  Future<void> _activate() async {
    final c = code.text.replaceAll(RegExp(r'\D'), '');
    if (c.length != 6) {
      return setState(() => error = 'Le code d\'activation a 6 chiffres. Il est sur le pli remis avec la carte.');
    }
    final problem = pinProblem(cardPin.text);
    if (problem != null) return setState(() => error = problem);
    if (cardPin.text != confirm.text) return setState(() => error = 'Les deux PIN ne sont pas identiques.');
    final card = await run(widget.app, () => widget.app.api.activateCard(widget.card.id, c, cardPin.text));
    if (card != null && mounted) Navigator.of(context).pop(card);
  }

  @override
  Widget build(BuildContext context) => FormPage(
    title: 'Activer la carte',
    subtitle:
        'Carte •••• ${widget.card.last4}. Choisissez un PIN pour les terminaux et les distributeurs. '
        'Gardez-le pour vous : personne chez Capital Invest ne vous le demandera.',
    children: [
      ErrorBanner(error),
      TextField(
        key: const ValueKey('activation-code'),
        controller: code,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(6)],
        decoration: const InputDecoration(labelText: 'Code d\'activation (6 chiffres)'),
      ),
      const SizedBox(height: 16),
      PinField(controller: cardPin, label: 'PIN de la carte'),
      const SizedBox(height: 16),
      PinField(controller: confirm, label: 'PIN de la carte, encore', onSubmitted: (_) => _activate()),
      const SizedBox(height: 20),
      BusyButton(key: const ValueKey('card-activate-confirm'), label: 'Activer', busy: busy, onPressed: _activate),
    ],
  );
}
