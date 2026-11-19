import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../app_state.dart';
import '../ui/chrome.dart';
import '../ui/format.dart';
import '../ui/theme.dart';
import '../ui/widgets.dart';
import 'pending_transfer_screens.dart';
import 'scheduled_screens.dart';

/// Recharge par mobile money : le client valide ensuite sur son téléphone, l'app attend la confirmation.
class TopupScreen extends StatefulWidget {
  const TopupScreen({super.key, required this.app, this.pollInterval = const Duration(seconds: 3)});

  final AppState app;
  final Duration pollInterval;

  @override
  State<TopupScreen> createState() => _TopupScreenState();
}

class _TopupScreenState extends State<TopupScreen> with ApiAction {
  final amount = TextEditingController();
  Operator op = Operator.mpesa;
  Topup? topup;
  Timer? _timer;
  int _polls = 0;

  /// Gardée pour toute la saisie : un nouvel essai après une coupure ne crée pas une deuxième recharge.
  String _key = newIdempotencyKey();

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _start() async {
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 25 ou 12,50.');
    final t = await run(widget.app, () => widget.app.api.startTopup(op, minor, _key));
    if (t == null) return;
    setState(() => topup = t);
    _timer = Timer.periodic(widget.pollInterval, (_) => _poll());
  }

  Future<void> _poll() async {
    final t = topup;
    if (t == null || t.status != 'PENDING') return;
    if (++_polls > 40) {
      _timer?.cancel();
      return setState(
        () => error = 'Toujours en attente. Le solde se mettra à jour dès la confirmation de l\'opérateur.',
      );
    }
    try {
      final next = await widget.app.api.topup(t.id);
      if (!mounted) return;
      setState(() => topup = next);
      if (next.status != 'PENDING') _timer?.cancel();
    } on ApiException {
      // Réseau instable : on réessaie au prochain tour.
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = topup;
    if (t != null) return _status(t);
    return FormPage(
      title: 'Recharger',
      subtitle: 'L\'argent vient de votre compte mobile money. Vous validerez sur votre téléphone.',
      children: [
        ErrorBanner(error),
        Wrap(
          spacing: 8,
          children: [
            for (final o in Operator.values)
              ChoiceChip(
                label: Text(o.label),
                selected: op == o,
                onSelected: (_) => setState(() {
                  op = o;
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
          decoration: const InputDecoration(labelText: 'Montant', suffixText: '\$'),
          onChanged: (_) => _key = newIdempotencyKey(),
        ),
        const SizedBox(height: 20),
        BusyButton(label: 'Recharger', busy: busy, onPressed: _start),
      ],
    );
  }

  Widget _status(Topup t) {
    final (icon, color, title, text) = switch (t.status) {
      'CONFIRMED' => (
        Icons.check_circle,
        Brand.success,
        'Recharge reçue',
        '${formatUsd(t.amountMinor)} ajoutés à votre solde.',
      ),
      'FAILED' => (Icons.cancel, Brand.danger, 'Recharge refusée', topupFailureText(t.failureCode, op.label)),
      'REVIEW' => (
        Icons.manage_search,
        Brand.ochre,
        'Recharge en vérification',
        'Nous vérifions ce paiement avec ${op.label}. Si l\'argent a quitté votre compte, il arrivera sur votre '
            'solde après vérification, en général sous 24 heures. Vous n\'avez rien à refaire.',
      ),
      _ => (
        Icons.hourglass_top,
        Brand.ochre,
        'Validez sur votre téléphone',
        'Un message ${op.label} vous demande de confirmer ${formatUsd(t.amountMinor)} avec votre code secret.',
      ),
    };
    return FormPage(
      title: 'Recharger',
      children: [
        const SizedBox(height: 24),
        Icon(icon, size: 72, color: color),
        const SizedBox(height: 16),
        Text(
          title,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 8),
        Text(
          text,
          textAlign: TextAlign.center,
          style: TextStyle(color: context.pal.muted, fontSize: 15),
        ),
        const SizedBox(height: 24),
        ErrorBanner(error),
        if (t.status == 'PENDING') const Center(child: CircularProgressIndicator()),
        if (t.status != 'PENDING')
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
      ],
    );
  }
}

/// Retrait vers le compte mobile money du client : montant, vérification des frais, PIN, puis suivi.
class WithdrawScreen extends StatefulWidget {
  const WithdrawScreen({super.key, required this.app, this.limits, this.pollInterval = const Duration(seconds: 3)});

  final AppState app;
  final Limits? limits;
  final Duration pollInterval;

  @override
  State<WithdrawScreen> createState() => _WithdrawScreenState();
}

class _WithdrawScreenState extends State<WithdrawScreen> with ApiAction {
  final amount = TextEditingController();
  final pin = TextEditingController();
  late Operator op = operatorForPhone(widget.app.customer!.phone) ?? Operator.mpesa;
  FeeQuote? quote;
  Withdrawal? withdrawal;
  Timer? _timer;
  int _polls = 0;
  String _key = newIdempotencyKey();

  String get _phone => widget.app.customer!.phone;

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _review() async {
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 25 ou 12,50.');
    final l = widget.limits;
    if (l != null && minor > l.perTransactionMinor) {
      return setState(() => error = 'Votre plafond par opération est de ${formatUsd(l.perTransactionMinor)}.');
    }
    final q = await run(widget.app, () => widget.app.api.withdrawalFee(minor));
    if (q == null) return;
    setState(() {
      quote = q;
      _key = newIdempotencyKey(); // une nouvelle vérification est un nouveau retrait
    });
  }

  Future<void> _confirm() async {
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final w = await run(
      widget.app,
      () => widget.app.api.withdraw(op: op, amountMinor: quote!.amountMinor, pin: pin.text, idempotencyKey: _key),
    );
    pin.clear();
    if (w == null) return;
    setState(() => withdrawal = w);
    if (w.status == 'PENDING') _timer = Timer.periodic(widget.pollInterval, (_) => _poll());
  }

  Future<void> _poll() async {
    final w = withdrawal;
    if (w == null || w.status != 'PENDING') return;
    if (++_polls > 40) {
      _timer?.cancel();
      return setState(() => error = 'Toujours en cours chez ${op.label}. Vous recevrez l\'argent dès que l\'opérateur aura versé.');
    }
    try {
      final next = await widget.app.api.withdrawal(w.id);
      if (!mounted) return;
      setState(() => withdrawal = next);
      if (next.status != 'PENDING') _timer?.cancel();
    } on ApiException {
      // Réseau instable : on réessaie au prochain tour.
    }
  }

  @override
  Widget build(BuildContext context) {
    final w = withdrawal;
    if (w != null) return _status(w);
    final q = quote;
    if (q != null) {
      return FormPage(
        title: 'Vérifier le retrait',
        children: [
          ErrorBanner(error),
          SoftCard(
            child: Column(
              children: [
                _Row('Vers', '${op.label} · $_phone'),
                _Row('Montant reçu', formatUsd(q.amountMinor)),
                _Row('Frais', formatUsd(q.feeMinor)),
                Divider(height: 28, color: context.pal.divider),
                _Row('Total débité', formatUsd(q.totalMinor), strong: true),
              ],
            ),
          ),
          const SizedBox(height: 24),
          PinField(controller: pin, label: 'PIN pour confirmer', autofocus: true, onSubmitted: (_) => _confirm()),
          const SizedBox(height: 20),
          BusyButton(label: 'Retirer ${formatUsd(q.amountMinor)}', busy: busy, onPressed: _confirm),
          TextButton(onPressed: () => setState(() => quote = null), child: const Text('Modifier')),
        ],
      );
    }
    return FormPage(
      title: 'Retirer',
      subtitle: 'L\'argent part de votre solde vers votre compte mobile money, au $_phone.',
      children: [
        ErrorBanner(error),
        Wrap(
          spacing: 8,
          children: [
            for (final o in Operator.values)
              ChoiceChip(label: Text(o.label), selected: op == o, onSelected: (_) => setState(() => op = o)),
          ],
        ),
        const SizedBox(height: 16),
        TextField(
          key: const ValueKey('amount'),
          controller: amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Montant à recevoir', suffixText: '\$'),
        ),
        const SizedBox(height: 20),
        BusyButton(label: 'Continuer', busy: busy, onPressed: _review),
      ],
    );
  }

  Widget _status(Withdrawal w) {
    final (icon, color, title, text) = switch (w.status) {
      'CONFIRMED' => (
        Icons.check_circle,
        Brand.success,
        'Argent envoyé',
        '${formatUsd(w.amountMinor)} sont arrivés sur votre compte ${op.label}.',
      ),
      'FAILED' => (
        Icons.cancel,
        Brand.danger,
        'Retrait refusé',
        '${withdrawalFailureText(w.failureCode, op.label)} Votre solde a été recrédité, frais compris.',
      ),
      'REVIEW' => (
        Icons.manage_search,
        Brand.ochre,
        'Retrait en vérification',
        'Nous vérifions ce versement avec ${op.label}. Si l\'argent n\'arrive pas, il vous sera rendu, frais compris. '
            'Vous n\'avez rien à refaire.',
      ),
      _ => (
        Icons.hourglass_top,
        Brand.ochre,
        'Retrait en cours',
        '${formatUsd(w.amountMinor)} sont en route vers votre compte ${op.label}. Cela prend en général moins d\'une minute.',
      ),
    };
    return FormPage(
      title: 'Retirer',
      children: [
        const SizedBox(height: 24),
        Icon(icon, size: 72, color: color),
        const SizedBox(height: 16),
        Text(title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Text(text, textAlign: TextAlign.center, style: TextStyle(color: context.pal.muted, fontSize: 15)),
        const SizedBox(height: 24),
        ErrorBanner(error),
        if (w.status == 'PENDING') const Center(child: CircularProgressIndicator()),
        if (w.status != 'PENDING')
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
      ],
    );
  }
}

/// Motif d'un retrait refusé, en mots simples.
String withdrawalFailureText(String? code, String operator) => switch (code) {
  'RECIPIENT_NOT_FOUND' || 'PAYER_NOT_FOUND' || 'INVALID_PHONE_NUMBER' =>
    'Ce numéro n\'a pas de compte $operator. Choisissez l\'opérateur de votre numéro.',
  'RECIPIENT_NOT_ALLOWED_TO_RECEIVE' || 'PAYER_LIMIT_REACHED' || 'AMOUNT_OUT_OF_BOUNDS' =>
    'Votre compte $operator ne peut pas recevoir ce montant pour le moment.',
  'INSUFFICIENT_BALANCE' || 'PROVIDER_TEMPORARILY_UNAVAILABLE' =>
    '$operator est momentanément indisponible. Réessayez dans quelques minutes.',
  'NOT_RECEIVED' => 'La demande n\'est pas arrivée chez $operator.',
  _ => 'L\'opérateur n\'a pas pu verser l\'argent.',
};

/// Explication d'un refus, en mots simples. Le message brut de l'opérateur n'est jamais montré.
String topupFailureText(String? code, String operator) => switch (code) {
  'INSUFFICIENT_BALANCE' => 'Solde $operator insuffisant. Rien n\'a été débité.',
  'PAYMENT_NOT_APPROVED' =>
    'Le paiement n\'a pas été validé sur votre téléphone, ou le délai est dépassé. Rien n\'a été débité.',
  'PAYER_NOT_FOUND' || 'INVALID_PHONE_NUMBER' =>
    'Ce numéro n\'a pas de compte $operator. Choisissez l\'opérateur de votre numéro.',
  'PAYER_LIMIT_REACHED' || 'AMOUNT_OUT_OF_BOUNDS' =>
    'Ce montant dépasse les plafonds de votre compte $operator. Essayez un montant plus petit.',
  'PROVIDER_TEMPORARILY_UNAVAILABLE' => '$operator est momentanément indisponible. Réessayez dans quelques minutes.',
  'NOT_RECEIVED' => 'La demande n\'est pas arrivée chez $operator. Rien n\'a été débité, vous pouvez réessayer.',
  _ => 'L\'opérateur a refusé le paiement. Rien n\'a été débité.',
};

/// Envoi à un autre client : saisie, vérification avec les frais, puis confirmation par PIN.
class TransferScreen extends StatefulWidget {
  const TransferScreen({super.key, required this.app, this.limits});

  final AppState app;
  final Limits? limits;

  @override
  State<TransferScreen> createState() => _TransferScreenState();
}

class _TransferScreenState extends State<TransferScreen> with ApiAction {
  final phone = TextEditingController();
  final amount = TextEditingController();
  final note = TextEditingController();
  final pin = TextEditingController();
  String? toPhone;
  Recipient? recipient;
  FeeQuote? quote;
  TransferResult? done;
  List<Recipient> recent = const [];
  List<Favorite> favorites = const [];
  int waiting = 0;

  /// Numéro sans compte, pour proposer l'envoi en attente.
  String? noAccountPhone;
  Favorite? savedFavorite;
  final nickname = TextEditingController();
  String _key = newIdempotencyKey();

  @override
  void initState() {
    super.initState();
    _loadRecent();
    _loadFavorites();
    _loadWaiting();
    // Un autre numéro : la proposition d'envoi sans compte ne vaut plus.
    phone.addListener(() {
      if (noAccountPhone != null && normalizePhone(phone.text) != noAccountPhone) setState(() => noAccountPhone = null);
    });
  }

  Future<void> _loadWaiting() async {
    try {
      final l = await widget.app.api.pendingTransfers();
      if (mounted) setState(() => waiting = l.where((t) => t.isPending).length);
    } on ApiException {
      // Le raccourci n'apparaît simplement pas.
    }
  }

  Future<void> _openWaiting() async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => PendingTransfersScreen(app: widget.app)));
    _loadWaiting();
  }

  /// Le numéro n'a pas de compte : l'argent peut l'attendre, le temps que le proche s'inscrive.
  Future<void> _sendWithoutAccount() async {
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 25 ou 12,50.');
    final q = await run(widget.app, () => widget.app.api.transferFee(minor));
    if (q == null || !mounted) return;
    final sent = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => InviteTransferScreen(app: widget.app, phone: noAccountPhone!, quote: q, note: note.text.trim()),
      ),
    );
    if (sent == true && mounted) Navigator.of(context).pop();
  }

  Future<void> _loadFavorites() async {
    try {
      final f = await widget.app.api.favorites();
      if (mounted) setState(() => favorites = f);
    } on ApiException {
      // Sans favoris, les récents et le numéro suffisent.
    }
  }

  Favorite? get _existingFavorite {
    for (final f in favorites) {
      if (f.phone == toPhone) return f;
    }
    return null;
  }

  Future<void> _saveFavorite() async {
    final n = nickname.text.trim();
    if (n.isEmpty || n.length > 30) return setState(() => error = 'Donnez un surnom de 1 à 30 caractères.');
    final f = await run(widget.app, () => widget.app.api.saveFavorite(toPhone!, n));
    if (f != null) setState(() => savedFavorite = f);
  }

  Future<void> _manageFavorites() async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => FavoritesScreen(app: widget.app)),
    );
    _loadFavorites();
  }

  void _pick(String p) => setState(() => phone.text = p.replaceFirst('+243', ''));

  Future<void> _loadRecent() async {
    try {
      final r = await widget.app.api.recentRecipients();
      if (mounted) setState(() => recent = r);
    } on ApiException {
      // Pas de raccourcis : le client tape le numéro.
    }
  }

  Future<void> _review() async {
    final p = normalizePhone(phone.text);
    if (p == null) return setState(() => error = 'Numéro congolais attendu (9 chiffres après +243).');
    final minor = parseUsd(amount.text);
    if (minor == null) return setState(() => error = 'Entrez un montant, par exemple 25 ou 12,50.');
    final l = widget.limits;
    if (l != null && minor > l.perTransactionMinor) {
      return setState(() => error = 'Votre plafond par opération est de ${formatUsd(l.perTransactionMinor)}.');
    }
    // Le nom d'abord : un mauvais numéro est arrêté avant de calculer quoi que ce soit.
    var noAccount = false;
    final who = await run(widget.app, () async {
      try {
        return await widget.app.api.lookupRecipient(p);
      } on ApiException catch (e) {
        if (e.code != 'RECIPIENT_NOT_FOUND') rethrow;
        noAccount = true;
        return null;
      }
    });
    setState(() => noAccountPhone = noAccount ? p : null);
    if (who == null) return;
    final q = await run(widget.app, () => widget.app.api.transferFee(minor));
    if (q == null) return;
    setState(() {
      toPhone = p;
      recipient = who;
      quote = q;
      _key = newIdempotencyKey(); // une nouvelle vérification est un nouvel envoi
    });
  }

  Future<void> _send() async {
    if (pin.text.length != 4) return setState(() => error = 'Entrez vos 4 chiffres de PIN.');
    final r = await run(
      widget.app,
      () => widget.app.api.transfer(
        toPhone: toPhone!,
        amountMinor: quote!.amountMinor,
        pin: pin.text,
        idempotencyKey: _key,
        note: note.text.trim(),
      ),
    );
    pin.clear();
    if (r != null) setState(() => done = r);
  }

  /// Un favori n'apparaît pas une deuxième fois dans les récents.
  List<Recipient> get _recentNotFavorite {
    final fav = {for (final f in favorites) f.phone};
    return recent.where((r) => !fav.contains(r.phone)).toList();
  }

  /// Après un envoi : proposer d'enregistrer le destinataire avec un surnom.
  List<Widget> _favoriteBlock(BuildContext context) {
    final existing = savedFavorite ?? _existingFavorite;
    if (existing != null) {
      return [
        Row(
          key: const ValueKey('favorite-saved'),
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.star_rounded, color: Brand.ochreDeep),
            const SizedBox(width: 6),
            Text('Dans vos favoris : ${existing.nickname}', style: const TextStyle(fontWeight: FontWeight.w600)),
          ],
        ),
        const SizedBox(height: 16),
      ];
    }
    return [
      SoftCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Ajouter aux favoris', style: TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Text(
              'La prochaine fois, touchez son surnom au lieu de taper le numéro.',
              style: TextStyle(color: context.pal.muted, fontSize: 13),
            ),
            const SizedBox(height: 10),
            TextField(
              key: const ValueKey('favorite-nickname'),
              controller: nickname,
              maxLength: 30,
              decoration: const InputDecoration(labelText: 'Surnom', hintText: 'Maman, Boutique…'),
            ),
            BusyButton(label: 'Ajouter aux favoris', busy: busy, onPressed: _saveFavorite),
          ],
        ),
      ),
      const SizedBox(height: 16),
    ];
  }

  @override
  Widget build(BuildContext context) {
    if (done != null) {
      return FormPage(
        title: 'Envoyer',
        children: [
          const SizedBox(height: 24),
          const Icon(Icons.check_circle, size: 72, color: Brand.success),
          const SizedBox(height: 16),
          Text(
            '${formatUsd(done!.amountMinor)} envoyés',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            'à ${recipient!.displayName} ($toPhone). Frais : ${formatUsd(done!.feeMinor)}.',
            textAlign: TextAlign.center,
            style: TextStyle(color: context.pal.muted),
          ),
          const SizedBox(height: 24),
          ErrorBanner(error),
          ..._favoriteBlock(context),
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Terminé')),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: () => Navigator.of(context).pushReplacement(
              MaterialPageRoute<void>(
                builder: (_) => NewScheduledTransferScreen(
                  app: widget.app,
                  toPhone: toPhone,
                  amountMinor: done!.amountMinor,
                  note: note.text.trim(),
                ),
              ),
            ),
            icon: const Icon(Icons.event_repeat_rounded),
            label: const Text('Répéter cet envoi chaque mois'),
          ),
        ],
      );
    }
    final q = quote;
    if (q != null) {
      return FormPage(
        title: 'Vérifier l\'envoi',
        children: [
          ErrorBanner(error),
          SoftCard(
            child: Column(
              children: [
                _Row('Destinataire', recipient!.displayName, key: const ValueKey('recipient-name')),
                _Row('Numéro', toPhone!),
                _Row('Montant reçu', formatUsd(q.amountMinor)),
                _Row('Frais', formatUsd(q.feeMinor)),
                Divider(height: 28, color: context.pal.divider),
                _Row('Total débité', formatUsd(q.totalMinor), strong: true),
              ],
            ),
          ),
          const SizedBox(height: 10),
          Text(
            'Ce n\'est pas la bonne personne ? Touchez « Modifier » : un envoi ne peut pas être annulé.',
            style: TextStyle(color: context.pal.muted, fontSize: 13),
          ),
          const SizedBox(height: 16),
          PinField(controller: pin, label: 'PIN pour confirmer', autofocus: true, onSubmitted: (_) => _send()),
          const SizedBox(height: 20),
          BusyButton(label: 'Envoyer ${formatUsd(q.amountMinor)}', busy: busy, onPressed: _send),
          TextButton(onPressed: () => setState(() => quote = null), child: const Text('Modifier')),
        ],
      );
    }
    return FormPage(
      title: 'Envoyer',
      subtitle: 'À un autre client Capital Invest, avec son numéro de téléphone.',
      children: [
        ErrorBanner(error),
        if (noAccountPhone != null) ...[
          SoftCard(
            key: const ValueKey('no-account'),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text('Ce numéro n\'a pas encore de compte', style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                Text(
                  'Envoyez quand même : votre proche reçoit un SMS et l\'argent l\'attend 7 jours, le temps d\'ouvrir son compte. '
                  'Sinon, il vous revient, frais compris.',
                  style: TextStyle(color: context.pal.muted, fontSize: 13),
                ),
                const SizedBox(height: 12),
                BusyButton(label: 'Envoyer quand même', busy: busy, onPressed: _sendWithoutAccount),
              ],
            ),
          ),
          const SizedBox(height: 12),
        ],
        if (waiting > 0) ...[
          SoftCard(
            key: const ValueKey('waiting'),
            onTap: _openWaiting,
            child: Row(
              children: [
                const Icon(Icons.hourglass_top_rounded, color: Brand.ochreDeep),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    waiting == 1 ? '1 envoi attend l\'inscription d\'un proche' : '$waiting envois attendent l\'inscription de proches',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                const Icon(Icons.chevron_right_rounded),
              ],
            ),
          ),
          const SizedBox(height: 12),
        ],
        if (favorites.isNotEmpty) ...[
          Row(
            children: [
              Expanded(child: Text('Favoris', style: TextStyle(color: context.pal.muted, fontSize: 13))),
              TextButton(
                key: const ValueKey('manage-favorites'),
                onPressed: _manageFavorites,
                child: const Text('Gérer'),
              ),
            ],
          ),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              for (final f in favorites)
                ActionChip(
                  key: ValueKey('favorite-${f.phone}'),
                  avatar: const Icon(Icons.star_rounded, size: 18, color: Brand.ochreDeep),
                  label: Text(f.nickname),
                  tooltip: f.displayName,
                  onPressed: () => _pick(f.phone),
                ),
            ],
          ),
          const SizedBox(height: 12),
        ],
        if (_recentNotFavorite.isNotEmpty) ...[
          Text('Récents', style: TextStyle(color: context.pal.muted, fontSize: 13)),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              for (final r in _recentNotFavorite)
                ActionChip(
                  key: ValueKey('recent-${r.phone}'),
                  avatar: const Icon(Icons.person_outline_rounded, size: 18),
                  label: Text(r.displayName),
                  // Le champ affiche déjà +243 : on n'y met que les 9 chiffres.
                  onPressed: () => _pick(r.phone),
                ),
            ],
          ),
          const SizedBox(height: 12),
        ],
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
          controller: note,
          maxLength: 140,
          decoration: const InputDecoration(labelText: 'Motif (facultatif)'),
        ),
        const SizedBox(height: 12),
        BusyButton(label: 'Continuer', busy: busy, onPressed: _review),
      ],
    );
  }
}

/// Liste des favoris, pour en retirer.
class FavoritesScreen extends StatefulWidget {
  const FavoritesScreen({super.key, required this.app});

  final AppState app;

  @override
  State<FavoritesScreen> createState() => _FavoritesScreenState();
}

class _FavoritesScreenState extends State<FavoritesScreen> with ApiAction {
  List<Favorite>? items;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final f = await run(widget.app, () => widget.app.api.favorites());
    if (f != null) setState(() => items = f);
  }

  Future<void> _remove(Favorite f) async {
    final ok = await run(widget.app, () async {
      await widget.app.api.deleteFavorite(f.id);
      return true;
    });
    if (ok == true) setState(() => items = items!.where((x) => x.id != f.id).toList());
  }

  @override
  Widget build(BuildContext context) {
    final list = items;
    return FormPage(
      title: 'Mes favoris',
      subtitle: 'Ajoutez un favori depuis l\'écran qui suit un envoi. 20 au plus.',
      children: [
        ErrorBanner(error),
        if (list == null && error == null) const Center(child: CircularProgressIndicator()),
        if (list != null && list.isEmpty)
          Text('Aucun favori pour le moment.', style: TextStyle(color: context.pal.muted)),
        for (final f in list ?? const <Favorite>[])
          SoftCard(
            key: ValueKey('favorite-row-${f.phone}'),
            child: Row(
              children: [
                const Icon(Icons.star_rounded, color: Brand.ochreDeep),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(f.nickname, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                      Text('${f.displayName} · ${f.phone}', style: TextStyle(color: context.pal.muted, fontSize: 13)),
                    ],
                  ),
                ),
                IconButton(
                  key: ValueKey('remove-favorite-${f.phone}'),
                  tooltip: 'Retirer',
                  onPressed: busy ? null : () => _remove(f),
                  icon: const Icon(Icons.delete_outline_rounded),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {super.key, this.strong = false});

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
        Text(
          value,
          style: TextStyle(fontSize: strong ? 20 : 16, fontWeight: strong ? FontWeight.w800 : FontWeight.w600),
        ),
      ],
    ),
  );
}
